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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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

    private val isStopped = AtomicBoolean(false)
    
    @Volatile private var sharedProcessor: AudioProcessor? = null
    @Volatile private var currentProcessorHz = 0
    @Volatile private var currentWriterThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service onCreate (Buffer Fix) ===")
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defEngine = getDefaultEngineFallback()

            shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defEngine)
            initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it }

            burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defEngine)
            initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }

            englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defEngine)
            initTTS(englishPkg, Locale.US) { englishEngine = it }
        } catch (e: Exception) { AppLogger.error("onCreate Error", e) }
    }

    override fun onStop() {
        isStopped.set(true)
        AppLogger.log("=== onStop Called ===")
        currentWriterThread?.interrupt()
        sharedProcessor?.flushQueue()
        stopSafe(shanEngine)
        stopSafe(burmeseEngine)
        stopSafe(englishEngine)
    }

    private fun stopSafe(engine: TextToSpeech?) {
        try { engine?.stop() } catch (e: Exception) {}
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        val reqId = UUID.randomUUID().toString().substring(0, 5)
        
        isStopped.set(false)
        val text = request.charSequenceText.toString()
        val chunks = TTSUtils.splitHelper(text)
        if (chunks.isEmpty()) {
            callback.done()
            return
        }

        val rawRate = request.speechRate / 100.0f
        val finalSpeed = rawRate.coerceIn(0.1f, 6.0f)
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.5f, 2.0f)

        AppLogger.log("[$reqId] Start. Chunks=${chunks.size}")

        try {
            callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

            val inputBuffer = ByteBuffer.allocateDirect(16384).order(ByteOrder.LITTLE_ENDIAN)
            val outputBuffer = ByteBuffer.allocateDirect(32768).order(ByteOrder.LITTLE_ENDIAN)
            val chunkReadBuffer = ByteArray(16384)
            val chunkWriteBuffer = ByteArray(32768)
            val headerDiscardBuffer = ByteArray(44)

            for ((index, chunk) in chunks.withIndex()) {
                if (isStopped.get()) break
                if (chunk.text.isBlank()) continue

                val engine = when (chunk.lang) {
                    "SHAN" -> shanEngine
                    "MYANMAR" -> burmeseEngine
                    else -> englishEngine
                } ?: englishEngine

                if (engine == null) continue

                val currentPkg = when (chunk.lang) {
                    "SHAN" -> shanPkg
                    "MYANMAR" -> burmesePkg
                    else -> englishPkg
                }
                
                var engineHz = prefs.getInt("RATE_$currentPkg", 0)
                if (engineHz <= 0) engineHz = 22050 

                var processor = sharedProcessor
                if (processor == null || currentProcessorHz != engineHz) {
                    processor?.release()
                    AppLogger.log("[$reqId] Switching Processor: $engineHz Hz")
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

                val writerThread = Thread {
                    currentWriterThread = Thread.currentThread()
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    try {
                        if (!Thread.interrupted() && !isStopped.get()) {
                            engine.synthesizeToFile(chunk.text, params, writeFd, uuid)
                        }
                    } catch (e: Exception) {
                        AppLogger.error("[$reqId] Writer Error", e)
                    } finally {
                        try { writeFd.close() } catch (e: Exception) {}
                    }
                }
                writerThread.start()

                try {
                    ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                        // Header Skip Logic
                        var headerReadTotal = 0
                        while (headerReadTotal < 44 && !isStopped.get()) {
                            val count = fis.read(headerDiscardBuffer, headerReadTotal, 44 - headerReadTotal)
                            if (count < 0) break 
                            headerReadTotal += count
                        }

                        while (!isStopped.get()) {
                            val bytesRead = try { fis.read(chunkReadBuffer) } catch (e: IOException) { -1 }
                            if (bytesRead == -1) break
                            
                            if (bytesRead > 0) {
                                // ★ SAFE BUFFER LOADING FIX ★
                                // Ensure we don't overflow the input buffer
                                inputBuffer.clear()
                                
                                // Make sure 'bytesRead' fits into inputBuffer capacity
                                val safeLen = min(bytesRead, inputBuffer.capacity())
                                inputBuffer.put(chunkReadBuffer, 0, safeLen)
                                
                                // Limit buffer to actual data read
                                // This is crucial for 'process' to know how much to read
                                inputBuffer.flip() 
                                // Note: AudioProcessor.process expects limit to be set correctly, 
                                // but our AudioProcessor implementation takes 'len' argument.
                                // Let's ensure AudioProcessor respects 'len'.
                                
                                outputBuffer.clear()
                                
                                // Pass 'safeLen' instead of raw 'bytesRead'
                                val processedBytes = processor.process(
                                    inputBuffer, 
                                    safeLen, 
                                    outputBuffer, 
                                    outputBuffer.capacity()
                                )
                                
                                if (processedBytes > 0) {
                                    outputBuffer.get(chunkWriteBuffer, 0, processedBytes)
                                    val max = callback.maxBufferSize
                                    var offset = 0
                                    while (offset < processedBytes) {
                                        if (isStopped.get()) break
                                        val len = min(processedBytes - offset, max)
                                        
                                        val ret = callback.audioAvailable(chunkWriteBuffer, offset, len)
                                        if (ret == TextToSpeech.ERROR) {
                                            isStopped.set(true)
                                            break
                                        }
                                        offset += len
                                    }
                                }
                            }
                        }
                    }

                    if (!isStopped.get()) {
                        processor.flushQueue()
                        var flushedBytes: Int
                        do {
                            if (isStopped.get()) break
                            outputBuffer.clear()
                            // Pass 0 length for flush processing
                            flushedBytes = processor.process(inputBuffer, 0, outputBuffer, outputBuffer.capacity())
                            
                            if (flushedBytes > 0) {
                                outputBuffer.get(chunkWriteBuffer, 0, flushedBytes)
                                val max = callback.maxBufferSize
                                var offset = 0
                                while (offset < flushedBytes) {
                                    if (isStopped.get()) break
                                    val len = min(flushedBytes - offset, max)
                                    callback.audioAvailable(chunkWriteBuffer, offset, len)
                                    offset += len
                                }
                            }
                        } while (flushedBytes > 0)
                    }

                } catch (e: Exception) {
                    AppLogger.error("[$reqId] Reader Error", e)
                } finally {
                    if (!isStopped.get()) {
                        try { writerThread.join(2000) } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) { 
            AppLogger.error("[$reqId] Critical", e) 
        } finally {
            if (!isStopped.get()) {
                callback.done()
                AppLogger.log("[$reqId] Done.")
            } else {
                AppLogger.log("[$reqId] Stopped.")
            }
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
        } catch (e: Exception) {}
    }
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onDestroy() {
        super.onDestroy()
        onStop()
        sharedProcessor?.release()
        sharedProcessor = null
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

