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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private var currentReadFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service onCreate Started ===")
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defEngine = getDefaultEngineFallback()

            shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defEngine)
            initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it }

            burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defEngine)
            initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }

            englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defEngine)
            initTTS(englishPkg, Locale.US) { englishEngine = it }
            
            AppLogger.log("Service Initialized. Shan: $shanPkg, Bur: $burmesePkg, Eng: $englishPkg")
        } catch (e: Exception) { 
            AppLogger.error("Error in onCreate", e)
        }
    }

    override fun onStop() {
        AppLogger.log("=== onStop Called ===")
        isStopped = true
        try { 
            currentReadFd?.close() 
            AppLogger.log("Closed currentReadFd")
        } catch (e: Exception) {
            AppLogger.error("Error closing ReadFd", e)
        }
        currentReadFd = null
        
        stopSafe(shanEngine, "Shan")
        stopSafe(burmeseEngine, "Burmese")
        stopSafe(englishEngine, "English")
    }

    private fun stopSafe(engine: TextToSpeech?, name: String) {
        try { 
            engine?.stop() 
            AppLogger.log("Stopped engine: $name")
        } catch (e: Exception) {
            AppLogger.error("Failed to stop $name", e)
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            AppLogger.error("Request or Callback is NULL")
            return
        }
        val reqId = UUID.randomUUID().toString().substring(0, 5)
        AppLogger.log("[$reqId] onSynthesizeText Start. TextLen: ${request.charSequenceText.length}")
        
        isStopped = false
        val text = request.charSequenceText.toString()

        val chunks = TTSUtils.splitHelper(text)
        if (chunks.isEmpty()) {
            AppLogger.log("[$reqId] No chunks found. Done.")
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
        AppLogger.log("[$reqId] Config - Hz: $hz, Mult: $engineMult, Pkg: $targetPkg")

        var rawRate = request.speechRate / 100.0f
        var safeRate = if (rawRate < 0.35f) 0.35f else rawRate
        if (safeRate > 3.0f) safeRate = 3.0f

        val finalRate = safeRate * engineMult
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.7f, 1.4f)
        
        AppLogger.log("[$reqId] Rate - Raw: $rawRate, Safe: $safeRate, Final: $finalRate")

        try {
            callback.start(hz, AudioFormat.ENCODING_PCM_16BIT, 1)

            val pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]
            val uuid = UUID.randomUUID().toString()
            currentReadFd = readFd
            
            AppLogger.log("[$reqId] Pipe created. Starting Writer Thread...")

            val writerThread = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                
                try {
                    for ((index, chunk) in chunks.withIndex()) {
                        if (isStopped) {
                            AppLogger.log("[$reqId] Writer stopped by flag")
                            break
                        }
                        if (chunk.text.isBlank()) continue

                        val engine = when (chunk.lang) {
                            "SHAN" -> shanEngine
                            "MYANMAR" -> burmeseEngine
                            else -> englishEngine
                        } ?: englishEngine

                        if (engine == null) {
                            AppLogger.error("[$reqId] Engine null for chunk $index")
                            continue
                        }

                        val currentPkg = when (chunk.lang) {
                            "SHAN" -> shanPkg
                            "MYANMAR" -> burmesePkg
                            else -> englishPkg
                        }
                        
                        val chunkMult = prefs.getFloat("MULT_$currentPkg", 1.0f)
                        val chunkRate = (safeRate * chunkMult).coerceIn(0.2f, 3.5f)

                        AppLogger.log("[$reqId] Processing Chunk $index (${chunk.lang}): Rate=$chunkRate")
                        
                        engine.setSpeechRate(chunkRate)
                        engine.setPitch(finalPitch)

                        val latch = CountDownLatch(1)
                        
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {
                                // AppLogger.log("[$reqId] Engine Started utterance")
                            }
                            override fun onDone(id: String?) { 
                                // AppLogger.log("[$reqId] Engine Done")
                                latch.countDown() 
                            }
                            override fun onError(id: String?) { 
                                AppLogger.error("[$reqId] Engine Error")
                                latch.countDown() 
                            }
                            override fun onError(id: String?, code: Int) { 
                                AppLogger.error("[$reqId] Engine Error Code: $code")
                                latch.countDown() 
                            }
                        })

                        val res = engine.synthesizeToFile(chunk.text, params, writeFd, uuid)
                        
                        if (res == TextToSpeech.SUCCESS) {
                            AppLogger.log("[$reqId] Waiting for latch (Chunk $index)...")
                            val finished = latch.await(5000, TimeUnit.MILLISECONDS)
                            if (!finished) AppLogger.error("[$reqId] Latch TIMEOUT for Chunk $index")
                            else AppLogger.log("[$reqId] Chunk $index finished normally")
                        } else {
                            AppLogger.error("[$reqId] synthesizeToFile FAILED for Chunk $index")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.error("[$reqId] Writer Thread Exception", e)
                } finally {
                    try { 
                        writeFd.close() 
                        AppLogger.log("[$reqId] WriteFD Closed")
                    } catch (e: Exception) {
                        AppLogger.error("[$reqId] Error closing WriteFD", e)
                    }
                }
            }
            writerThread.start()

            // Increased Buffer to 16KB
            val buffer = ByteArray(16384) 
            var totalBytesRead = 0
            
            AppLogger.log("[$reqId] Starting Reader Loop...")

            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (!isStopped) {
                        val bytesRead = try { fis.read(buffer) } catch (e: IOException) { 
                            AppLogger.error("[$reqId] Reader IO Exception", e)
                            -1 
                        }
                        
                        if (bytesRead == -1) {
                            AppLogger.log("[$reqId] Reader: EOF detected (Writer done)")
                            break 
                        }
                        
                        if (bytesRead > 0) {
                            totalBytesRead += bytesRead
                            // Only log every 50KB to avoid spam, or log if necessary
                            // AppLogger.log("[$reqId] Read $bytesRead bytes") 

                            val max = callback.maxBufferSize
                            var offset = 0
                            while (offset < bytesRead) {
                                if (isStopped) break
                                val chunkLen = Math.min(bytesRead - offset, max)
                                val ret = callback.audioAvailable(buffer, offset, chunkLen)
                                if (ret == TextToSpeech.ERROR) {
                                    AppLogger.error("[$reqId] AudioTrack rejected data")
                                    isStopped = true
                                    break
                                }
                                offset += chunkLen
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.error("[$reqId] Reader Loop Critical Error", e)
            } finally {
                AppLogger.log("[$reqId] Reader Loop Ended. Total Bytes: $totalBytesRead")
                if (writerThread.isAlive) {
                   AppLogger.log("[$reqId] Stopping engines forcefully...")
                   stopSafe(shanEngine, "Shan")
                   stopSafe(burmeseEngine, "Burmese")
                   stopSafe(englishEngine, "English")
                }
                if (!isStopped) {
                    callback.done()
                    AppLogger.log("[$reqId] Callback Done sent")
                }
            }

        } catch (e: Exception) { 
            AppLogger.error("[$reqId] Critical Setup Error", e) 
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
                    AppLogger.log("Init TTS Success: $pkg")
                    onReady(t!!) 
                } else {
                    AppLogger.error("Init TTS Failed: $pkg")
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.error("Init TTS Exception: $pkg", e)
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

