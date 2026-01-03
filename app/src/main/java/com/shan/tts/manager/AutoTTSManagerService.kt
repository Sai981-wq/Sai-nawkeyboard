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
    private val TARGET_HZ = 24000

    private val serviceJob = SupervisorJob()
    private var currentSessionJob: Job? = null
    private var activeReadFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service Created (Full Logging Mode) ===")
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
            AppLogger.error("Service onCreate Failed", e)
        }
    }

    override fun onStop() {
        AppLogger.log("Service: onStop Called")
        currentSessionJob?.cancel()
        currentSessionJob = null
        try { 
            if (activeReadFd != null) {
                activeReadFd?.close()
                AppLogger.log("Pipe: Force closed active pipe")
            }
        } catch (e: Exception) {
            AppLogger.error("Pipe Close Error", e)
        }
        activeReadFd = null
        stopSafe(shanEngine)
        stopSafe(burmeseEngine)
        stopSafe(englishEngine)
    }

    private fun stopSafe(engine: TextToSpeech?) {
        try { engine?.stop() } catch (e: Exception) {}
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            AppLogger.error("Request or Callback is null")
            return
        }
        
        currentSessionJob?.cancel()
        val sessionJob = Job()
        currentSessionJob = sessionJob
        
        try { activeReadFd?.close() } catch (e: Exception) {}
        
        val text = request.charSequenceText.toString()
        val reqId = UUID.randomUUID().toString().substring(0, 4)
        
        // Log Input Text
        AppLogger.log("[$reqId] REQ START: '${text.take(20)}...'")

        val chunks = TTSUtils.splitHelper(text)
        if (chunks.isEmpty()) {
            AppLogger.log("[$reqId] No chunks to process")
            callback.done()
            return
        }
        AppLogger.log("[$reqId] Split into ${chunks.size} chunks")

        val engineRate = request.speechRate / 100.0f
        val enginePitch = request.pitch / 100.0f

        runBlocking(sessionJob) {
            try {
                AppLogger.log("[$reqId] Starting AudioTrack at $TARGET_HZ Hz")
                callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

                for ((index, chunk) in chunks.withIndex()) {
                    if (!isActive) {
                        AppLogger.log("[$reqId] Job Cancelled at Chunk $index")
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
                        AppLogger.error("[$reqId] Engine NULL for ${chunk.lang}")
                        continue
                    }

                    var inputHz = prefs.getInt("RATE_$activePkg", 22050)
                    if (inputHz < 8000) inputHz = 22050
                    
                    // AppLogger.log("[$reqId] Chk $index: Using $activePkg ($inputHz Hz)")

                    val pipe = ParcelFileDescriptor.createPipe()
                    val readFd = pipe[0]
                    val writeFd = pipe[1]
                    val uuid = UUID.randomUUID().toString()

                    activeReadFd = readFd 

                    activeEngine.setSpeechRate(engineRate) 
                    activeEngine.setPitch(enginePitch)

                    val writerJob = launch(Dispatchers.IO) {
                        val params = Bundle()
                        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                        try {
                            // AppLogger.log("[$reqId] Writer: Synthesizing...")
                            val result = activeEngine.synthesizeToFile(chunk.text, params, writeFd, uuid)
                            if (result != TextToSpeech.SUCCESS) {
                                AppLogger.error("[$reqId] Writer: Engine returned ERROR code")
                            }
                        } catch (e: Exception) {
                           AppLogger.error("[$reqId] Writer Exception", e)
                        } finally {
                            try { writeFd.close() } catch (e: Exception) {}
                        }
                    }

                    try {
                        withContext(Dispatchers.IO) {
                            FileInputStream(readFd.fileDescriptor).use { fis ->
                                val header = ByteArray(44)
                                var skipped = 0
                                while (skipped < 44 && isActive) {
                                    val count = fis.read(header, skipped, 44 - skipped)
                                    if (count < 0) break
                                    skipped += count
                                }
                                
                                if (skipped == 44) {
                                    AppLogger.log("[$reqId] Chk $index: Header skipped. Processing...")
                                    val processor = AudioProcessor(inputHz, TARGET_HZ, reqId)
                                    try {
                                        processor.setSpeed(1.0f)
                                        processor.setPitch(1.0f)
                                        processor.processStream(fis, callback, this)
                                    } finally {
                                        processor.release()
                                    }
                                } else {
                                    AppLogger.error("[$reqId] Chk $index: Header read failed (Got $skipped bytes)")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            AppLogger.error("[$reqId] Reader Exception", e)
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
                    AppLogger.log("[$reqId] CANCELLED")
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
            t = TextToSpeech(this, { 
                if (it == TextToSpeech.SUCCESS) {
                    AppLogger.log("TTS Init Success: $pkg")
                    onReady(t!!) 
                } else {
                    AppLogger.error("TTS Init Failed: $pkg (Code $it)")
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.error("TTS Init Exception: $pkg", e)
        }
    }
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    
    override fun onDestroy() {
        super.onDestroy()
        AppLogger.log("Service Destroyed")
        serviceJob.cancel()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

