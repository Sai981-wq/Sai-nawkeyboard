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
    
    // CPU သက်သာအောင် Thread 4 ခုပဲ ပေးသုံးမယ်
    private val executor = Executors.newFixedThreadPool(4)
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val TARGET_SAMPLE_RATE = 24000 
    
    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service", "Cherry TTS Service Created (Debug Mode)")
        
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
        if (pkg.isNullOrEmpty()) {
            AppLogger.log("Init", "$name skipped (No Package)")
            return
        }
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
                } else {
                    AppLogger.log("Init", "$name Failed to Init")
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.log("InitError", "Crash on $name: ${e.message}")
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val reqId = System.currentTimeMillis() % 1000 // Short ID for logging
        
        AppLogger.log("Synth", "[$reqId] Request received. Length: ${text.length}")

        isStopped.set(true)
        currentTask?.cancel(true)
        isStopped.set(false)
        
        if (wakeLock?.isHeld == false) wakeLock?.acquire(60000)

        currentTask = executor.submit {
            try {
                val chunks = LanguageUtils.splitHelper(text) 
                AppLogger.log("Split", "[$reqId] Text split into ${chunks.size} chunks")

                if (chunks.isEmpty()) {
                    AppLogger.log("Synth", "[$reqId] Empty chunks, sending silence")
                    callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callback?.done()
                    return@submit
                }

                var hasStartedCallback = false

                for ((index, chunk) in chunks.withIndex()) {
                    if (isStopped.get() || Thread.currentThread().isInterrupted) {
                        AppLogger.log("Synth", "[$reqId] Process interrupted/stopped")
                        break
                    }
                    
                    val engine = getEngine(chunk.lang)
                    if (engine == null) {
                        AppLogger.log("Engine", "[$reqId] Engine for ${chunk.lang} is NULL")
                        continue
                    }

                    val params = applyRateAndPitch(engine, chunk.lang, request)
                    
                    AppLogger.log("Process", "[$reqId] Chunk $index [${chunk.lang}]: '${chunk.text.take(10)}...'")
                    
                    val success = processWithPipe(engine, chunk.text, params, callback, hasStartedCallback, reqId, index)
                    
                    if (success) hasStartedCallback = true
                }
                
                if (!hasStartedCallback) {
                     AppLogger.log("Synth", "[$reqId] Fallback Start (No audio produced)")
                     callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

            } catch (e: Exception) {
                 if (e !is InterruptedException) {
                    AppLogger.log("WorkerError", "[$reqId] CRITICAL: ${e.stackTraceToString()}")
                }
            } finally {
                if (!isStopped.get()) {
                    AppLogger.log("Synth", "[$reqId] Done called")
                    callback?.done()
                }
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }
    
    // ... applyRateAndPitch function remains the same ...
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


    private fun processWithPipe(engine: TextToSpeech, text: String, params: Bundle, callback: SynthesisCallback?, alreadyStarted: Boolean, reqId: Long, chunkIdx: Int): Boolean {
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

            // READER TASK
            val readerFuture = executor.submit {
                try {
                    val fis = FileInputStream(readFd.fileDescriptor)
                    val header = ByteArray(44)
                    var totalRead = 0
                    
                    // Wait for header
                    while (totalRead < 44) {
                         val c = fis.read(header, totalRead, 44 - totalRead)
                         if (c == -1) break
                         totalRead += c
                    }

                    if (totalRead == 44) {
                        val wavInfo = getWavInfo(header)
                        AppLogger.log("Audio", "[$reqId-$chunkIdx] Header: ${wavInfo.sampleRate}Hz")
                        
                        if (!didStart) {
                            synchronized(callback) {
                                callback.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                            }
                            didStart = true
                        }
                        
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        var totalBytesProcessed = 0
                        
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                             if (isStopped.get()) break
                             
                             val outputBytes = if (wavInfo.sampleRate != TARGET_SAMPLE_RATE) {
                                 AudioResampler.resampleChunk(buffer, bytesRead, wavInfo.sampleRate, TARGET_SAMPLE_RATE)
                             } else {
                                 buffer.copyOfRange(0, bytesRead)
                             }
                             
                             if (outputBytes.isNotEmpty()) {
                                 synchronized(callback) {
                                     callback.audioAvailable(outputBytes, 0, outputBytes.size)
                                 }
                                 totalBytesProcessed += outputBytes.size
                             }
                        }
                        AppLogger.log("Audio", "[$reqId-$chunkIdx] Processed ${totalBytesProcessed} bytes")
                    } else {
                        AppLogger.log("PipeError", "[$reqId-$chunkIdx] Incomplete Header: $totalRead bytes")
                    }
                    fis.close()
                } catch (e: Exception) {
                    AppLogger.log("PipeReadErr", "[$reqId-$chunkIdx] ${e.message}")
                }
            }

            // WRITER TASK (Main Thread)
            AppLogger.log("Writer", "[$reqId-$chunkIdx] Start SynthesizeToFile")
            val result = engine.synthesizeToFile(text, params, writeFd, uuid)
            
            if (result != TextToSpeech.SUCCESS) {
                AppLogger.log("Writer", "[$reqId-$chunkIdx] Engine Error: $result")
            }

            // Close write end to signal EOF
            writeFd.close()
            writeFd = null 
            AppLogger.log("Writer", "[$reqId-$chunkIdx] Write Closed, Waiting for Reader...")

            // Wait for Reader
            readerFuture.get() 
            AppLogger.log("Pipe", "[$reqId-$chunkIdx] Finished")

        } catch (e: Exception) {
            AppLogger.log("PipeMainErr", "[$reqId-$chunkIdx] ${e.message}")
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

    override fun onStop() { 
        AppLogger.log("Service", "onStop Called")
        isStopped.set(true)
        currentTask?.cancel(true)
    }
    
    override fun onDestroy() { 
        AppLogger.log("Service", "onDestroy Called")
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

