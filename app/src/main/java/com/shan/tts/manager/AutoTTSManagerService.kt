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
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkg = ""
    private var burmesePkg = ""
    private var englishPkg = ""

    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "TTS_CONFIG"
    
    // Fixed Output Rate to keep AudioTrack stable
    private val TARGET_HZ = 24000 

    private val serviceJob = SupervisorJob()
    private var currentSessionJob: Job? = null
    
    // Track active pipe to force close on stop
    private var activeReadFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service Started (v11 Final Stable + Logs) ===")
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defEngine = getDefaultEngineFallback()

            shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defEngine)
            initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it }

            burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defEngine)
            initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }

            englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defEngine)
            initTTS(englishPkg, Locale.US) { englishEngine = it }
        } catch (e: Exception) { 
            AppLogger.error("onCreate Error", e)
        }
    }

    override fun onStop() {
        // Critical: Cancel job AND close pipes to prevent deadlock (Silence on Swipe)
        currentSessionJob?.cancel()
        currentSessionJob = null
        try { activeReadFd?.close() } catch (e: Exception) {}
        activeReadFd = null
        
        stopSafe(shanEngine)
        stopSafe(burmeseEngine)
        stopSafe(englishEngine)
    }

    private fun stopSafe(engine: TextToSpeech?) {
        try { engine?.stop() } catch (e: Exception) {}
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        
        currentSessionJob?.cancel()
        val sessionJob = Job()
        currentSessionJob = sessionJob
        
        // Clean up old pipe
        try { activeReadFd?.close() } catch (e: Exception) {}
        
        val text = request.charSequenceText.toString()
        val chunks = TTSUtils.splitHelper(text)
        if (chunks.isEmpty()) {
            callback.done()
            return
        }

        val rawRate = request.speechRate / 100.0f
        val finalSpeed = rawRate.coerceIn(0.1f, 6.0f)
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.5f, 2.0f)
        val reqId = UUID.randomUUID().toString().substring(0, 4)

        // Log request start
        AppLogger.log("[$reqId] NEW REQ: '${text.take(15)}...' Chunks=${chunks.size}")

        runBlocking(sessionJob) {
            try {
                // Fixed Output Rate (24kHz)
                callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

                for ((index, chunk) in chunks.withIndex()) {
                    if (!isActive) {
                        AppLogger.log("[$reqId] Cancelled at Chunk $index")
                        break
                    }
                    if (chunk.text.isBlank()) continue

                    val (engine, currentPkg) = when (chunk.lang) {
                        "SHAN" -> shanEngine to shanPkg
                        "MYANMAR" -> burmeseEngine to burmesePkg
                        else -> englishEngine to englishPkg
                    }

                    val activeEngine = engine ?: englishEngine
                    val activePkg = if (engine != null) currentPkg else englishPkg

                    if (activeEngine == null) {
                         AppLogger.error("[$reqId] No Engine found for ${chunk.lang}")
                         continue
                    }

                    // Get Input Hz from Scanner Prefs
                    var inputHz = prefs.getInt("RATE_$activePkg", 22050)
                    if (inputHz < 8000) inputHz = 22050
                    
                    val pipe = ParcelFileDescriptor.createPipe()
                    val readFd = pipe[0]
                    val writeFd = pipe[1]
                    val uuid = UUID.randomUUID().toString()

                    activeReadFd = readFd 

                    activeEngine.setSpeechRate(1.0f) 
                    activeEngine.setPitch(1.0f)

                    val writerJob = launch(Dispatchers.IO) {
                        val params = Bundle()
                        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                        try {
                            activeEngine.synthesizeToFile(chunk.text, params, writeFd, uuid)
                        } catch (e: Exception) {
                           AppLogger.error("[$reqId] Writer Error", e)
                        } finally {
                            try { writeFd.close() } catch (e: Exception) {}
                        }
                    }

                    try {
                        withContext(Dispatchers.IO) {
                            FileInputStream(readFd.fileDescriptor).use { fis ->
                                // SKIP WAV HEADER (44 Bytes) - To prevent noise/speedup
                                val header = ByteArray(44)
                                var skipped = 0
                                while (skipped < 44 && isActive) {
                                    val count = fis.read(header, skipped, 44 - skipped)
                                    if (count < 0) break
                                    skipped += count
                                }
                                
                                if (skipped == 44) {
                                    AppLogger.log("[$reqId] Chk $index: $inputHz -> $TARGET_HZ Hz ($activePkg)")
                                    
                                    // Create FRESH processor for every chunk + Pass reqId for logging
                                    val processor = AudioProcessor(inputHz, TARGET_HZ, reqId)
                                    try {
                                        processor.setSpeed(finalSpeed)
                                        processor.setPitch(finalPitch)
                                        processor.processStream(fis, callback, this)
                                    } finally {
                                        processor.release()
                                    }
                                } else {
                                    AppLogger.error("[$reqId] Header Skip Failed (Read $skipped bytes)")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            AppLogger.error("[$reqId] Processing Error", e)
                        }
                    } finally {
                        try { readFd.close() } catch (e: Exception) {}
                        activeReadFd = null
                        writerJob.cancelAndJoin()
                    }
                }
            } catch (e: Exception) {
                AppLogger.error("[$reqId] Critical Session Error", e)
            } finally {
                if (isActive) {
                    callback.done()
                    AppLogger.log("[$reqId] DONE")
                } else {
                    AppLogger.log("[$reqId] SESSION CANCELLED")
                }
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
            t = TextToSpeech(this, { if (it == TextToSpeech.SUCCESS) onReady(t!!) }, pkg)
        } catch (e: Exception) {}
    }
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    
    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

