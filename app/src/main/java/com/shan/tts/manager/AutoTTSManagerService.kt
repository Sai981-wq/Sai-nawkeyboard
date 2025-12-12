package com.shan.tts.manager

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null
    
    private val executor = Executors.newSingleThreadExecutor()
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service", "Service Created")
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherryTTS:WakeLock")
        
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
        AppLogger.log("Init", "Initializing $name with package: $pkg")
        if (pkg.isNullOrEmpty()) return
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    AppLogger.log("Init", "$name initialized SUCCESS")
                    onSuccess(tempTTS!!)
                    tempTTS?.setOnUtteranceProgressListener(object : UtteranceProgressListener(){
                        override fun onStart(id: String?) { AppLogger.log("EngineCallback", "$name onStart") }
                        override fun onDone(id: String?) { AppLogger.log("EngineCallback", "$name onDone") }
                        override fun onError(id: String?) { AppLogger.log("EngineCallback", "$name onError") }
                    })
                } else {
                    AppLogger.log("Init", "$name initialized FAILED code: $status")
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.log("InitError", "Crash on $name: ${e.message}")
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        AppLogger.log("Synth", "Request received. Text length: ${text.length}")

        isStopped.set(true)
        currentTask?.cancel(true)
        isStopped.set(false)
        
        // Acquire WakeLock
        if (wakeLock?.isHeld == false) wakeLock?.acquire(60000)

        currentTask = executor.submit {
            try {
                AppLogger.log("Worker", "Processing started...")
                val chunks = splitByLanguage(text) // Assume this helper exists from previous code
                var hasStartedCallback = false

                for (chunk in chunks) {
                    if (isStopped.get()) break
                    AppLogger.log("Worker", "Processing chunk: [${chunk.lang}] ${chunk.text}")

                    val engine = getEngine(chunk.lang)
                    if (engine == null) {
                        AppLogger.log("Worker", "Engine is NULL for ${chunk.lang}")
                        continue
                    }

                    processStream(engine, chunk.text, callback, hasStartedCallback).also {
                        if (it) hasStartedCallback = true
                    }
                }
                AppLogger.log("Worker", "All chunks finished.")
            } catch (e: Exception) {
                AppLogger.log("WorkerError", "Exception: ${e.message}")
                e.printStackTrace()
            } finally {
                if (!isStopped.get()) callback?.done()
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }

    private fun processStream(engine: TextToSpeech, text: String, callback: SynthesisCallback?, alreadyStarted: Boolean): Boolean {
        var didStart = false
        val uuid = UUID.randomUUID().toString()
        var readSide: ParcelFileDescriptor? = null
        var writeSide: ParcelFileDescriptor? = null

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            readSide = pipe[0]
            writeSide = pipe[1]

            val params = Bundle()
            // Set params if needed...

            AppLogger.log("Pipe", "Calling synthesizeToFile...")
            val result = engine.synthesizeToFile(text, params, writeSide, uuid)
            
            // Important: Close write side immediately
            writeSide.close()
            writeSide = null 

            if (result != TextToSpeech.SUCCESS) {
                AppLogger.log("Pipe", "Engine returned ERROR code: $result")
                return false
            }

            val inputStream = FileInputStream(readSide.fileDescriptor)
            val buffer = ByteArray(4096)
            var totalRead = 0
            
            // Header Reading
            var headerBytes = 0
            while (headerBytes < 44) {
                 val c = inputStream.read(buffer, headerBytes, 44 - headerBytes)
                 if (c == -1) break
                 headerBytes += c
            }
            AppLogger.log("Pipe", "Header read bytes: $headerBytes")

            if (headerBytes > 0) {
                 if (!alreadyStarted) {
                     // Check Sample Rate
                     // val rate = ... (Use your helper)
                     callback?.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
                     didStart = true
                     AppLogger.log("Callback", "start() called with 24000")
                 }
                 
                 // Pass header first if valid pcm? usually header is skipped, but let's pass data
                 // Actually for raw PCM we might skip 44 bytes. 
                 // For now, let's just loop the rest.
                 
                 while (true) {
                     if (isStopped.get()) break
                     val count = inputStream.read(buffer)
                     if (count == -1) break
                     
                     // Resampling logic here if needed...
                     // For debugging, pass direct
                     if (count > 0) {
                         callback?.audioAvailable(buffer, 0, count)
                         totalRead += count
                     }
                 }
            }
            AppLogger.log("Pipe", "Stream finished. Total data bytes read: $totalRead")

        } catch (e: Exception) {
            AppLogger.log("PipeError", "Stream Failed: ${e.message}")
        } finally {
            try { readSide?.close() } catch (e: Exception) {}
            try { writeSide?.close() } catch (e: Exception) {}
        }
        return didStart
    }
    
    // --- Helpers (Copy from previous code) ---
    private fun getEngine(lang: String): TextToSpeech? {
        // Your logic
        return englishEngine // Placeholder
    }
    
    private fun splitByLanguage(text: String): List<LangChunk> {
        // Your logic
        return listOf(LangChunk(text, "ENGLISH")) // Placeholder
    }
    
    data class LangChunk(val text: String, val lang: String)

    override fun onStop() {
        AppLogger.log("Service", "onStop called")
        isStopped.set(true)
        super.onStop()
    }
    
    // Talkback Required Overrides...
    override fun onGetVoices(): List<Voice> { return listOf() }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onGetLanguage(): Array<String> { return arrayOf("eng", "USA", "") }
}

