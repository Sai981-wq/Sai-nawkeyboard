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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
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
        AppLogger.log("=== Service Started (v27: No Skipping) ===")
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

        try { activeReadFd?.close() } catch (e: Exception) {}

        val text = request.charSequenceText.toString()
        val chunks = TTSUtils.splitHelper(text)
        val reqId = UUID.randomUUID().toString().substring(0, 4)

        if (chunks.isEmpty()) {
            callback.done()
            return
        }

        val engineRate = request.speechRate / 100.0f
        val enginePitch = request.pitch / 100.0f

        runBlocking(sessionJob) {
            try {
                callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

                for ((index, chunk) in chunks.withIndex()) {
                    if (!isActive) break
                    if (chunk.text.isBlank()) continue

                    val (engine, currentPkg) = when (chunk.lang) {
                        "SHAN" -> shanEngine to shanPkg
                        "MYANMAR" -> burmeseEngine to burmesePkg
                        else -> englishEngine to englishPkg
                    }

                    val activeEngine = engine ?: englishEngine
                    val activePkg = if (engine != null) currentPkg else englishPkg

                    if (activeEngine == null) {
                        AppLogger.error("[$reqId] No Engine")
                        continue
                    }

                    // User Prefs Hz (11000 ဆို 11000 ပဲ ယူမယ်)
                    var inputHz = prefs.getInt("RATE_$activePkg", 22050)
                    if (inputHz < 8000) inputHz = 22050

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
                                // 1. ပထမ 44 bytes ကို ဖတ်ပြီး စစ်မယ်
                                val headerBuffer = ByteArray(44)
                                var bytesRead = 0
                                while (bytesRead < 44 && isActive) {
                                    val count = fis.read(headerBuffer, bytesRead, 44 - bytesRead)
                                    if (count < 0) break
                                    bytesRead += count
                                }

                                if (bytesRead > 0) {
                                    val isWav = if (bytesRead >= 4) {
                                        val riff = String(headerBuffer, 0, 4, Charset.forName("ASCII"))
                                        riff == "RIFF"
                                    } else false

                                    val finalHz: Int
                                    val bytesToInject: ByteArray?

                                    if (isWav && bytesRead == 44) {
                                        // Header အစစ်ဆိုရင် Hz ယူမယ်၊ Header ကို ကျော်မယ်
                                        val detected = ByteBuffer.wrap(headerBuffer).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
                                        finalHz = if (detected in 8000..48000) detected else inputHz
                                        bytesToInject = null 
                                        AppLogger.log("[$reqId] WAV Header Found: $detected Hz")
                                    } else {
                                        // Raw Audio ဆိုရင် Pref Hz ယူမယ်
                                        finalHz = inputHz
                                        // အရေးကြီးဆုံး: ဖတ်ထားတဲ့ 44 bytes ကို လွှင့်မပစ်ဘဲ ပြန်ထည့်ပေးမယ်
                                        bytesToInject = headerBuffer.copyOfRange(0, bytesRead)
                                        AppLogger.log("[$reqId] Raw Audio Detected: $inputHz Hz (Injecting Header Bytes)")
                                    }

                                    // SonicStreamer ဆီ ပို့မယ်
                                    SonicStreamer.stream(
                                        inputStream = fis,
                                        initialBytes = bytesToInject, // ဒီမှာ ပြန်ထည့်ပေးလိုက်ပြီ
                                        callback = callback,
                                        inputHz = finalHz,
                                        outputHz = TARGET_HZ,
                                        reqId = reqId
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e !is CancellationException) AppLogger.error("[$reqId] Stream Error", e)
                    } finally {
                        try { readFd.close() } catch (e: Exception) {}
                        activeReadFd = null
                        writerJob.cancelAndJoin()
                    }
                }
            } catch (e: Exception) {
                AppLogger.error("[$reqId] Session Error", e)
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

