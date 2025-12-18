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
import android.speech.tts.Voice
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
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
    private lateinit var prefs: SharedPreferences

    private val OUTPUT_HZ = 24000
    private var currentSonicRate = 0

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)

            shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName, Locale("shn")) { shanEngine = it }

            burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
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
                
                val rawChunks = TTSUtils.splitHelper(text)
                
                if (rawChunks.isEmpty()) {
                    callback.done()
                    return@submit
                }

                synchronized(callback) {
                    callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

                for (chunk in rawChunks) {
                    if (isStopped.get()) break

                    val engineData = getEngineDataForLang(chunk.lang)
                    val engine = engineData.engine ?: continue
                    val pkgName = engineData.pkgName

                    var inputRate = 16000
                    val lowerPkg = pkgName.lowercase(Locale.ROOT)
                    if (lowerPkg.contains("eloquence")) inputRate = 11025
                    else if (lowerPkg.contains("espeak") || lowerPkg.contains("shan")) inputRate = 22050
                    else if (lowerPkg.contains("google")) inputRate = 24000

                    val speedRaw = prefs.getInt(engineData.rateKey, 50)
                    val pitchRaw = prefs.getInt(engineData.pitchKey, 50)

                    val safeSpeed = if (speedRaw < 5) 5 else speedRaw
                    val safePitch = if (pitchRaw < 5) 5 else pitchRaw

                    val finalSpeed = safeSpeed / 50.0f
                    val finalPitch = safePitch / 50.0f

                    if (currentSonicRate != inputRate) {
                        AudioProcessor.initSonic(inputRate, 1)
                        currentSonicRate = inputRate
                    }
                    AudioProcessor.setConfig(finalSpeed, finalPitch)

                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, getVolumeCorrection(pkgName))
                    val uuid = UUID.randomUUID().toString()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

                    processDualThreads(engine, params, chunk.text, callback, uuid)
                }
            } catch (e: Exception) {
                AppLogger.error("Synthesize Critical Error", e)
            } finally {
                if (!isStopped.get()) callback?.done()
            }
        }
    }

    data class EngineData(val engine: TextToSpeech?, val pkgName: String, val rateKey: String, val pitchKey: String)

    private fun getEngineDataForLang(lang: String): EngineData {
        return when (lang) {
            "SHAN" -> EngineData(if (shanEngine != null) shanEngine else englishEngine, shanPkgName, "rate_shan", "pitch_shan")
            "MYANMAR" -> EngineData(if (burmeseEngine != null) burmeseEngine else englishEngine, burmesePkgName, "rate_burmese", "pitch_burmese")
            else -> EngineData(englishEngine, englishPkgName, "rate_english", "pitch_english")
        }
    }

    private fun processDualThreads(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        var lR: ParcelFileDescriptor? = null
        var lW: ParcelFileDescriptor? = null
        var writerFuture: Future<*>? = null
        var readerFuture: Future<*>? = null

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            lR = pipe[0]; lW = pipe[1]
            
            writerFuture = pipeExecutor.submit {
                try { engine.synthesizeToFile(text, params, lW!!, uuid) } 
                catch (e: Exception) {} 
                finally { try { lW?.close() } catch (e: Exception) {} }
            }

            readerFuture = pipeExecutor.submit {
                var fis: FileInputStream? = null
                var channel: FileChannel? = null
                try {
                    fis = FileInputStream(lR!!.fileDescriptor)
                    channel = fis.channel
                    val buffer = ByteBuffer.allocateDirect(2048)

                    while (!isStopped.get() && !Thread.currentThread().isInterrupted) {
                        buffer.clear()
                        val read = channel.read(buffer)
                        if (read == -1) break
                        
                        if (read > 0) {
                            if (isStopped.get()) break
                            buffer.flip()
                            val out = AudioProcessor.processAudio(buffer, read)
                            sendAudioToSystem(out, callback)
                        }
                    }
                    if (!isStopped.get()) {
                        val tail = AudioProcessor.drain()
                        sendAudioToSystem(tail, callback)
                    }
                } catch (e: IOException) {} 
                finally { try { fis?.close() } catch (e: Exception) {} }
            }
            try { writerFuture.get(); readerFuture.get() } catch (e: Exception) {}
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
        currentSonicRate = 0
    }

    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return when {
            l.contains("vocalizer") -> 0.85f; l.contains("eloquence") -> 0.6f; else -> 1.0f
        }
    }
    
    override fun onStop() { stopEverything("onStop") }
    override fun onDestroy() { stopEverything("Destroy"); controllerExecutor.shutdownNow(); pipeExecutor.shutdownNow(); AudioProcessor.flush(); super.onDestroy() }
    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

