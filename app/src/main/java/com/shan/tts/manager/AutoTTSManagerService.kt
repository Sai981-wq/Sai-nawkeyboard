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

// Helper classes
data class LangChunk(val text: String, val lang: String)
data class WavInfo(val sampleRate: Int, val channels: Int)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null
    
    private val executor = Executors.newSingleThreadExecutor()
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    
    // *** INDUSTRY STANDARD TARGET: 24000Hz ***
    // 16k Engine များကို 24k သို့ Upsample လုပ်မည်။
    // 24k Engine များကို Direct Play မည်။
    private val TARGET_SAMPLE_RATE = 24000 
    
    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service", "Cherry TTS: Pro Architecture (24kHz)")
        
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
        
        isStopped.set(true)
        currentTask?.cancel(true)
        isStopped.set(false)
        
        if (wakeLock?.isHeld == false) wakeLock?.acquire(60000)

        currentTask = executor.submit {
            try {
                // New Splitter: Cuts long sentences to reduce wait time
                val chunks = LanguageUtils.splitHelper(text) 
                
                if (chunks.isEmpty()) {
                    callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callback?.done()
                    return@submit
                }

                // AppLogger.log("Split", "Processing ${chunks.size} chunks")
                var hasStartedCallback = false

                for ((index, chunk) in chunks.withIndex()) {
                    if (isStopped.get() || Thread.currentThread().isInterrupted) break
                    
                    val engine = getEngine(chunk.lang) ?: continue
                    val params = applyRateAndPitch(engine, chunk.lang, request)
                    
                    val success = processWithFile(engine, chunk.text, params, callback, hasStartedCallback)
                    
                    if (success) hasStartedCallback = true
                }
                
                if (!hasStartedCallback) {
                     callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

            } catch (e: Exception) {
                 if (e !is InterruptedException) {
                    AppLogger.log("WorkerError", "Error: ${e.toString()}")
                }
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
        if (callback == null) return alreadyStarted

        var didStart = alreadyStarted
        val uuid = UUID.randomUUID().toString()
        val tempFile = File.createTempFile("tts_", ".wav", cacheDir)
        val latch = CountDownLatch(1)
        
        try {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { 
                    if (utteranceId == uuid) latch.countDown() 
                }
                override fun onError(utteranceId: String?) { 
                    if (utteranceId == uuid) latch.countDown() 
                }
            })

            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)
            
            val result = engine.synthesizeToFile(text, params, tempFile, uuid)
            
            if (result == TextToSpeech.SUCCESS) {
                // Latency Fix: Wait max 2.5s per chunk (Faster UI response)
                latch.await(2500, TimeUnit.MILLISECONDS)
                
                if (tempFile.exists() && tempFile.length() > 44) {
                    val fis = FileInputStream(tempFile)
                    val header = ByteArray(44)
                    val headerRead = fis.read(header)
                    
                    if (headerRead == 44) {
                        val wavInfo = getWavInfo(header)
                        
                        // *** ALWAYS START WITH 24000Hz (High Quality) ***
                        if (!didStart) {
                            callback.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                            didStart = true
                        }
                        
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                             if (isStopped.get() || Thread.currentThread().isInterrupted) break
                             
                             // *** MAGIC HAPPENS HERE ***
                             // 16kHz (eSpeak) -> 24kHz (Resample UP)
                             // 24kHz (Google) -> 24kHz (Pass Through)
                             val outputBytes = if (wavInfo.sampleRate != TARGET_SAMPLE_RATE) {
                                 AudioResampler.resampleChunk(buffer, bytesRead, wavInfo.sampleRate, TARGET_SAMPLE_RATE)
                             } else {
                                 buffer.copyOfRange(0, bytesRead)
                             }
                             
                             if (outputBytes.isNotEmpty()) {
                                 callback.audioAvailable(outputBytes, 0, outputBytes.size)
                             }
                        }
                    } 
                    fis.close()
                }
            } 
        } catch (e: Exception) {
            if (e !is InterruptedException) {
                // AppLogger.log("FileError", "Ex: ${e.toString()}")
            }
        } finally {
            try { tempFile.delete() } catch (e: Exception) {}
        }
        return didStart
    }
    
    private fun getWavInfo(header: ByteArray): WavInfo {
        if (header.size < 44) return WavInfo(16000, 1)
        val rate = (header[24].toInt() and 0xFF) or ((header[25].toInt() and 0xFF) shl 8) or ((header[26].toInt() and 0xFF) shl 16) or ((header[27].toInt() and 0xFF) shl 24)
        // Safety check to prevent divide by zero in resampler
        val safeRate = if (rate > 0) rate else 16000
        return WavInfo(safeRate, 1)
    }
    
    private fun getEngine(lang: String): TextToSpeech? {
        return when (lang) {
            "SHAN" -> if (shanEngine != null) shanEngine else englishEngine
            "MYANMAR" -> if (burmeseEngine != null) burmeseEngine else englishEngine
            else -> englishEngine
        }
    }

    override fun onStop() { isStopped.set(true); currentTask?.cancel(true) }
    override fun onDestroy() { isStopped.set(true); executor.shutdownNow(); shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown(); if (wakeLock?.isHeld == true) wakeLock?.release(); super.onDestroy() }
    override fun onGetVoices(): List<Voice> { return listOf() }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onGetLanguage(): Array<String> { return arrayOf("eng", "USA", "") }
}

