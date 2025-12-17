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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkgName = ""
    private var burmesePkgName = ""
    private var englishPkgName = ""

    private val executor = Executors.newCachedThreadPool()
    
    // Session ID to prevent crashes during seekbar adjustment
    private val currentSessionId = AtomicLong(0)
    
    // Track the last rate to avoid restarting audio track unnecessarily
    private var lastReportedRate = 0

    private lateinit var prefs: SharedPreferences

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
        if (callback == null) return

        val sessionId = currentSessionId.incrementAndGet()
        
        try { AudioProcessor.flush() } catch (e: Exception) {}
        
        // Reset last rate for new request
        lastReportedRate = 0

        executor.submit {
            if (sessionId != currentSessionId.get()) return@submit

            val rawChunks = TTSUtils.splitHelper(text)
            if (rawChunks.isEmpty()) {
                callback.done()
                return@submit
            }

            for (chunk in rawChunks) {
                if (sessionId != currentSessionId.get()) break

                val engineData = getEngineDataForLang(chunk.lang)
                val engine = engineData.engine ?: continue

                val speedRaw = prefs.getInt(engineData.rateKey, 50)
                val pitchRaw = prefs.getInt(engineData.pitchKey, 50)

                val finalSpeed = 0.5f + (speedRaw / 100.0f)
                val finalPitch = 0.5f + (pitchRaw / 100.0f)
                
                val params = Bundle()
                val uuid = UUID.randomUUID().toString()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

                processChunk(sessionId, engine, params, chunk.text, callback, finalSpeed, finalPitch)
            }
            
            if (sessionId == currentSessionId.get()) {
                try { callback.done() } catch (e: Exception) {}
            }
        }
    }

    private fun processChunk(
        sessionId: Long,
        engine: TextToSpeech,
        params: Bundle,
        text: String,
        callback: SynthesisCallback,
        speed: Float,
        pitch: Float
    ) {
        var lR: ParcelFileDescriptor? = null
        var lW: ParcelFileDescriptor? = null
        
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            lR = pipe[0]
            lW = pipe[1]
            
            val writerTask = executor.submit {
                try {
                    engine.synthesizeToFile(text, params, lW!!, UUID.randomUUID().toString())
                } catch (e: Exception) {
                } finally {
                    try { lW?.close() } catch (e: Exception) {}
                }
            }

            var fis: FileInputStream? = null
            try {
                fis = FileInputStream(lR!!.fileDescriptor)
                val header = ByteArray(44)
                val bytesRead = fis.read(header)
                
                var realRate = 16000 
                
                if (bytesRead == 44) {
                    realRate = (header[24].toInt() and 0xFF) or
                               ((header[25].toInt() and 0xFF) shl 8) or
                               ((header[26].toInt() and 0xFF) shl 16) or
                               ((header[27].toInt() and 0xFF) shl 24)
                }

                synchronized(callback) {
                    if (sessionId == currentSessionId.get()) {
                        // Only restart audio track if Hz changes (Smooth transition for mixed langs)
                        if (realRate != lastReportedRate) {
                            callback.start(realRate, AudioFormat.ENCODING_PCM_16BIT, 1)
                            lastReportedRate = realRate
                        }
                    }
                }

                AudioProcessor.initSonic(realRate, 1)
                AudioProcessor.setConfig(speed, pitch)

                val buffer = ByteArray(4096)
                while (sessionId == currentSessionId.get()) {
                    val read = fis.read(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        val out = AudioProcessor.processAudio(buffer, read)
                        sendSafe(sessionId, out, callback)
                    }
                }
                
                if (sessionId == currentSessionId.get()) {
                    val tail = AudioProcessor.drain()
                    sendSafe(sessionId, tail, callback)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { fis?.close() } catch (e: Exception) {}
            }
            
            try { writerTask.get() } catch (e: Exception) {}

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { lW?.close() } catch (e: Exception) {}
            try { lR?.close() } catch (e: Exception) {}
        }
    }

    private fun sendSafe(sessionId: Long, data: ByteArray, callback: SynthesisCallback) {
        if (data.isEmpty()) return
        synchronized(callback) {
            try {
                if (sessionId == currentSessionId.get()) {
                    var offset = 0
                    while (offset < data.size) {
                        val len = min(4096, data.size - offset)
                        callback.audioAvailable(data, offset, len)
                        offset += len
                    }
                }
            } catch (e: Exception) {
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

    override fun onStop() {
        currentSessionId.incrementAndGet() 
        try { AudioProcessor.flush() } catch (e: Exception) {}
    }

    override fun onDestroy() {
        currentSessionId.incrementAndGet()
        executor.shutdownNow()
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

