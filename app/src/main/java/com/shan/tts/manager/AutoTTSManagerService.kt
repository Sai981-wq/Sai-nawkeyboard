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

    // C++ Sonic Output Rate is HARDCODED to 24000 in native-lib.cpp
    private val TARGET_HZ = 24000

    @Volatile private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service onCreate (Native Sonic + Full Logging) ===")
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defEngine = getDefaultEngineFallback()

            shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defEngine)
            initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it }

            burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defEngine)
            initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }

            englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defEngine)
            initTTS(englishPkg, Locale.US) { englishEngine = it }
            
            AppLogger.log("Engines Initialized: Shan=$shanPkg, Bur=$burmesePkg, Eng=$englishPkg")
        } catch (e: Exception) { 
            AppLogger.error("Error in onCreate", e)
        }
    }

    override fun onStop() {
        AppLogger.log("=== onStop Called ===")
        isStopped = true
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
            AppLogger.error("Request or Callback is NULL")
            return
        }
        val reqId = UUID.randomUUID().toString().substring(0, 5)
        AppLogger.log("[$reqId] New Request. Length: ${request.charSequenceText.length}")
        
        isStopped = false
        val text = request.charSequenceText.toString()

        val chunks = TTSUtils.splitHelper(text)
        if (chunks.isEmpty()) {
            AppLogger.log("[$reqId] No chunks generated.")
            callback.done()
            return
        }
        AppLogger.log("[$reqId] Text split into ${chunks.size} chunks")

        // --- Speed Calculation (Jieshuo Fix) ---
        val rawRate = request.speechRate / 100.0f
        
        // Aggressive Scaling for Jieshuo's high values
        val smartSpeed = if (rawRate <= 1.0f) {
             rawRate.coerceAtLeast(0.1f)
        } else {
             // 1.0 + (Excess * 0.15)
             1.0f + ((rawRate - 1.0f) * 0.15f)
        }
        val finalBaseSpeed = smartSpeed.coerceIn(0.1f, 3.0f)
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.8f, 1.2f)

        AppLogger.log("[$reqId] Rate Logic: Raw=$rawRate -> Smart=$smartSpeed -> Final=$finalBaseSpeed")

        try {
            // ★ SYSTEM OUTPUT: Always 24000Hz (Matched with C++) ★
            AppLogger.log("[$reqId] Starting Audio Track at ${TARGET_HZ}Hz")
            callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

            // Direct Buffers for JNI (Critical for performance)
            val inputBuffer = ByteBuffer.allocateDirect(4096)
            val outputBuffer = ByteBuffer.allocateDirect(16384) 
            val chunkReadBuffer = ByteArray(4096)
            val chunkWriteBuffer = ByteArray(16384)

            for ((index, chunk) in chunks.withIndex()) {
                if (isStopped) {
                    AppLogger.log("[$reqId] Process Stopped by user")
                    break
                }
                if (chunk.text.isBlank()) continue

                val engine = when (chunk.lang) {
                    "SHAN" -> shanEngine
                    "MYANMAR" -> burmeseEngine
                    else -> englishEngine
                } ?: englishEngine

                if (engine == null) {
                    AppLogger.error("[$reqId] Engine NULL for chunk $index")
                    continue
                }

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

                AppLogger.log("[$reqId] Chunk $index ($currentPkg): InHz=$engineHz -> OutHz=24000, Speed=$chunkSpeed")

                // ★ Initialize Native AudioProcessor with ACTUAL Engine Hz ★
                val processor = AudioProcessor(engineHz, 1)
                processor.setSpeed(chunkSpeed)
                processor.setPitch(finalPitch)

                // Pipe Setup
                val pipe = ParcelFileDescriptor.createPipe()
                val readFd = pipe[0]
                val writeFd = pipe[1]
                val uuid = UUID.randomUUID().toString()

                // Configure Engine (Standard Rate, Sonic handles speed)
                engine.setSpeechRate(1.0f) 
                engine.setPitch(1.0f)

                val writerThread = Thread {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    try {
                        val res = engine.synthesizeToFile(chunk.text, params, writeFd, uuid)
                        if (res != TextToSpeech.SUCCESS) {
                            AppLogger.error("[$reqId-W] Synthesize Error code: $res")
                        }
                    } catch (e: Exception) {
                        AppLogger.error("[$reqId-W] Writer Exception", e)
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
                            val bytesRead = try { fis.read(chunkReadBuffer) } catch (e: IOException) { -1 }
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
                                            AppLogger.error("[$reqId] AudioTrack Error")
                                            isStopped = true
                                            break
                                        }
                                        offset += len
                                    }
                                }
                            }
                        }
                    }

                    // ★ FLUSH & DRAIN SONIC BUFFER (Fixes Skipping Words) ★
                    if (!isStopped) {
                        processor.flushQueue()
                        
                        var flushedBytes: Int
                        do {
                            outputBuffer.clear()
                            // Passing 0 length input to trigger read-only drain
                            flushedBytes = processor.process(
                                inputBuffer, 
                                0, 
                                outputBuffer, 
                                outputBuffer.capacity()
                            )
                            
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
                    AppLogger.error("[$reqId-R] Reader Loop Exception", e)
                } finally {
                    processor.release() // Clean up C++ memory
                    try { writerThread.join(10000) } catch (e: Exception) {
                         AppLogger.error("[$reqId] Join Timeout", e)
                    }
                }
            }
        } catch (e: Exception) { 
            AppLogger.error("[$reqId] Critical Service Error", e) 
        } finally {
            if (!isStopped) {
                callback.done()
                AppLogger.log("[$reqId] Request Completed")
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
        } catch (e: Exception) { AppLogger.error("Init TTS Error: $pkg", e) }
    }
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onDestroy() {
        super.onDestroy()
        AppLogger.log("=== Service onDestroy ===")
        onStop()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

