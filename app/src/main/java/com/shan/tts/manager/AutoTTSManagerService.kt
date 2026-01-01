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
import kotlin.math.ln
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

    @Volatile private var isStopped = false
    @Volatile private var currentWriterThread: Thread? = null
    @Volatile private var currentProcessor: AudioProcessor? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service onCreate (Sonic Native + Anti-Freeze) ===")
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
        AppLogger.log("=== onStop Called ===")
        isStopped = true
        
        // 1. Interrupt writer immediately
        currentWriterThread?.interrupt()
        currentWriterThread = null

        // 2. Cleanup Native
        currentProcessor?.release()
        currentProcessor = null

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
        
        isStopped = false
        val text = request.charSequenceText.toString()
        val chunks = TTSUtils.splitHelper(text)
        if (chunks.isEmpty()) {
            callback.done()
            return
        }

        // --- Speed Calculation ---
        val rawRate = request.speechRate / 100.0f
        
        // Jieshuo Fix: Dampen extremely high speeds
        val smartSpeed = if (rawRate <= 1.0f) {
             rawRate.coerceAtLeast(0.1f)
        } else {
             // Formula: 1.0 + (Excess * 0.15)
             1.0f + ((rawRate - 1.0f) * 0.15f)
        }
        val finalBaseSpeed = smartSpeed.coerceIn(0.1f, 3.0f)
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.8f, 1.2f)

        AppLogger.log("[$reqId] Rate: Raw=$rawRate -> Final=$finalBaseSpeed")

        try {
            // System Output Fixed at 24000Hz
            callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

            // Direct Buffers
            val inputBuffer = ByteBuffer.allocateDirect(4096)
            val outputBuffer = ByteBuffer.allocateDirect(32768) 
            val chunkReadBuffer = ByteArray(4096)
            val chunkWriteBuffer = ByteArray(32768)

            for ((index, chunk) in chunks.withIndex()) {
                if (isStopped) break
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

                // Initialize Sonic with ENGINE Hz
                val processor = AudioProcessor(engineHz, 1)
                currentProcessor = processor // Track for cleanup
                
                processor.setSpeed(chunkSpeed)
                processor.setPitch(finalPitch)

                AppLogger.log("[$reqId] Chunk $index ($currentPkg) Hz=$engineHz Speed=$chunkSpeed")

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
                        if (!Thread.interrupted()) {
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
                        // Skip Header
                        var skipped: Long = 0
                        while (skipped < 44) {
                            val s = fis.skip(44 - skipped)
                            if (s <= 0) break
                            skipped += s
                        }

                        while (!isStopped) {
                            val bytesRead = try { fis.read(chunkReadBuffer) } catch (e: IOException) { -1 }
                            if (bytesRead == -1) break
                            
                            if (bytesRead > 0) {
                                inputBuffer.clear()
                                inputBuffer.put(chunkReadBuffer, 0, bytesRead)
                                
                                outputBuffer.clear()
                                // Process directly (C++ handles resampling via Rate)
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
                                        if (isStopped) break
                                        val len = min(processedBytes - offset, max)
                                        if (callback.audioAvailable(chunkWriteBuffer, offset, len) == TextToSpeech.ERROR) {
                                            isStopped = true
                                            break
                                        }
                                        offset += len
                                    }
                                }
                            }
                        }
                    }

                    // Drain Loop
                    if (!isStopped) {
                        processor.flushQueue()
                        var flushedBytes: Int
                        do {
                            outputBuffer.clear()
                            // Pass 0 input to trigger drain
                            flushedBytes = processor.process(inputBuffer, 0, outputBuffer, outputBuffer.capacity())
                            
                            if (flushedBytes > 0) {
                                outputBuffer.get(chunkWriteBuffer, 0, flushedBytes)
                                val max = callback.maxBufferSize
                                var offset = 0
                                while (offset < flushedBytes) {
                                    if (isStopped) break
                                    val len = min(flushedBytes - offset, max)
                                    callback.audioAvailable(chunkWriteBuffer, offset, len)
                                    offset += len
                                }
                            }
                        } while (flushedBytes > 0 && !isStopped)
                    }

                } catch (e: Exception) {
                    AppLogger.error("[$reqId] Reader Error", e)
                } finally {
                    processor.release()
                    currentProcessor = null
                    // Prevent freeze: Wait with timeout
                    if (!isStopped) {
                        try { writerThread.join(2000) } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) { 
            AppLogger.error("[$reqId] Critical", e) 
        } finally {
            if (!isStopped) callback.done()
        }
    }
    
    // Helpers (Standard)
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
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

