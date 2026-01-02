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
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkg = ""
    private var burmesePkg = ""
    private var englishPkg = ""

    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "TTS_CONFIG"
    private val TARGET_HZ = 24000

    // Coroutine Scope Setup
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private var synthesisJob: Job? = null

    // Shared Processor
    @Volatile private var sharedProcessor: AudioProcessor? = null
    @Volatile private var currentProcessorHz = 0

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service onCreate (Coroutines + Logs) ===")
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defEngine = getDefaultEngineFallback()

            shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defEngine)
            initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it }

            burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defEngine)
            initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }

            englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defEngine)
            initTTS(englishPkg, Locale.US) { englishEngine = it }
            
            AppLogger.log("Engines Ready: Shan=$shanPkg, Bur=$burmesePkg, Eng=$englishPkg")
        } catch (e: Exception) { AppLogger.error("onCreate Failed", e) }
    }

    override fun onStop() {
        AppLogger.log("=== onStop Called ===")
        
        if (synthesisJob?.isActive == true) {
            AppLogger.log("Cancelling active job...")
            synthesisJob?.cancel()
        }
        synthesisJob = null

        // Safe cleanup
        sharedProcessor?.flushQueue()
        // Note: We keep the processor instance alive for performance, 
        // it will be fully released in onDestroy.

        stopSafe(shanEngine, "Shan")
        stopSafe(burmeseEngine, "Burmese")
        stopSafe(englishEngine, "English")
    }

    private fun stopSafe(engine: TextToSpeech?, name: String) {
        try { engine?.stop() } catch (e: Exception) {
            AppLogger.error("Error stopping $name", e)
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            AppLogger.error("Synthesize Request NULL")
            return
        }
        val reqId = UUID.randomUUID().toString().substring(0, 5)

        // Cancel previous work
        synthesisJob?.cancel()

        val text = request.charSequenceText.toString()
        val chunks = TTSUtils.splitHelper(text)
        if (chunks.isEmpty()) {
            AppLogger.log("[$reqId] No chunks to read.")
            callback.done()
            return
        }

        // Speed Logic
        val rawRate = request.speechRate / 100.0f
        val finalSpeed = rawRate.coerceIn(0.1f, 6.0f)
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.5f, 2.0f)

        AppLogger.log("[$reqId] Start. Chunks=${chunks.size}, Speed=$finalSpeed")

        // Blocking call for Android System
        runBlocking {
            synthesisJob = launch(Dispatchers.IO) {
                try {
                    ensureActive()
                    
                    AppLogger.log("[$reqId] Starting AudioTrack @ ${TARGET_HZ}Hz")
                    callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

                    val inputBuffer = ByteBuffer.allocateDirect(8192).order(ByteOrder.LITTLE_ENDIAN)
                    val outputBuffer = ByteBuffer.allocateDirect(32768).order(ByteOrder.LITTLE_ENDIAN)
                    val chunkReadBuffer = ByteArray(8192)
                    val chunkWriteBuffer = ByteArray(32768)

                    for ((index, chunk) in chunks.withIndex()) {
                        if (!isActive) {
                            AppLogger.log("[$reqId] Job cancelled at chunk $index")
                            break 
                        }
                        if (chunk.text.isBlank()) continue

                        val engine = when (chunk.lang) {
                            "SHAN" -> shanEngine
                            "MYANMAR" -> burmeseEngine
                            else -> englishEngine
                        } ?: englishEngine

                        if (engine == null) {
                            AppLogger.error("[$reqId] Engine NULL for ${chunk.lang}")
                            continue
                        }

                        val currentPkg = when (chunk.lang) {
                            "SHAN" -> shanPkg
                            "MYANMAR" -> burmesePkg
                            else -> englishPkg
                        }
                        
                        var engineHz = prefs.getInt("RATE_$currentPkg", 0)
                        if (engineHz <= 0) engineHz = 22050 

                        // Processor Re-Use Logic
                        var processor = sharedProcessor
                        if (processor == null || currentProcessorHz != engineHz) {
                            processor?.release()
                            AppLogger.log("[$reqId] Init Sonic: $engineHz Hz")
                            processor = AudioProcessor(engineHz, 1)
                            processor.init()
                            sharedProcessor = processor
                            currentProcessorHz = engineHz
                        }
                        
                        processor.setSpeed(finalSpeed)
                        processor.setPitch(finalPitch)

                        val pipe = ParcelFileDescriptor.createPipe()
                        val readFd = pipe[0]
                        val writeFd = pipe[1]
                        val uuid = UUID.randomUUID().toString()

                        engine.setSpeechRate(1.0f) 
                        engine.setPitch(1.0f)

                        // Launch Writer Coroutine
                        val writerJob = launch(Dispatchers.IO) {
                            // Using full package name to avoid import error
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                            
                            val params = Bundle()
                            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                            try {
                                // AppLogger.log("[$reqId-W] Writing chunk $index...")
                                val res = engine.synthesizeToFile(chunk.text, params, writeFd, uuid)
                                if (res != TextToSpeech.SUCCESS) {
                                    AppLogger.error("[$reqId-W] Engine returned error: $res")
                                }
                            } catch (e: Exception) {
                                AppLogger.error("[$reqId-W] Writer Exception", e)
                            } finally {
                                try { writeFd.close() } catch (e: Exception) {}
                            }
                        }

                        // Reader Loop
                        try {
                            ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                                var skipped: Long = 0
                                while (skipped < 44 && isActive) {
                                    val s = fis.skip(44 - skipped)
                                    if (s <= 0) break
                                    skipped += s
                                }

                                while (isActive) {
                                    val bytesRead = try { fis.read(chunkReadBuffer) } catch (e: IOException) { -1 }
                                    if (bytesRead == -1) break
                                    
                                    if (bytesRead > 0) {
                                        var safeBytes = bytesRead
                                        if (safeBytes % 2 != 0) safeBytes-- // Byte alignment fix

                                        if (safeBytes > 0) {
                                            inputBuffer.clear()
                                            inputBuffer.put(chunkReadBuffer, 0, safeBytes)
                                            outputBuffer.clear()
                                            
                                            val processedBytes = processor.process(
                                                inputBuffer, 
                                                safeBytes, 
                                                outputBuffer, 
                                                outputBuffer.capacity()
                                            )
                                            
                                            if (processedBytes > 0) {
                                                outputBuffer.get(chunkWriteBuffer, 0, processedBytes)
                                                val max = callback.maxBufferSize
                                                var offset = 0
                                                while (offset < processedBytes) {
                                                    if (!isActive) break 
                                                    
                                                    val len = min(processedBytes - offset, max)
                                                    val ret = callback.audioAvailable(chunkWriteBuffer, offset, len)
                                                    if (ret == TextToSpeech.ERROR) {
                                                        AppLogger.error("[$reqId] AudioTrack Error! Cancelling.")
                                                        this@launch.cancel()
                                                        break
                                                    }
                                                    offset += len
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Drain Loop
                            if (isActive) {
                                // AppLogger.log("[$reqId] Draining chunk $index...")
                                processor.flushQueue()
                                var flushedBytes: Int
                                do {
                                    if (!isActive) break
                                    outputBuffer.clear()
                                    flushedBytes = processor.process(inputBuffer, 0, outputBuffer, outputBuffer.capacity())
                                    
                                    if (flushedBytes > 0) {
                                        outputBuffer.get(chunkWriteBuffer, 0, flushedBytes)
                                        val max = callback.maxBufferSize
                                        var offset = 0
                                        while (offset < flushedBytes) {
                                            if (!isActive) break
                                            val len = min(flushedBytes - offset, max)
                                            callback.audioAvailable(chunkWriteBuffer, offset, len)
                                            offset += len
                                        }
                                    }
                                } while (flushedBytes > 0)
                            }

                        } catch (e: Exception) {
                            AppLogger.error("[$reqId] Loop Exception", e)
                        } finally {
                            if (!isActive) {
                                // Wait briefly for writer to clean up
                                try { writerJob.join() } catch (e: Exception) {}
                            }
                        }
                    }
                    
                    if (isActive) {
                        callback.done()
                        AppLogger.log("[$reqId] Request Finished Successfully.")
                    } else {
                        AppLogger.log("[$reqId] Request Cancelled.")
                    }

                } catch (e: Exception) {
                    AppLogger.error("[$reqId] Critical Coroutine Error", e)
                }
            }
            // Wait for completion
            synthesisJob?.join()
        }
    }
    
    // Helpers
    private fun getDefaultEngineFallback(): String {
        return try {
            val tts = TextToSpeech(this, null)
            val p = tts.defaultEngine; tts.shutdown(); p ?: "com.google.android.tts"
        } catch (e: Exception) { "com.google.android.tts" }
    }
    private fun getPkg(key: String, list: List<String>, def: String): String {
        val p = prefs.getString(key, "")
        if (!p.isNullOrEmpty() && isInstalled(p)) return p
        for (i in list) if (isInstalled(i)) return i
        return def
    }
    private fun isInstalled(pkg: String): Boolean {
        return try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
    }
    private fun initTTS(pkg: String, loc: Locale, onReady: (TextToSpeech) -> Unit) {
        if (pkg.isEmpty()) return
        try {
            var t: TextToSpeech? = null
            t = TextToSpeech(this, { if (it == TextToSpeech.SUCCESS) onReady(t!!) }, pkg)
        } catch (e: Exception) { AppLogger.error("InitTTS Error $pkg", e) }
    }
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    
    override fun onDestroy() {
        super.onDestroy()
        AppLogger.log("=== Service onDestroy ===")
        serviceJob.cancel()
        sharedProcessor?.release()
        sharedProcessor = null
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
    }
}

