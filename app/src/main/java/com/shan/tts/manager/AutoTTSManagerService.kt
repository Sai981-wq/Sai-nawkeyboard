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

    // C++ ဘက်မှာ 24000Hz သတ်မှတ်ထားသလို ဒီဘက်မှာလည်း 24000Hz အသေထားပါမယ်
    private val TARGET_HZ = 24000

    @Volatile private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service onCreate (Native Sonic Integration) ===")
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defEngine = getDefaultEngineFallback()

            shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defEngine)
            initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it }

            burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defEngine)
            initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }

            englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defEngine)
            initTTS(englishPkg, Locale.US) { englishEngine = it }
            
            AppLogger.log("Engines: Shan=$shanPkg, Bur=$burmesePkg, Eng=$englishPkg")
        } catch (e: Exception) { 
            AppLogger.error("Error in onCreate", e)
        }
    }

    override fun onStop() {
        isStopped = true
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

        // --- Speed Calculation (Jieshuo Fix) ---
        val rawRate = request.speechRate / 100.0f
        val smartSpeed = if (rawRate <= 1.0f) {
             rawRate.coerceAtLeast(0.1f)
        } else {
             // Logarithmic curve for high speeds
             1.0f + (ln(rawRate) * 0.7f)
        }
        val finalBaseSpeed = smartSpeed.coerceIn(0.1f, 3.0f)
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.8f, 1.2f)

        AppLogger.log("[$reqId] Rate: Raw=$rawRate -> Smart=$finalBaseSpeed")

        try {
            // ★ SYSTEM OUTPUT: Always 24000Hz (Matched with C++) ★
            callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

            // Buffers for JNI Processing
            // Direct Buffers are required for efficient JNI access
            val inputBuffer = ByteBuffer.allocateDirect(4096)
            val outputBuffer = ByteBuffer.allocateDirect(16384) // Output can be larger due to resampling
            val chunkReadBuffer = ByteArray(4096)
            val chunkWriteBuffer = ByteArray(16384)

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
                
                // Get Source Engine Hz (Defaults to 22050 if unknown)
                var engineHz = prefs.getInt("RATE_$currentPkg", 0)
                if (engineHz <= 0) engineHz = 22050 

                // Calculate Speed for this chunk
                val chunkMult = prefs.getFloat("MULT_$currentPkg", 1.0f)
                val chunkSpeed = (finalBaseSpeed * chunkMult).coerceIn(0.1f, 3.5f)

                AppLogger.log("[$reqId] Processing Chunk $index ($currentPkg): InHz=$engineHz -> OutHz=24000, Speed=$chunkSpeed")

                // ★ Initialize Native AudioProcessor ★
                val processor = AudioProcessor(engineHz, 1)
                processor.setSpeed(chunkSpeed)
                processor.setPitch(finalPitch)

                // Pipe Setup
                val pipe = ParcelFileDescriptor.createPipe()
                val readFd = pipe[0]
                val writeFd = pipe[1]
                val uuid = UUID.randomUUID().toString()

                // Configure Engine
                // We set rate/pitch to 1.0 on the engine itself because 
                // Sonic (C++) will handle the actual speed/pitch changes.
                // Exception: If engine is very unstable, we might need basic setup, 
                // but usually raw output is best for Sonic.
                engine.setSpeechRate(1.0f) 
                engine.setPitch(1.0f)

                val writerThread = Thread {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    try {
                        engine.synthesizeToFile(chunk.text, params, writeFd, uuid)
                    } catch (e: Exception) {
                        AppLogger.error("[$reqId-W] Writer Error", e)
                    } finally {
                        try { writeFd.close() } catch (e: Exception) {}
                    }
                }
                writerThread.start()

                try {
                    ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                        // Skip WAV Header (44 bytes)
                        var skipped: Long = 0
                        while (skipped < 44) {
                            val s = fis.skip(44 - skipped)
                            if (s <= 0) break
                            skipped += s
                        }

                        while (!isStopped) {
                            val bytesRead = fis.read(chunkReadBuffer)
                            if (bytesRead == -1) break
                            
                            if (bytesRead > 0) {
                                // 1. Copy Data to Direct Input Buffer
                                inputBuffer.clear()
                                inputBuffer.put(chunkReadBuffer, 0, bytesRead)
                                
                                // 2. Process via C++ (Sonic)
                                outputBuffer.clear()
                                val processedBytes = processor.process(
                                    inputBuffer, 
                                    bytesRead, 
                                    outputBuffer, 
                                    outputBuffer.capacity()
                                )
                                
                                // 3. Send Processed Data to System
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

                    // ★ FLUSH & DRAIN SONIC BUFFER ★
                    if (!isStopped) {
                        processor.flushQueue() // Signals Sonic to flush
                        // Read the remaining tail
                        outputBuffer.clear()
                        // Passing 0 length input to trigger read-only
                        val flushedBytes = processor.process(
                            inputBuffer, 
                            0, 
                            outputBuffer, 
                            outputBuffer.capacity()
                        )
                        
                        if (flushedBytes > 0) {
                            outputBuffer.get(chunkWriteBuffer, 0, flushedBytes)
                            callback.audioAvailable(chunkWriteBuffer, 0, flushedBytes)
                        }
                    }

                } catch (e: Exception) {
                    AppLogger.error("[$reqId-R] Reader Error", e)
                } finally {
                    processor.release() // Clean up C++ memory
                    try { writerThread.join(2000) } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) { 
            AppLogger.error("[$reqId] Critical Error", e) 
        } finally {
            if (!isStopped) callback.done()
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
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

