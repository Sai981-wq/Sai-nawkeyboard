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
import android.speech.tts.UtteranceProgressListener
import java.io.IOException
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

    @Volatile private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service onCreate (Low Latency Mode) ===")
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
        // Stop all engines immediately
        stopSafe(shanEngine, "Shan")
        stopSafe(burmeseEngine, "Burmese")
        stopSafe(englishEngine, "English")
    }

    private fun stopSafe(engine: TextToSpeech?, name: String) {
        try { 
            engine?.stop() 
        } catch (e: Exception) {
            AppLogger.error("Error stopping $name", e)
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            AppLogger.error("Request or Callback is NULL")
            return
        }
        // Unique ID for this request to track in logs
        val reqId = UUID.randomUUID().toString().substring(0, 5)
        AppLogger.log("[$reqId] New Request. Text Length: ${request.charSequenceText.length}")
        
        isStopped = false
        val text = request.charSequenceText.toString()

        val chunks = TTSUtils.splitHelper(text)
        if (chunks.isEmpty()) {
            AppLogger.log("[$reqId] No chunks to speak.")
            callback.done()
            return
        }
        AppLogger.log("[$reqId] Split into ${chunks.size} chunks")

        val firstLang = chunks[0].lang
        val targetPkg = when (firstLang) {
            "SHAN" -> shanPkg
            "MYANMAR" -> burmesePkg
            else -> englishPkg
        }
        
        var hz = prefs.getInt("RATE_$targetPkg", 0)
        if (hz == 0) hz = if (targetPkg.contains("google")) 24000 else 22050
        
        val engineMult = prefs.getFloat("MULT_$targetPkg", 1.0f)
        AppLogger.log("[$reqId] Main Config - Hz: $hz, Mult: $engineMult")

        var rawRate = request.speechRate / 100.0f
        var safeRate = if (rawRate < 0.35f) 0.35f else rawRate
        if (safeRate > 3.0f) safeRate = 3.0f

        val finalRate = safeRate * engineMult
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.7f, 1.4f)
        
        AppLogger.log("[$reqId] Rate Calculation: Raw=$rawRate -> Final=$finalRate")

        try {
            // Start Audio Stream once
            callback.start(hz, AudioFormat.ENCODING_PCM_16BIT, 1)

            // Buffer for reading (16KB)
            val buffer = ByteArray(16384) 

            for ((index, chunk) in chunks.withIndex()) {
                if (isStopped) {
                    AppLogger.log("[$reqId] Stopped by user at chunk $index")
                    break
                }
                if (chunk.text.isBlank()) continue

                val engine = when (chunk.lang) {
                    "SHAN" -> shanEngine
                    "MYANMAR" -> burmeseEngine
                    else -> englishEngine
                } ?: englishEngine

                if (engine == null) {
                    AppLogger.error("[$reqId] Engine is NULL for chunk $index (${chunk.lang})")
                    continue
                }

                val currentPkg = when (chunk.lang) {
                    "SHAN" -> shanPkg
                    "MYANMAR" -> burmesePkg
                    else -> englishPkg
                }
                
                val chunkMult = prefs.getFloat("MULT_$currentPkg", 1.0f)
                val chunkRate = (safeRate * chunkMult).coerceIn(0.2f, 3.5f)

                AppLogger.log("[$reqId] Processing Chunk $index [${chunk.lang}] Rate=$chunkRate")

                // 1. Create a FRESH Pipe for EACH chunk
                val pipe = ParcelFileDescriptor.createPipe()
                val readFd = pipe[0]
                val writeFd = pipe[1]
                val uuid = UUID.randomUUID().toString()

                // 2. Configure Engine
                engine.setSpeechRate(chunkRate)
                engine.setPitch(finalPitch)
                
                // 3. Start Writer Thread
                val writerThread = Thread {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    
                    try {
                        // AppLogger.log("[$reqId-W] Writer started for chunk $index")
                        val res = engine.synthesizeToFile(chunk.text, params, writeFd, uuid)
                        if (res != TextToSpeech.SUCCESS) {
                            AppLogger.error("[$reqId-W] synthesizeToFile returned ERROR code")
                        }
                    } catch (e: Exception) {
                        AppLogger.error("[$reqId-W] Writer Exception", e)
                    } finally {
                        try { 
                            writeFd.close() 
                            // AppLogger.log("[$reqId-W] WriteFD closed")
                        } catch (e: Exception) {}
                    }
                }
                writerThread.start()

                // 4. Main Thread acts as Reader
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                        
                        // ★ Header Skipping Logic ★
                        var bytesSkipped: Long = 0
                        while (bytesSkipped < 44) {
                            val skipped = fis.skip(44 - bytesSkipped)
                            if (skipped <= 0) break 
                            bytesSkipped += skipped
                        }
                        // AppLogger.log("[$reqId-R] Skipped $bytesSkipped bytes header")

                        // Stream Body Loop
                        while (!isStopped) {
                            val bytesRead = try { fis.read(buffer) } catch (e: IOException) { 
                                AppLogger.error("[$reqId-R] Read Error", e)
                                -1 
                            }
                            
                            if (bytesRead == -1) break // EOF
                            
                            if (bytesRead > 0) {
                                val max = callback.maxBufferSize
                                var offset = 0
                                while (offset < bytesRead) {
                                    if (isStopped) break
                                    val chunkLen = min(bytesRead - offset, max)
                                    val ret = callback.audioAvailable(buffer, offset, chunkLen)
                                    if (ret == TextToSpeech.ERROR) {
                                        AppLogger.error("[$reqId] AudioTrack Error")
                                        isStopped = true
                                        break
                                    }
                                    offset += chunkLen
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.error("[$reqId] Reader Exception Chunk $index", e)
                } finally {
                    try { writerThread.join(2000) } catch (e: Exception) {
                        AppLogger.error("[$reqId] Join Timeout", e)
                    }
                }
            }
        } catch (e: Exception) { 
            AppLogger.error("[$reqId] Critical Synthesize Error", e) 
        } finally {
            if (!isStopped) {
                callback.done()
                AppLogger.log("[$reqId] Request Done")
            }
        }
    }

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
            t = TextToSpeech(this, { 
                if (it == TextToSpeech.SUCCESS) {
                    AppLogger.log("TTS Init Success: $pkg")
                    onReady(t!!) 
                } else {
                    AppLogger.error("TTS Init Failed: $pkg")
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.error("TTS Init Error: $pkg", e)
        }
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

