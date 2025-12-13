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

data class LangChunk(val text: String, val lang: String)
data class WavInfo(val sampleRate: Int, val channels: Int)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null
    
    // Executors
    private val controllerExecutor = Executors.newSingleThreadExecutor()
    private val pipeExecutor = Executors.newCachedThreadPool()
    
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Target Rate
    private val TARGET_SAMPLE_RATE = 24000 
    
    override fun onCreate() {
        super.onCreate()
        AppLogger.log("System", "Service Created: Diagnostic Mode ON")
        
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
        val reqId = System.currentTimeMillis() % 10000 // Short ID for tracking
        val startTime = System.currentTimeMillis()
        
        AppLogger.log("Flow", "[$reqId] === NEW REQUEST START ===")
        AppLogger.log("Flow", "[$reqId] Text Len: ${text.length} chars")

        isStopped.set(true)
        currentTask?.cancel(true)
        isStopped.set(false)
        
        if (wakeLock?.isHeld == false) wakeLock?.acquire(60000)

        currentTask = controllerExecutor.submit {
            try {
                val chunks = LanguageUtils.splitHelper(text) 
                AppLogger.log("Split", "[$reqId] Split into ${chunks.size} chunks")
                
                if (chunks.isEmpty()) {
                    callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callback?.done()
                    AppLogger.log("Flow", "[$reqId] Empty text, finished.")
                    return@submit
                }

                var hasStartedCallback = false

                for ((index, chunk) in chunks.withIndex()) {
                    if (isStopped.get()) {
                        AppLogger.log("Flow", "[$reqId] STOPPED by User Action")
                        break
                    }
                    
                    val engine = getEngine(chunk.lang)
                    if (engine == null) {
                        AppLogger.log("Error", "[$reqId] Engine NULL for ${chunk.lang}")
                        continue
                    }
                    
                    AppLogger.log("Chunk", "[$reqId-$index] Processing (${chunk.lang}): '${chunk.text.take(10)}...'")
                    
                    val params = applyRateAndPitch(engine, chunk.lang, request)
                    val success = processWithPipe(engine, chunk.text, params, callback, hasStartedCallback, reqId, index, startTime)
                    
                    if (success) hasStartedCallback = true
                }
                
                if (!hasStartedCallback) {
                     AppLogger.log("Flow", "[$reqId] Fallback Start (Silent)")
                     callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

            } catch (e: Exception) {
                if (e !is InterruptedException) {
                    AppLogger.log("Critical", "[$reqId] Worker Crashed: ${e.message}")
                }
            } finally {
                if (!isStopped.get()) {
                    callback?.done()
                    val totalTime = System.currentTimeMillis() - startTime
                    AppLogger.log("Flow", "[$reqId] === DONE (Total: ${totalTime}ms) ===")
                }
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

    private fun processWithPipe(engine: TextToSpeech, text: String, params: Bundle, callback: SynthesisCallback?, alreadyStarted: Boolean, reqId: Long, chunkIdx: Int, startTime: Long): Boolean {
        if (callback == null) return alreadyStarted

        var didStart = alreadyStarted
        val uuid = UUID.randomUUID().toString()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

        var readFd: ParcelFileDescriptor? = null
        var writeFd: ParcelFileDescriptor? = null

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            readFd = pipe[0]
            writeFd = pipe[1]
            
            AppLogger.log("Pipe", "[$reqId-$chunkIdx] Pipe Created")

            val readerFuture = pipeExecutor.submit {
                try {
                    val fis = FileInputStream(readFd.fileDescriptor)
                    val header = ByteArray(44)
                    var totalRead = 0
                    
                    // Wait Header
                    while (totalRead < 44) {
                         val c = fis.read(header, totalRead, 44 - totalRead)
                         if (c == -1) break
                         totalRead += c
                    }

                    if (totalRead == 44) {
                        val wavInfo = getWavInfo(header)
                        val engineRate = wavInfo.sampleRate
                        
                        // *** CRITICAL LOG: Hz Check ***
                        if (engineRate != TARGET_SAMPLE_RATE) {
                            AppLogger.log("Hz", "[$reqId-$chunkIdx] MISMATCH! Engine: ${engineRate}Hz -> Target: ${TARGET_SAMPLE_RATE}Hz (Resampling...)")
                        } else {
                            AppLogger.log("Hz", "[$reqId-$chunkIdx] MATCH! Engine: ${engineRate}Hz (Direct)")
                        }

                        // *** CRITICAL LOG: Latency Check ***
                        if (!didStart) {
                            val latency = System.currentTimeMillis() - startTime
                            AppLogger.log("Latency", "[$reqId] First Audio in ${latency}ms")
                            
                            synchronized(callback) {
                                callback.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                            }
                            didStart = true
                        }
                        
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        var totalBytes = 0
                        
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                             if (isStopped.get()) break
                             
                             val outputBytes = if (engineRate != TARGET_SAMPLE_RATE) {
                                 AudioResampler.resampleChunk(buffer, bytesRead, engineRate, TARGET_SAMPLE_RATE)
                             } else {
                                 buffer.copyOfRange(0, bytesRead)
                             }
                             
                             if (outputBytes.isNotEmpty()) {
                                 synchronized(callback) {
                                     callback.audioAvailable(outputBytes, 0, outputBytes.size)
                                 }
                                 totalBytes += outputBytes.size
                             }
                        }
                        AppLogger.log("Pipe", "[$reqId-$chunkIdx] Reader Finished. Total Output: $totalBytes bytes")
                    } else {
                        AppLogger.log("Error", "[$reqId-$chunkIdx] Incomplete Header. Read: $totalRead")
                    }
                    fis.close()
                } catch (e: Exception) {
                    if (!isStopped.get()) AppLogger.log("ReaderErr", "[$reqId-$chunkIdx] ${e.message}")
                }
            }

            // Writer
            AppLogger.log("Pipe", "[$reqId-$chunkIdx] Engine Writing...")
            engine.synthesizeToFile(text, params, writeFd, uuid)
            
            // Close Writer
            try { writeFd?.close(); writeFd = null } catch(e:Exception){}
            AppLogger.log("Pipe", "[$reqId-$chunkIdx] Writer Closed")

            // Wait Reader
            readerFuture.get() 

        } catch (e: Exception) {
             if (!isStopped.get()) {
                 AppLogger.log("MainErr", "[$reqId-$chunkIdx] ${e.message}")
             }
        } finally {
            try { readFd?.close() } catch (e: Exception) {}
            try { writeFd?.close() } catch (e: Exception) {}
        }
        return didStart
    }
    
    private fun getWavInfo(header: ByteArray): WavInfo {
        if (header.size < 44) return WavInfo(16000, 1)
        val rate = (header[24].toInt() and 0xFF) or ((header[25].toInt() and 0xFF) shl 8) or ((header[26].toInt() and 0xFF) shl 16) or ((header[27].toInt() and 0xFF) shl 24)
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
    
    // ... Stop, Destroy methods same as before ...
    override fun onStop() { isStopped.set(true); currentTask?.cancel(true) }
    override fun onDestroy() { isStopped.set(true); controllerExecutor.shutdownNow(); pipeExecutor.shutdownNow(); super.onDestroy() }
    override fun onGetVoices(): List<Voice> { return listOf() }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onGetLanguage(): Array<String> { return arrayOf("eng", "USA", "") }
}

