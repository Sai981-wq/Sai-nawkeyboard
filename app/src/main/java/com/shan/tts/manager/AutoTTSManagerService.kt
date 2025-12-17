package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""

    private val controllerExecutor = Executors.newSingleThreadExecutor()
    private val pipeExecutor = Executors.newCachedThreadPool()

    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)

    private lateinit var configPrefs: SharedPreferences
    private lateinit var settingsPrefs: SharedPreferences

    // Target Output Rate (Standard)
    private val OUTPUT_HZ = 24000

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service Created ===")
        try {
            // Note: Settings Activity က ဒီဖိုင်တွေထဲကို Save လုပ်ပေးရပါမယ်
            settingsPrefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)

            shanPkgName = settingsPrefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName, Locale("shn")) { shanEngine = it }

            burmesePkgName = settingsPrefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = settingsPrefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName, Locale.US) { englishEngine = it }

            AudioProcessor.initSonic(16000, 1)
        } catch (e: Exception) {
            AppLogger.error("Error in onCreate", e)
        }
    }

    private fun initEngine(pkg: String?, locale: Locale, onSuccess: (TextToSpeech) -> Unit) {
        if (pkg.isNullOrEmpty()) return
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try { tempTTS?.language = locale } catch (e: Exception) {}
                    onSuccess(tempTTS!!)
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.error("Crash initializing $pkg", e)
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()

        stopEverything("New Request")
        isStopped.set(false)

        currentTask = controllerExecutor.submit {
            try {
                if (callback == null) return@submit
                val rawChunks = LanguageUtils.splitHelper(text)
                if (rawChunks.isEmpty()) {
                    callback.done()
                    return@submit
                }

                synchronized(callback) {
                    callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

                for (chunk in rawChunks) {
                    if (isStopped.get()) break

                    val engine = getEngine(chunk.lang) ?: continue
                    val activePkg = getPkgName(chunk.lang)

                    // 1. Hz Rate Correction
                    var inputRate = configPrefs.getInt("RATE_$activePkg", 0)
                    if (activePkg.lowercase().contains("eloquence")) {
                        inputRate = 11025
                    } else if (activePkg.lowercase().contains("espeak") || activePkg.lowercase().contains("shan")) {
                        inputRate = 22050
                    } else if (inputRate == 0) {
                        inputRate = getFallbackRate(activePkg)
                    }

                    // 2. Speed & Pitch from Seekbar (Default 100 = 1.0x)
                    // အရင်ဆုံး Engine သီးသန့် Key ကို ရှာမယ်
                    var speedVal = configPrefs.getInt("SPEED_$activePkg", -1)
                    var pitchVal = configPrefs.getInt("PITCH_$activePkg", -1)

                    // မတွေ့ရင် (သို့) မသတ်မှတ်ရသေးရင် Global Key ကို ရှာမယ်
                    if (speedVal == -1) speedVal = configPrefs.getInt("SPEED_Global", 100)
                    if (pitchVal == -1) pitchVal = configPrefs.getInt("PITCH_Global", 100)

                    val finalSpeed = speedVal / 100.0f
                    val finalPitch = pitchVal / 100.0f

                    // DEBUG LOG: ဒီစာကြောင်းက အရေးကြီးပါတယ်။ Log မှာ S နဲ့ P တန်ဖိုး ပြောင်းမပြောင်း ကြည့်ပါ
                    AppLogger.log("[${chunk.lang}] $activePkg ($inputRate Hz) | Speed:$speedVal Pitch:$pitchVal")

                    AudioProcessor.initSonic(inputRate, 1)
                    AudioProcessor.setConfig(finalSpeed, finalPitch)

                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, getVolumeCorrection(activePkg))
                    val uuid = UUID.randomUUID().toString()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

                    processDualThreads(engine, params, chunk.text, callback, uuid)
                }
            } catch (e: Exception) {
                AppLogger.error("Synthesize Critical Error", e)
            } finally {
                if (!isStopped.get()) {
                    try { Thread.sleep(50) } catch (e: Exception) {}
                    callback?.done()
                }
            }
        }
    }
    
    // ... (ကျန်တဲ့ processDualThreads, stopEverything, helper function များသည် ယခင်အတိုင်းဖြစ်သည်) ...

    private fun processDualThreads(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        var lR: ParcelFileDescriptor? = null
        var lW: ParcelFileDescriptor? = null
        
        var writerFuture: Future<*>? = null
        var readerFuture: Future<*>? = null

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            lR = pipe[0]
            lW = pipe[1]
            
            writerFuture = pipeExecutor.submit {
                try {
                    engine.synthesizeToFile(text, params, lW!!, uuid)
                } catch (e: Exception) {
                    if (!isStopped.get()) AppLogger.error("Writer Failed", e)
                } finally {
                    try { lW?.close() } catch (e: Exception) {}
                }
            }

            readerFuture = pipeExecutor.submit {
                var fis: FileInputStream? = null
                try {
                    fis = FileInputStream(lR!!.fileDescriptor)
                    val buffer = ByteArray(2048) 
                    
                    while (!isStopped.get() && !Thread.currentThread().isInterrupted) {
                        val read = fis.read(buffer)
                        if (read == -1) break 
                        
                        if (read > 0) {
                            if (isStopped.get()) break
                            val out = AudioProcessor.processAudio(buffer, read)
                            sendAudioToSystem(out, callback)
                        }
                    }
                    
                    if (!isStopped.get()) {
                        val tail = AudioProcessor.drain()
                        sendAudioToSystem(tail, callback)
                    }

                } catch (e: IOException) {
                } finally {
                    try { fis?.close() } catch (e: Exception) {}
                }
            }

            try { writerFuture.get() } catch (e: Exception) { }
            try { readerFuture.get() } catch (e: Exception) { }

        } catch (e: Exception) {
            AppLogger.error("Setup Error", e)
        } finally {
            try { lW?.close() } catch (e: Exception) {}
            try { lR?.close() } catch (e: Exception) {}
        }
    }

    private fun sendAudioToSystem(out: ByteArray, callback: SynthesisCallback) {
        if (out.isEmpty()) return
        synchronized(callback) {
            try {
                var offset = 0
                while (offset < out.size) {
                    val chunkLen = min(4096, out.size - offset)
                    callback.audioAvailable(out, offset, chunkLen)
                    offset += chunkLen
                }
            } catch (e: Exception) {}
        }
    }

    private fun stopEverything(reason: String) {
        isStopped.set(true)
        currentTask?.cancel(true)
        AudioProcessor.flush()
    }

    private fun getFallbackRate(pkg: String): Int {
        val lower = pkg.lowercase(Locale.ROOT)
        return when {
            lower.contains("eloquence") -> 11025
            lower.contains("espeak") || lower.contains("shan") -> 22050
            lower.contains("google") -> 24000
            else -> 16000
        }
    }

    private fun getPkgName(lang: String) = when (lang) {
        "SHAN" -> shanPkgName
        "MYANMAR" -> burmesePkgName
        else -> englishPkgName
    }

    private fun getEngine(lang: String) = when (lang) {
        "SHAN" -> if (shanEngine != null) shanEngine else englishEngine
        "MYANMAR" -> if (burmeseEngine != null) burmeseEngine else englishEngine
        else -> englishEngine
    }

    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return when {
            l.contains("vocalizer") -> 0.85f
            l.contains("eloquence") -> 0.6f
            else -> 1.0f
        }
    }

    override fun onStop() { stopEverything("onStop") }
    override fun onDestroy() { 
        stopEverything("Destroy")
        controllerExecutor.shutdownNow()
        pipeExecutor.shutdownNow()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        AudioProcessor.flush()
        super.onDestroy() 
    }
    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

