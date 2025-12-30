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
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt
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
        AppLogger.log("=== Service onCreate (Smart SquareRoot Speed) ===")
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

    // ★ NEW: Smart Speed Calculator ★
    private fun calculateSmartRate(jieshuoRate: Float): Float {
        // 1. Minimum Floor (Prevent < 0.2 crash)
        if (jieshuoRate < 0.1f) return 1.0f // Reset if too low (bug)
        if (jieshuoRate < 0.5f) return 0.5f // Minimum slow speed

        // 2. Normal Range (0.5 - 1.5) -> Pass through
        if (jieshuoRate <= 1.5f) return jieshuoRate

        // 3. High Speed Range (> 1.5) -> Use Square Root Curve
        // Formula: 1.5 + sqrt(input - 1.5)
        // Example: Input 5.5 -> 1.5 + sqrt(4) = 3.5
        // This dampens the acceleration.
        return 1.0f + sqrt(jieshuoRate - 0.5f)
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

        val firstLang = chunks[0].lang
        val targetPkg = when (firstLang) {
            "SHAN" -> shanPkg
            "MYANMAR" -> burmesePkg
            else -> englishPkg
        }
        
        // Fix Hz logic
        var hz = prefs.getInt("RATE_$targetPkg", 0)
        if (hz < 8000) hz = if (targetPkg.contains("google")) 24000 else 22050
        
        // Scanner Multiplier (Normalization)
        val engineMult = prefs.getFloat("MULT_$targetPkg", 1.0f)

        // Jieshuo Raw Rate (e.g. 5.0, 8.0)
        val rawRate = request.speechRate / 100.0f
        
        // ★ Apply Smart Curve ★
        val curvedRate = calculateSmartRate(rawRate)
        
        // ★ Apply Engine Normalization ★
        val finalBaseRate = curvedRate * engineMult
        
        // ★ Final Hard Cap (Safety Net) ★
        // No engine should ever go above 3.5x, regardless of inputs
        val safeRate = finalBaseRate.coerceIn(0.4f, 3.5f)

        val finalPitch = (request.pitch / 100.0f).coerceIn(0.8f, 1.2f)
        
        AppLogger.log("[$reqId] Rate: Raw=$rawRate -> Curved=$curvedRate -> Final=$safeRate (Hz=$hz)")

        try {
            callback.start(hz, AudioFormat.ENCODING_PCM_16BIT, 1)
            val buffer = ByteArray(16384) 

            for ((index, chunk) in chunks.withIndex()) {
                if (isStopped) break
                if (chunk.text.isBlank()) continue

                val engine = when (chunk.lang) {
                    "SHAN" -> shanEngine
                    "MYANMAR" -> burmeseEngine
                    else -> englishEngine
                } ?: englishEngine

                if (engine == null) continue

                // Per-Chunk Multiplier check
                val currentPkg = when (chunk.lang) {
                    "SHAN" -> shanPkg
                    "MYANMAR" -> burmesePkg
                    else -> englishPkg
                }
                val chunkMult = prefs.getFloat("MULT_$currentPkg", 1.0f)
                
                // Recalculate for specific chunk engine
                val chunkRate = (calculateSmartRate(rawRate) * chunkMult).coerceIn(0.4f, 3.5f)

                engine.setSpeechRate(chunkRate)
                engine.setPitch(finalPitch)

                // Low Latency Pipe Logic
                val pipe = ParcelFileDescriptor.createPipe()
                val readFd = pipe[0]
                val writeFd = pipe[1]
                val uuid = UUID.randomUUID().toString()
                
                val writerThread = Thread {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    try {
                        engine.synthesizeToFile(chunk.text, params, writeFd, uuid)
                    } catch (e: Exception) {
                        AppLogger.error("[$reqId-W] Error", e)
                    } finally {
                        try { writeFd.close() } catch (e: Exception) {}
                    }
                }
                writerThread.start()

                try {
                    ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                        // Skip Header 44 bytes
                        var skipped: Long = 0
                        while (skipped < 44) {
                            val s = fis.skip(44 - skipped)
                            if (s <= 0) break
                            skipped += s
                        }

                        while (!isStopped) {
                            val read = try { fis.read(buffer) } catch (e: IOException) { -1 }
                            if (read == -1) break
                            if (read > 0) {
                                val max = callback.maxBufferSize
                                var offset = 0
                                while (offset < read) {
                                    if (isStopped) break
                                    val len = min(read - offset, max)
                                    if (callback.audioAvailable(buffer, offset, len) == TextToSpeech.ERROR) {
                                        isStopped = true
                                        break
                                    }
                                    offset += len
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.error("[$reqId-R] Error", e)
                } finally {
                    try { writerThread.join(2000) } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) { 
            AppLogger.error("[$reqId] Critical", e) 
        } finally {
            if (!isStopped) callback.done()
        }
    }
    
    // ... Helpers (getDefaultEngineFallback, getPkg, isInstalled, initTTS, onDestroy) remain same ...
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

