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

        } catch (e: Exception) {
            e.printStackTrace()
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
            e.printStackTrace()
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()

        stopEverything()
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
                    
                    val speedRaw = prefs.getInt(engineData.rateKey, 50)
                    val pitchRaw = prefs.getInt(engineData.pitchKey, 50)

                    val finalSpeed = 0.5f + (speedRaw / 100.0f)
                    val finalPitch = 0.5f + (pitchRaw / 100.0f)

                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, getVolumeCorrection(engineData.pkgName))
                    val uuid = UUID.randomUUID().toString()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

                    processDualThreads(engine, params, chunk.text, callback, uuid, finalSpeed, finalPitch)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (!isStopped.get()) {
                    try { callback?.done() } catch (e: Exception) {}
                }
            }
        }
    }

    data class EngineData(
        val engine: TextToSpeech?,
        val pkgName: String,
        val rateKey: String,
        val pitchKey: String
    )

    private fun getEngineDataForLang(lang: String): EngineData {
        return when (lang) {
            "SHAN" -> EngineData(if (shanEngine != null) shanEngine else englishEngine, shanPkgName, "rate_shan", "pitch_shan")
            "MYANMAR" -> EngineData(if (burmeseEngine != null) burmeseEngine else englishEngine, burmesePkgName, "rate_burmese", "pitch_burmese")
            else -> EngineData(englishEngine, englishPkgName, "rate_english", "pitch_english")
        }
    }

    private fun processDualThreads(
        engine: TextToSpeech, 
        params: Bundle, 
        text: String, 
        callback: SynthesisCallback, 
        uuid: String,
        speed: Float,
        pitch: Float
    ) {
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
                } finally {
                    try { lW?.close() } catch (e: Exception) {}
                }
            }

            readerFuture = pipeExecutor.submit {
                var fis: FileInputStream? = null
                try {
                    fis = FileInputStream(lR!!.fileDescriptor)
                    val buffer = ByteArray(4096)
                    
                    val header = ByteArray(44)
                    val headerRead = fis.read(header)
                    
                    if (headerRead == 44) {
                        val realRate = (header[24].toInt() and 0xFF) or
                                       ((header[25].toInt() and 0xFF) shl 8) or
                                       ((header[26].toInt() and 0xFF) shl 16) or
                                       ((header[27].toInt() and 0xFF) shl 24)
                        
                        AudioProcessor.initSonic(realRate, 1)
                    } else {
                        AudioProcessor.initSonic(16000, 1)
                    }

                    AudioProcessor.setConfig(speed, pitch)
                    
                    while (!isStopped.get()) {
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
            e.printStackTrace()
        } finally {
            try { lW?.close() } catch (e: Exception) {}
            try { lR?.close() } catch (e: Exception) {}
        }
    }

    private fun sendAudioToSystem(out: ByteArray, callback: SynthesisCallback) {
        if (out.isEmpty()) return
        synchronized(callback) {
            try {
                if (isStopped.get()) return 
                var offset = 0
                while (offset < out.size) {
                    val chunkLen = min(4096, out.size - offset)
                    callback.audioAvailable(out, offset, chunkLen)
                    offset += chunkLen
                }
            } catch (e: Exception) {}
        }
    }

    private fun stopEverything() {
        isStopped.set(true)
        currentTask?.cancel(true) 
        try { AudioProcessor.flush() } catch (e: Exception) {}
    }

    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return when {
            l.contains("vocalizer") -> 0.85f
            l.contains("eloquence") -> 0.6f
            else -> 1.0f
        }
    }

    override fun onStop() { stopEverything() }
    override fun onDestroy() { 
        stopEverything()
        controllerExecutor.shutdownNow()
        pipeExecutor.shutdownNow()
        try { shanEngine?.shutdown() } catch(e:Exception){}
        try { burmeseEngine?.shutdown() } catch(e:Exception){}
        try { englishEngine?.shutdown() } catch(e:Exception){}
        super.onDestroy() 
    }
    
    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

