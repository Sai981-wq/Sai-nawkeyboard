package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
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
    
    // Shared Processor
    @Volatile private var sharedProcessor: AudioProcessor? = null
    @Volatile private var currentProcessorHz = 0
    @Volatile private var currentWriterThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service onCreate (Simple Stream) ===")
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
        
        // Interrupt Writer
        currentWriterThread?.interrupt()
        
        // Flush Processor
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
        // Limit speed to avoid buffer underrun
        val finalSpeed = rawRate.coerceIn(0.1f, 5.0f)
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.5f, 2.0f)

        AppLogger.log("[$reqId] Start. Chunks=${chunks.size}")

        try {
            // Standard Android TTS Output
            callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

            // Buffers
            val inputBuffer = ByteBuffer.allocateDirect(8192).order(ByteOrder.LITTLE_ENDIAN)
            val outputBuffer = ByteBuffer.allocateDirect(16384).order(ByteOrder.LITTLE_ENDIAN)
            val chunkReadBuffer = ByteArray(8192)
            val chunkWriteBuffer = ByteArray(16384)

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

                // Processor Logic
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

                val writerThread = Thread {
                    currentWriterThread = Thread.currentThread()
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    try {
                        if (!isStopped.get() && !Thread.interrupted()) {
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
                        var skipped: Long = 0
                        while (skipped < 44 && !isStopped.get()) {
                            val s = fis.skip(44 - skipped)
                            if (s <= 0) break
                            skipped += s
                        }

                        while (!isStopped.get()) {
                            val bytesRead = try { fis.read(chunkReadBuffer) } catch (e: IOException) { -1 }
                            if (bytesRead == -1) break
                            
                            if (bytesRead > 0) {
                                // Byte alignment safety
                                var safeLen = bytesRead
                                if (safeLen % 2 != 0) safeLen--

                                if (safeLen > 0) {
                                    inputBuffer.clear()
                                    inputBuffer.put(chunkReadBuffer, 0, safeLen)
                                    outputBuffer.clear()
                                    
                                    val processedBytes = processor.process(
                                        inputBuffer, 
                                        safeLen, 
                                        outputBuffer, 
                                        outputBuffer.capacity()
                                    )
                                    
                                    if (processedBytes > 0) {
                                        outputBuffer.get(chunkWriteBuffer, 0, processedBytes)
                                        
                                        // Simple Write Loop (No complex offsets)
                                        // Just push to system. If system buffer full, it blocks naturally.
                                        val ret = callback.audioAvailable(chunkWriteBuffer, 0, processedBytes)
                                        if (ret == TextToSpeech.ERROR) {
                                            isStopped.set(true)
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Flush Logic
                    if (!isStopped.get()) {
                        processor.flushQueue()
                        var flushedBytes: Int
                        do {
                            if (isStopped.get()) break
                            outputBuffer.clear()
                            flushedBytes = processor.process(inputBuffer, 0, outputBuffer, outputBuffer.capacity())
                            
                            if (flushedBytes > 0) {
                                outputBuffer.get(chunkWriteBuffer, 0, flushedBytes)
                                val ret = callback.audioAvailable(chunkWriteBuffer, 0, flushedBytes)
                                if (ret == TextToSpeech.ERROR) break
                            }
                        } while (flushedBytes > 0)
                    }

                } catch (e: Exception) {
                    AppLogger.error("[$reqId] Loop Error", e)
                } finally {
                    if (!isStopped.get()) {
                        try { writerThread.join(1000) } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) { 
            AppLogger.error("[$reqId] Critical", e) 
        } finally {
            if (!isStopped.get()) callback.done()
        }
    }
    
    // Standard Helpers...
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

