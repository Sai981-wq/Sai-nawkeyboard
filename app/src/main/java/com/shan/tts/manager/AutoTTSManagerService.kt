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
    private val TARGET_HZ = 24000
    private val START_THRESHOLD = 4096 

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var currentSessionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service Created")
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
        AppLogger.log("Service Stopped")
        currentSessionJob?.cancel()
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

        AppLogger.log("[$reqId] New Request: '${text.take(20)}...' Speed=$finalSpeed")

        runBlocking(sessionJob) {
            try {
                callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

                val inputBuffer = ByteBuffer.allocateDirect(32768).order(ByteOrder.LITTLE_ENDIAN)
                val outputBuffer = ByteBuffer.allocateDirect(32768).order(ByteOrder.LITTLE_ENDIAN)
                val chunkReadBuffer = ByteArray(8192)
                val chunkWriteBuffer = ByteArray(32768)

                for (chunk in chunks) {
                    if (!isActive) break
                    if (chunk.text.isBlank()) continue

                    val engine = when (chunk.lang) {
                        "SHAN" -> shanEngine
                        "MYANMAR" -> burmeseEngine
                        else -> englishEngine
                    } ?: englishEngine

                    if (engine == null) {
                        AppLogger.log("[$reqId] Engine null for ${chunk.lang}")
                        continue
                    }

                    val currentPkg = when (chunk.lang) {
                        "SHAN" -> shanPkg
                        "MYANMAR" -> burmesePkg
                        else -> englishPkg
                    }
                    
                    var engineHz = prefs.getInt("RATE_$currentPkg", 0)
                    if (engineHz <= 0) engineHz = 22050 

                    AppLogger.log("[$reqId] Processing Chunk: ${chunk.lang} ($engineHz Hz)")

                    val processor = AudioProcessor(engineHz, 1)
                    processor.setSpeed(finalSpeed)
                    processor.setPitch(finalPitch)

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
                            ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                                var isBuffering = true 

                                while (isActive) {
                                    val bytesRead = try { fis.read(chunkReadBuffer) } catch (e: IOException) { -1 }
                                    if (bytesRead == -1) {
                                        AppLogger.log("[$reqId] Pipe End Reached")
                                        break
                                    }
                                    
                                    if (bytesRead > 0) {
                                        inputBuffer.clear()
                                        inputBuffer.put(chunkReadBuffer, 0, bytesRead)
                                        inputBuffer.flip() 
                                        
                                        processor.writeInput(inputBuffer, bytesRead)
                                        val available = processor.availableBytes()

                                        if (isBuffering) {
                                            if (available >= START_THRESHOLD) {
                                                isBuffering = false
                                                AppLogger.log("[$reqId] Threshold Met ($available/$START_THRESHOLD). Playing...")
                                            } else {
                                                // Log every few reads to avoid spam, or check specific conditions
                                                if (available % 1000 < 200) { 
                                                    AppLogger.log("[$reqId] Buffering... ($available/$START_THRESHOLD)")
                                                }
                                                continue 
                                            }
                                        }

                                        while (isActive) {
                                            outputBuffer.clear()
                                            val processedBytes = processor.readOutput(outputBuffer)
                                            
                                            if (processedBytes > 0) {
                                                outputBuffer.flip()
                                                outputBuffer.get(chunkWriteBuffer, 0, processedBytes)
                                                
                                                var offset = 0
                                                while (offset < processedBytes) {
                                                    if (!isActive) break
                                                    val len = min(processedBytes - offset, callback.maxBufferSize)
                                                    
                                                    val ret = callback.audioAvailable(chunkWriteBuffer, offset, len)
                                                    if (ret == TextToSpeech.ERROR) {
                                                        AppLogger.error("[$reqId] AudioTrack Error")
                                                        cancel()
                                                        break
                                                    }
                                                    offset += len
                                                }
                                            } else {
                                                break 
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (isActive) {
                            processor.flushQueue()
                            var flushedBytes: Int
                            do {
                                if (!isActive) break
                                outputBuffer.clear()
                                flushedBytes = processor.readOutput(outputBuffer)
                                
                                if (flushedBytes > 0) {
                                    outputBuffer.flip()
                                    outputBuffer.get(chunkWriteBuffer, 0, flushedBytes)
                                    var offset = 0
                                    while (offset < flushedBytes) {
                                        if (!isActive) break
                                        val len = min(flushedBytes - offset, callback.maxBufferSize)
                                        callback.audioAvailable(chunkWriteBuffer, offset, len)
                                        offset += len
                                    }
                                }
                            } while (flushedBytes > 0)
                        }

                    } finally {
                        processor.release()
                        writerJob.cancelAndJoin()
                    }
                }
            } catch (e: Exception) {
                AppLogger.error("Synthesis Error", e)
            } finally {
                if (isActive) {
                    callback.done()
                    AppLogger.log("[$reqId] Done")
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

