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

    private var sharedProcessor: AudioProcessor? = null
    private var currentProcessorHz = 0

    private val serviceJob = SupervisorJob()
    private var currentSessionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service Started (Standard IO Mode) ===")
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
        currentSessionJob?.cancel()
        sharedProcessor?.release()
        sharedProcessor = null
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

        AppLogger.log("[$reqId] NEW REQ: '${text.take(20)}...'")

        runBlocking(sessionJob) {
            try {
                callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

                val headerBuffer = ByteArray(44)

                for (chunk in chunks) {
                    if (!isActive) break
                    if (chunk.text.isBlank()) continue

                    val engine = when (chunk.lang) {
                        "SHAN" -> shanEngine
                        "MYANMAR" -> burmeseEngine
                        else -> englishEngine
                    } ?: englishEngine

                    if (engine == null) continue

                    val pipe = ParcelFileDescriptor.createPipe()
                    val readFd = pipe[0]
                    val writeFd = pipe[1]
                    val uuid = UUID.randomUUID().toString()

                    engine.setSpeechRate(1.0f) 
                    engine.setPitch(1.0f)

                    val writerJob = launch(Dispatchers.IO) {
                        val params = Bundle()
                        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                        try {
                            engine.synthesizeToFile(chunk.text, params, writeFd, uuid)
                        } catch (e: Exception) {
                            AppLogger.error("[$reqId] Writer Error", e)
                        } finally {
                            try { writeFd.close() } catch (e: Exception) {}
                        }
                    }

                    try {
                        withContext(Dispatchers.IO) {
                            FileInputStream(readFd.fileDescriptor).use { fis ->
                                var headerRead = 0
                                while (headerRead < 44 && isActive) {
                                    val count = fis.read(headerBuffer, headerRead, 44 - headerRead)
                                    if (count < 0) break
                                    headerRead += count
                                }

                                if (headerRead == 44) {
                                    val detectedHz = TTSUtils.parseWavSampleRate(headerBuffer)
                                    
                                    var finalHz = detectedHz
                                    if (finalHz <= 0) {
                                         val currentPkg = when (chunk.lang) {
                                            "SHAN" -> shanPkg
                                            "MYANMAR" -> burmesePkg
                                            else -> englishPkg
                                        }
                                        finalHz = prefs.getInt("RATE_$currentPkg", 22050)
                                    }
                                    if (finalHz <= 0) finalHz = 22050

                                    if (sharedProcessor == null || currentProcessorHz != finalHz) {
                                        sharedProcessor?.release()
                                        sharedProcessor = AudioProcessor(finalHz, 1)
                                        currentProcessorHz = finalHz
                                        AppLogger.log("[$reqId] ${chunk.lang}: Init Processor $finalHz Hz")
                                    }

                                    sharedProcessor?.setSpeed(finalSpeed)
                                    sharedProcessor?.setPitch(finalPitch)
                                    
                                    // Piped Stream Processing
                                    sharedProcessor?.processStream(fis, callback, this)
                                } else {
                                    AppLogger.error("[$reqId] Failed to read WAV Header")
                                }
                            }
                        }
                    } finally {
                        writerJob.cancelAndJoin()
                        try { readFd.close() } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
                AppLogger.error("Session Error", e)
            } finally {
                if (isActive) {
                    callback.done()
                    AppLogger.log("[$reqId] DONE")
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

