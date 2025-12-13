package com.shan.tts.manager

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.os.PowerManager
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class LangChunk(val text: String, val lang: String)
data class WavInfo(val sampleRate: Int, val channels: Int) // New helper class

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null
    
    private val executor = Executors.newSingleThreadExecutor()
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Global variable to store the session's Sample Rate
    private var currentSessionRate = 0
    private var currentSessionChannels = 1

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service", "Service Created (Dynamic Rate Mode)")
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherryTTS:WakeLock")
        wakeLock?.setReferenceCounted(false)
        
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        initEngine("Shan", prefs.getString("pref_shan_pkg", "com.espeak.ng")) { shanEngine = it }
        initEngine("Burmese", prefs.getString("pref_burmese_pkg", "com.google.android.tts")) { 
            burmeseEngine = it
            it.language = Locale("my", "MM")
        }
        initEngine("English", prefs.getString("pref_english_pkg", "com.google.android.tts")) { 
            englishEngine = it
            it.language = Locale.US
        }
    }

    private fun initEngine(name: String, pkg: String?, onSuccess: (TextToSpeech) -> Unit) {
        if (pkg.isNullOrEmpty()) return
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    AppLogger.log("Init", "$name initialized SUCCESS")
                    onSuccess(tempTTS!!)
                    tempTTS?.setOnUtteranceProgressListener(object : UtteranceProgressListener(){
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {}
                        override fun onError(id: String?) {}
                    })
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.log("InitError", "Crash on $name: ${e.message}")
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
        // Reset Logic
        isStopped.set(true)
        currentTask?.cancel(true)
        isStopped.set(false)
        
        // Reset Session Rate
        currentSessionRate = 0 
        
        if (wakeLock?.isHeld == false) wakeLock?.acquire(60000)

        currentTask = executor.submit {
            try {
                val chunks = LanguageUtils.splitHelper(text) 
                if (chunks.isEmpty()) {
                    // Default safe start if empty
                    callback?.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callback?.done()
                    return@submit
                }

                AppLogger.log("Split", "Chunks: ${chunks.size}")
                var hasStartedCallback = false

                for ((index, chunk) in chunks.withIndex()) {
                    if (isStopped.get()) break
                    val engine = getEngine(chunk.lang) ?: continue
                    
                    val params = applyRateAndPitch(engine, chunk.lang, request)
                    val success = processWithFile(engine, chunk.text, params, callback, hasStartedCallback)
                    if (success) hasStartedCallback = true
                }
                
                if (!hasStartedCallback) {
                     callback?.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

            } catch (e: Exception) {
                AppLogger.log("WorkerError", "${e.message}")
            } finally {
                if (!isStopped.get()) callback?.done()
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }
    
    private fun applyRateAndPitch(engine: TextToSpeech, lang: String, request: SynthesisRequest?): Bundle {
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100
        
        var userRate = 100
        var userPitch = 100
        
        when(lang) {
            "SHAN" -> {
                userRate = prefs.getInt("rate_shan", 100)
                userPitch = prefs.getInt("pitch_shan", 100)
            }
            "MYANMAR" -> {
                userRate = prefs.getInt("rate_burmese", 100)
                userPitch = prefs.getInt("pitch_burmese", 100)
            }
            else -> {
                userRate = prefs.getInt("rate_english", 100)
                userPitch = prefs.getInt("pitch_english", 100)
            }
        }

        val finalRate = (sysRate * userRate) / 10000.0f
        val finalPitch = (sysPitch * userPitch) / 10000.0f
        
        engine.setSpeechRate(finalRate)
        engine.setPitch(finalPitch)

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) 
        return params
    }

    private fun processWithFile(engine: TextToSpeech, text: String, params: Bundle, callback: SynthesisCallback?, alreadyStarted: Boolean): Boolean {
        var didStart = alreadyStarted
        val uuid = UUID.randomUUID().toString()
        val tempFile = File.createTempFile("tts_", ".wav", cacheDir)
        val latch = CountDownLatch(1)
        var success = false

        try {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == uuid) latch.countDown() }
                override fun onError(utteranceId: String?) { if (utteranceId == uuid) latch.countDown() }
            })

            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)
            val result = engine.synthesizeToFile(text, params, tempFile.absolutePath)
            
            if (result == TextToSpeech.SUCCESS) {
                latch.await(5, TimeUnit.SECONDS)
                
                if (tempFile.exists() && tempFile.length() > 44) {
                    val fis = FileInputStream(tempFile)
                    val header = ByteArray(44)
                    fis.read(header)
                    
                    val wavInfo = getWavInfo(header)
                    
                    // *** CRITICAL CHANGE: DYNAMIC START ***
                    if (!didStart) {
                        // ပထမဆုံး Chunk မှာ ပါလာတဲ့ Rate အတိုင်း TalkBack ကို Start လုပ်မယ်
                        currentSessionRate = wavInfo.sampleRate
                        currentSessionChannels = wavInfo.channels
                        
                        AppLogger.log("AudioConfig", "Start Session: ${currentSessionRate}Hz / ${currentSessionChannels}Ch")
                        
                        // Callback start with ORIGINAL rate
                        callback?.start(currentSessionRate, AudioFormat.ENCODING_PCM_16BIT, currentSessionChannels)
                        didStart = true
                    }
                    
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                         if (isStopped.get()) break
                         
                         // Session Rate နဲ့ ဝင်လာတဲ့ Rate မတူမှသာ Resample လုပ်မယ်
                         // တူရင် Direct Pass လုပ်မယ် (Quality မကျတော့ဘူး)
                         val outputBytes = if (wavInfo.sampleRate != currentSessionRate) {
                             AudioResampler.resampleChunk(buffer, bytesRead, wavInfo.sampleRate, currentSessionRate)
                         } else {
                             buffer.copyOfRange(0, bytesRead)
                         }
                         
                         if (outputBytes.isNotEmpty()) {
                             callback?.audioAvailable(outputBytes, 0, outputBytes.size)
                         }
                    }
                    fis.close()
                    success = true
                }
            }
        } catch (e: Exception) {
            AppLogger.log("FileError", "Err: ${e.message}")
        } finally {
            tempFile.delete()
        }
        return didStart
    }
    
    private fun getWavInfo(header: ByteArray): WavInfo {
        if (header.size < 44) return WavInfo(16000, 1)
        
        // Parse Sample Rate (Byte 24-27)
        val rate = (header[24].toInt() and 0xFF) or
               ((header[25].toInt() and 0xFF) shl 8) or
               ((header[26].toInt() and 0xFF) shl 16) or
               ((header[27].toInt() and 0xFF) shl 24)
               
        // Parse Channels (Byte 22-23)
        val channels = (header[22].toInt() and 0xFF) or
               ((header[23].toInt() and 0xFF) shl 8)
               
        val safeRate = if (rate > 0) rate else 16000
        val safeChannels = if (channels > 0) channels else 1
        
        return WavInfo(safeRate, safeChannels)
    }
    
    // ... Other methods (onStop, onDestroy, etc.) remain same
    private fun getEngine(lang: String): TextToSpeech? {
        return when (lang) {
            "SHAN" -> if (shanEngine != null) shanEngine else englishEngine
            "MYANMAR" -> if (burmeseEngine != null) burmeseEngine else englishEngine
            else -> englishEngine
        }
    }

    override fun onStop() {
        isStopped.set(true)
        currentTask?.cancel(true)
    }
    
    override fun onDestroy() {
        isStopped.set(true)
        executor.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onGetVoices(): List<Voice> { return listOf() }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onGetLanguage(): Array<String> { return arrayOf("eng", "USA", "") }
}

