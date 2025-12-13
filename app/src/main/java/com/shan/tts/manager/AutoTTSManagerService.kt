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

// Data class
data class LangChunk(val text: String, val lang: String)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null
    
    private val executor = Executors.newSingleThreadExecutor()
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val TARGET_SAMPLE_RATE = 24000

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service", "Cherry SME TTS Service Created (File Mode)")
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherryTTS:WakeLock")
        wakeLock?.setReferenceCounted(false)
        
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        // Initialize Engines
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
                    // Listener is essential for File Mode
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
                val chunks = LanguageUtils.splitHelper(text) 
                
                // *** FIX 1: Handle Empty Request Immediately ***
                if (chunks.isEmpty()) {
                    AppLogger.log("Split", "0 chunks - Sending silent success")
                    callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callback?.done()
                    return@submit
                }

                AppLogger.log("Split", "Text split into ${chunks.size} chunks")
                var hasStartedCallback = false

                for ((index, chunk) in chunks.withIndex()) {
                    if (isStopped.get()) break
                    
                    val engine = getEngine(chunk.lang) ?: continue
                    
                    // *** FIX 2: Enhanced Pitch/Rate Setting ***
                    val params = applyRateAndPitch(engine, chunk.lang, request)

                    // *** FIX 3: Use File Mode instead of Pipe for Stability ***
                    val success = processWithFile(engine, chunk.text, params, callback, hasStartedCallback)
                    
                    if (success) hasStartedCallback = true
                }
                
                if (!hasStartedCallback) {
                     callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

            } catch (e: Exception) {
                AppLogger.log("WorkerError", "Exception: ${e.message}")
                e.printStackTrace()
            } finally {
                if (!isStopped.get()) callback?.done()
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }
    
    // Returns Bundle to use in synthesizeToFile
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

        // *** FIX: Also put in Bundle for strict engines ***
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) 
        // Note: Standard API uses setPitch, but some engines might check bundle extras
        return params
    }

    private fun processWithFile(engine: TextToSpeech, text: String, params: Bundle, callback: SynthesisCallback?, alreadyStarted: Boolean): Boolean {
        var didStart = alreadyStarted
        val uuid = UUID.randomUUID().toString()
        val tempFile = File.createTempFile("tts_", ".wav", cacheDir)
        val latch = CountDownLatch(1)
        var success = false

        try {
            // Set listener specifically for this synthesis
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
            
            // Synthesize to FILE (More stable for long text)
            val result = engine.synthesizeToFile(text, params, tempFile.absolutePath)
            
            if (result == TextToSpeech.SUCCESS) {
                // Wait for file to be written (Max 4 seconds per chunk to prevent hang)
                latch.await(4, TimeUnit.SECONDS)
                
                if (tempFile.exists() && tempFile.length() > 44) {
                    val fis = FileInputStream(tempFile)
                    val header = ByteArray(44)
                    fis.read(header)
                    
                    val sourceRate = getSampleRateFromWav(header)
                    
                    if (!didStart) {
                        callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                        didStart = true
                    }
                    
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                         if (isStopped.get()) break
                         
                         val outputBytes = if (sourceRate != TARGET_SAMPLE_RATE) {
                             AudioResampler.resampleChunk(buffer, bytesRead, sourceRate, TARGET_SAMPLE_RATE)
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
            tempFile.delete() // Cleanup
        }
        return didStart
    }
    
    private fun getSampleRateFromWav(header: ByteArray): Int {
        if (header.size < 28) return 16000 
        val rate = (header[24].toInt() and 0xFF) or
               ((header[25].toInt() and 0xFF) shl 8) or
               ((header[26].toInt() and 0xFF) shl 16) or
               ((header[27].toInt() and 0xFF) shl 24)
        return if (rate > 0) rate else 16000 
    }

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

