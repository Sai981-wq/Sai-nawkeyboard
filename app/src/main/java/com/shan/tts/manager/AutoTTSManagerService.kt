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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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

    private val isStopped = AtomicBoolean(false)
    
    @Volatile private var sharedProcessor: AudioProcessor? = null
    @Volatile private var currentProcessorHz = 0

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defEngine = getDefaultEngineFallback()

            shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defEngine)
            initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it }

            burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defEngine)
            initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }

            englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defEngine)
            initTTS(englishPkg, Locale.US) { englishEngine = it }
        } catch (e: Exception) { AppLogger.error("onCreate Error", e) }
    }

    override fun onStop() {
        isStopped.set(true)
        shanEngine?.stop()
        burmeseEngine?.stop()
        englishEngine?.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        
        isStopped.set(false)
        val text = request.charSequenceText.toString()
        val chunks = TTSUtils.splitHelper(text)
        if (chunks.isEmpty()) {
            callback.done()
            return
        }

        val finalSpeed = (request.speechRate / 100.0f).coerceIn(0.1f, 6.0f)
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.5f, 2.0f)

        try {
            callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

            val inputBuffer = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
            val outputBuffer = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
            val chunkReadBuffer = ByteArray(32768)
            val chunkWriteBuffer = ByteArray(65536)

            for (chunk in chunks) {
                if (isStopped.get()) break
                if (chunk.text.isBlank()) continue

                val engine = when (chunk.lang) {
                    "SHAN" -> shanEngine
                    "MYANMAR" -> burmeseEngine
                    else -> englishEngine
                } ?: englishEngine

                if (engine == null) {
                    AppLogger.log("Engine not ready for: ${chunk.lang}")
                    continue
                }

                val currentPkg = when (chunk.lang) {
                    "SHAN" -> shanPkg
                    "MYANMAR" -> burmesePkg
                    else -> englishPkg
                }
                
                var engineHz = prefs.getInt("RATE_$currentPkg", 22050)
                if (engineHz <= 0) engineHz = 22050 

                if (sharedProcessor == null || currentProcessorHz != engineHz) {
                    sharedProcessor?.release()
                    sharedProcessor = AudioProcessor(engineHz, 1).apply { init() }
                    currentProcessorHz = engineHz
                }
                
                sharedProcessor?.setSpeed(finalSpeed)
                sharedProcessor?.setPitch(finalPitch)

                val pipe = ParcelFileDescriptor.createPipe()
                val writeFd = pipe[1]
                val readFd = pipe[0]

                Thread {
                    try {
                        engine.synthesizeToFile(chunk.text, Bundle(), writeFd, UUID.randomUUID().toString())
                    } catch (e: Exception) {
                        AppLogger.error("Synthesis Writing Error", e)
                    } finally {
                        try { writeFd.close() } catch (e: Exception) {}
                    }
                }.start()

                try {
                    ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                        val headerDiscard = ByteArray(44)
                        var hRead = 0
                        while (hRead < 44) {
                            val c = fis.read(headerDiscard, hRead, 44 - hRead)
                            if (c < 0) break
                            hRead += c
                        }

                        while (!isStopped.get()) {
                            val bytesRead = try { fis.read(chunkReadBuffer) } catch (e: IOException) { -1 }
                            if (bytesRead <= 0) break

                            inputBuffer.clear()
                            inputBuffer.put(chunkReadBuffer, 0, bytesRead)
                            inputBuffer.flip() 
                            
                            outputBuffer.clear()
                            val processedBytes = sharedProcessor?.process(inputBuffer, bytesRead, outputBuffer, outputBuffer.capacity()) ?: 0
                            
                            if (processedBytes > 0) {
                                // outputBuffer is already flipped in AudioProcessor.process()
                                outputBuffer.get(chunkWriteBuffer, 0, processedBytes)
                                var offset = 0
                                while (offset < processedBytes) {
                                    if (isStopped.get()) break
                                    val len = min(processedBytes - offset, callback.maxBufferSize)
                                    callback.audioAvailable(chunkWriteBuffer, offset, len)
                                    offset += len
                                }
                            }
                        }
                    }
                    
                    // Flush remaining audio in Sonic for this chunk
                    sharedProcessor?.flushQueue()
                    var fBytes: Int
                    do {
                        outputBuffer.clear()
                        fBytes = sharedProcessor?.process(null, 0, outputBuffer, outputBuffer.capacity()) ?: 0
                        if (fBytes > 0) {
                            outputBuffer.get(chunkWriteBuffer, 0, fBytes)
                            callback.audioAvailable(chunkWriteBuffer, 0, fBytes)
                        }
                    } while (fBytes > 0)

                } catch (e: Exception) {
                    AppLogger.error("Chunk Processing Error", e)
                }
            }
        } catch (e: Exception) { 
            AppLogger.error("Synthesize Final Error", e) 
        } finally {
            callback.done()
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
        var ttsInstance: TextToSpeech? = null
        ttsInstance = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInstance?.let {
                    it.language = loc
                    onReady(it)
                }
            }
        }, pkg)
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    
    override fun onDestroy() {
        super.onDestroy()
        sharedProcessor?.release()
        sharedProcessor = null
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

