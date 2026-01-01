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
    
    // ★ Singleton Processor variables ★
    @Volatile private var sharedProcessor: AudioProcessor? = null
    @Volatile private var currentProcessorHz = 0
    @Volatile private var currentWriterThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service onCreate (Optimized Re-Use) ===")
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
        
        // Don't release sharedProcessor here immediately if you want to reuse it across requests.
        // BUT for safety on stop, we can flush or just leave it.
        // Releasing it ensures clean state if user switches apps.
        sharedProcessor?.release()
        sharedProcessor = null
        currentProcessorHz = 0

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
        val smartSpeed = if (rawRate <= 1.0f) {
             rawRate.coerceAtLeast(0.1f)
        } else {
             1.0f + ((rawRate - 1.0f) * 0.15f)
        }
        val finalBaseSpeed = smartSpeed.coerceIn(0.1f, 3.0f)
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.8f, 1.2f)

        AppLogger.log("[$reqId] Start. Chunks=${chunks.size}")

        try {
            callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

            val inputBuffer = ByteBuffer.allocateDirect(8192)
            val outputBuffer = ByteBuffer.allocateDirect(32768) 
            val chunkReadBuffer = ByteArray(8192)
            val chunkWriteBuffer = ByteArray(32768)

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

                val chunkMult = prefs.getFloat("MULT_$currentPkg", 1.0f)
                val chunkSpeed = (finalBaseSpeed * chunkMult).coerceIn(0.1f, 3.5f)

                // ★ PROCESSOR RE-USE LOGIC ★
                // Only create new processor if Hz changes or it's null
                var processor = sharedProcessor
                if (processor == null || currentProcessorHz != engineHz) {
                    processor?.release() // Release old one
                    AppLogger.log("[$reqId] Switching Processor: $currentProcessorHz -> $engineHz")
                    
                    processor = AudioProcessor(engineHz, 1)
                    processor.init()
                    
                    sharedProcessor = processor
                    currentProcessorHz = engineHz
                }
                
                // Update settings on existing processor
                processor.setSpeed(chunkSpeed)
                processor.setPitch(finalPitch)

                // AppLogger.log("[$reqId] Chunk $index ($currentPkg) Hz=$engineHz")

                val pipe = ParcelFileDescriptor.createPipe()
                val readFd = pipe[0]
                val writeFd = pipe[1]
                val uuid = UUID.randomUUID().toString()

                engine.setSpeechRate(1.0f) 
                engine.setPitch(1.0f)

                val writerThread = Thread {
                    currentWriterThread = Thread.currentThread()
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
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
                                inputBuffer.clear()
                                inputBuffer.put(chunkReadBuffer, 0, bytesRead)
                                
                                outputBuffer.clear()
                                val processedBytes = processor.process(
                                    inputBuffer, 
                                    bytesRead, 
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
                                        if (callback.audioAvailable(chunkWriteBuffer, offset, len) == TextToSpeech.ERROR) {
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
                        // For re-usable processor, we just flush, DO NOT RELEASE
                        processor.flushQueue()
                        
                        var flushedBytes: Int
                        do {
                            if (isStopped.get()) break
                            outputBuffer.clear()
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
                    AppLogger.error("[$reqId] Loop Error", e)
                } finally {
                    // ★ CRITICAL: Do NOT release processor here. Only in onStop. ★
                    if (!isStopped.get()) {
                        try { writerThread.join(2000) } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) { 
            AppLogger.error("[$reqId] Critical", e) 
        } finally {
            if (!isStopped.get()) callback.done()
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
        // Final cleanup
        sharedProcessor?.release()
        sharedProcessor = null
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

