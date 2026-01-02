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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val TARGET_HZ = 24000 // Fixed Output Rate

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var synthesisJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service onCreate (Java Sonic + Logs) ===")
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
        } catch (e: Exception) { AppLogger.error("onCreate Error", e) }
    }

    override fun onStop() {
        AppLogger.log("=== onStop Called ===")
        synthesisJob?.cancel()
        synthesisJob = null
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
        
        // Clear previous job
        synthesisJob?.cancel()

        val text = request.charSequenceText.toString()
        val chunks = TTSUtils.splitHelper(text)
        if (chunks.isEmpty()) {
            callback.done()
            return
        }

        // Speed Logic
        val rawRate = request.speechRate / 100.0f
        val finalSpeed = rawRate.coerceIn(0.1f, 6.0f)
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.5f, 2.0f)

        AppLogger.log("[$reqId] Start Synthesis. Chunks=${chunks.size}, Speed=$finalSpeed")

        runBlocking {
            synthesisJob = launch(Dispatchers.IO) {
                try {
                    // Always start with Fixed 24000Hz
                    callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

                    val chunkReadBuffer = ByteArray(4096)
                    val sonicOutputBuffer = ByteArray(8192)

                    // Create Sonic Instance
                    val sonic = Sonic(TARGET_HZ, 1) // Start with default
                    sonic.setOutputSampleRate(TARGET_HZ)
                    sonic.setSpeed(finalSpeed)
                    sonic.setPitch(finalPitch)

                    for ((index, chunk) in chunks.withIndex()) {
                        if (!isActive) {
                            AppLogger.log("[$reqId] Cancelled at chunk $index")
                            break
                        }
                        if (chunk.text.isBlank()) continue

                        val engine = when (chunk.lang) {
                            "SHAN" -> shanEngine
                            "MYANMAR" -> burmeseEngine
                            else -> englishEngine
                        } ?: englishEngine

                        if (engine == null) {
                            AppLogger.error("[$reqId] Engine is NULL for ${chunk.lang}")
                            continue
                        }

                        val currentPkg = when (chunk.lang) {
                            "SHAN" -> shanPkg
                            "MYANMAR" -> burmesePkg
                            else -> englishPkg
                        }
                        
                        var engineHz = prefs.getInt("RATE_$currentPkg", 0)
                        if (engineHz <= 0) engineHz = 22050 

                        AppLogger.log("[$reqId] Processing Chunk $index ($currentPkg). InputHz=$engineHz")

                        // Update Sonic Input Hz
                        sonic.setSampleRate(engineHz)
                        
                        val pipe = ParcelFileDescriptor.createPipe()
                        val readFd = pipe[0]
                        val writeFd = pipe[1]
                        val uuid = UUID.randomUUID().toString()

                        engine.setSpeechRate(1.0f) 
                        engine.setPitch(1.0f)

                        // Launch Writer
                        val writerJob = launch(Dispatchers.IO) {
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

                        // Reader Loop
                        try {
                            ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                                var skipped: Long = 0
                                while (skipped < 44 && isActive) {
                                    val s = fis.skip(44 - skipped)
                                    if (s <= 0) break
                                    skipped += s
                                }

                                while (isActive) {
                                    val bytesRead = try { fis.read(chunkReadBuffer) } catch (e: IOException) { -1 }
                                    if (bytesRead == -1) break
                                    
                                    if (bytesRead > 0) {
                                        // 1. Feed Sonic
                                        sonic.writeBytesToStream(chunkReadBuffer, bytesRead)
                                        
                                        // 2. Read from Sonic & Send to Callback
                                        var available = sonic.samplesAvailable()
                                        while (available > 0 && isActive) {
                                            val readLen = min(available, sonicOutputBuffer.size)
                                            val actualRead = sonic.readBytesFromStream(sonicOutputBuffer, readLen)
                                            
                                            if (actualRead > 0) {
                                                val max = callback.maxBufferSize
                                                var offset = 0
                                                while (offset < actualRead) {
                                                    if (!isActive) break
                                                    val len = min(actualRead - offset, max)
                                                    
                                                    // Send to System
                                                    val ret = callback.audioAvailable(sonicOutputBuffer, offset, len)
                                                    if (ret == TextToSpeech.ERROR) {
                                                        AppLogger.error("[$reqId] AudioTrack Error")
                                                        this@launch.cancel()
                                                        break
                                                    }
                                                    offset += len
                                                }
                                            }
                                            available = sonic.samplesAvailable()
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            AppLogger.error("[$reqId] Reader Loop Error", e)
                        } finally {
                            if (!isActive) try { writerJob.join() } catch (e: Exception) {}
                        }
                    }
                    
                    if (isActive) {
                        callback.done()
                        AppLogger.log("[$reqId] Done.")
                    }

                } catch (e: Exception) {
                    AppLogger.error("[$reqId] Critical Error", e)
                }
            }
            synthesisJob?.join()
        }
    }
    
    // Standard Helpers...
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
        } catch (e: Exception) { AppLogger.error("InitTTS Failed", e) }
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

