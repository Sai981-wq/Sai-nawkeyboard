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
    private val processorCache = HashMap<Int, AudioProcessor>()

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("SVC: onCreate - Initializing Engines...")
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val def = getDefaultEngineFallback()
            
            shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), def)
            initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it; AppLogger.log("SVC: Shan Engine Ready ($shanPkg)") }

            burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), def)
            initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it; AppLogger.log("SVC: Burmese Engine Ready ($burmesePkg)") }

            englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), def)
            initTTS(englishPkg, Locale.US) { englishEngine = it; AppLogger.log("SVC: English Engine Ready ($englishPkg)") }
        } catch (e: Exception) { AppLogger.error("SVC: Initialization Failed", e) }
    }

    override fun onStop() { 
        AppLogger.log("SVC: onStop requested.")
        isStopped.set(true) 
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        val reqId = UUID.randomUUID().toString().substring(0, 4)
        isStopped.set(false)

        val text = request.charSequenceText.toString()
        val chunks = TTSUtils.splitHelper(text)
        AppLogger.log("[$reqId] START: Chunks=${chunks.size}, TextLength=${text.length}")

        val speed = (request.speechRate / 100.0f).coerceIn(0.1f, 4.0f)
        val pitch = (request.pitch / 100.0f).coerceIn(0.5f, 2.0f)
        callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

        val inBuf = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
        val readArr = ByteArray(32768)
        val writeArr = ByteArray(65536)

        for ((idx, chunk) in chunks.withIndex()) {
            if (isStopped.get()) { AppLogger.log("[$reqId] STOPPED: Interrupted during chunk $idx"); break }
            
            val engine = when(chunk.lang) { "SHAN" -> shanEngine; "MYANMAR" -> burmeseEngine; else -> englishEngine }
            if (engine == null) { AppLogger.error("[$reqId] ERROR: No engine for ${chunk.lang}"); continue }

            val pkg = when(chunk.lang) { "SHAN" -> shanPkg; "MYANMAR" -> burmesePkg; else -> englishPkg }
            val hz = prefs.getInt("RATE_$pkg", 22050).let { if (it < 8000) 22050 else it }
            
            AppLogger.log("[$reqId] CHUNK $idx: Lang=${chunk.lang}, Hz=$hz, Text='${chunk.text.take(20)}...'")

            val proc = processorCache.getOrPut(hz) { AudioProcessor(hz, 1).apply { init() } }
            proc.reset()
            proc.setSpeed(speed)
            proc.setPitch(pitch)

            val pipe = ParcelFileDescriptor.createPipe()
            val startTime = System.currentTimeMillis()

            Thread {
                try { 
                    engine.synthesizeToFile(chunk.text, Bundle(), pipe[1], UUID.randomUUID().toString()) 
                } catch (e: Exception) {
                    AppLogger.error("[$reqId] ENGINE WRITE ERROR", e)
                } finally { 
                    try { pipe[1].close() } catch (e: Exception) {} 
                }
            }.start()

            var totalBytesRead = 0
            var totalBytesOut = 0

            try {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { fis ->
                    val head = ByteArray(44)
                    var hRead = 0
                    while (hRead < 44) {
                        val c = fis.read(head, hRead, 44 - hRead)
                        if (c < 0) break
                        hRead += c
                    }

                    while (!isStopped.get()) {
                        val r = fis.read(readArr)
                        if (r <= 0) break
                        totalBytesRead += r
                        
                        inBuf.clear(); inBuf.put(readArr, 0, r); inBuf.flip()
                        outBuf.clear()
                        
                        val p = proc.process(inBuf, r, outBuf, outBuf.capacity())
                        if (p > 0) {
                            totalBytesOut += p
                            outBuf.get(writeArr, 0, p)
                            var offset = 0
                            while (offset < p) {
                                val len = min(p - offset, callback.maxBufferSize)
                                callback.audioAvailable(writeArr, offset, len)
                                offset += len
                            }
                        }
                    }
                }
                
                proc.flushQueue()
                var f: Int
                do {
                    outBuf.clear()
                    f = proc.process(null, 0, outBuf, outBuf.capacity())
                    if (f > 0) {
                        totalBytesOut += f
                        outBuf.get(writeArr, 0, f)
                        callback.audioAvailable(writeArr, 0, f)
                    }
                } while (f > 0)
                
                val duration = System.currentTimeMillis() - startTime
                AppLogger.log("[$reqId] CHUNK $idx DONE: Read=$totalBytesRead, Out=$totalBytesOut, Time=${duration}ms")

            } catch (e: Exception) { 
                AppLogger.error("[$reqId] READER ERROR at chunk $idx", e) 
            }
        }
        AppLogger.log("[$reqId] FINISHED.")
        callback.done()
    }

    private fun getDefaultEngineFallback(): String {
        val tts = TextToSpeech(this, null)
        val p = tts.defaultEngine; tts.shutdown(); return p ?: "com.google.android.tts"
    }

    private fun getPkg(k: String, l: List<String>, d: String): String {
        val p = prefs.getString(k, "")
        if (!p.isNullOrEmpty() && isInstalled(p)) return p
        return l.firstOrNull { isInstalled(it) } ?: d
    }

    private fun isInstalled(p: String): Boolean = try { packageManager.getPackageInfo(p, 0); true } catch (e: Exception) { false }

    private fun initTTS(p: String, l: Locale, ready: (TextToSpeech) -> Unit) {
        var t: TextToSpeech? = null
        t = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) { 
                t?.language = l
                ready(t!!) 
            } else {
                AppLogger.error("SVC: Failed to init engine $p")
            }
        }, p)
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    
    override fun onDestroy() {
        super.onDestroy()
        AppLogger.log("SVC: onDestroy - Cleaning up...")
        processorCache.forEach { it.value.release() }
        processorCache.clear()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

