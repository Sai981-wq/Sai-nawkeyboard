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
    private var sharedProcessor: AudioProcessor? = null
    private var currentProcessorHz = 0

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val def = getDefaultEngineFallback()
        shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), def)
        initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it }
        burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), def)
        initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }
        englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), def)
        initTTS(englishPkg, Locale.US) { englishEngine = it }
    }

    override fun onStop() {
        isStopped.set(true)
        shanEngine?.stop(); burmeseEngine?.stop(); englishEngine?.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        isStopped.set(false)
        val chunks = TTSUtils.splitHelper(request.charSequenceText.toString())
        if (chunks.isEmpty()) { callback.done(); return }

        val speed = (request.speechRate / 100.0f).coerceIn(0.1f, 4.0f)
        val pitch = (request.pitch / 100.0f).coerceIn(0.5f, 2.0f)
        callback.start(TARGET_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

        val inBuf = ByteBuffer.allocateDirect(32768).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.allocateDirect(32768).order(ByteOrder.LITTLE_ENDIAN)
        val tempRead = ByteArray(32768)
        val tempWrite = ByteArray(32768)

        for (chunk in chunks) {
            if (isStopped.get()) break
            val engine = when(chunk.lang) { "SHAN" -> shanEngine; "MYANMAR" -> burmeseEngine; else -> englishEngine } ?: englishEngine ?: continue
            val pkg = when(chunk.lang) { "SHAN" -> shanPkg; "MYANMAR" -> burmesePkg; else -> englishPkg }
            
            var hz = prefs.getInt("RATE_$pkg", 22050)
            if (hz < 8000) hz = 22050

            if (sharedProcessor == null || currentProcessorHz != hz) {
                sharedProcessor?.release()
                sharedProcessor = AudioProcessor(hz, 1).apply { init() }
                currentProcessorHz = hz
            }
            sharedProcessor?.setSpeed(speed)
            sharedProcessor?.setPitch(pitch)

            val pipe = ParcelFileDescriptor.createPipe()
            val uuid = UUID.randomUUID().toString()
            
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                try { engine.synthesizeToFile(chunk.text, Bundle(), pipe[1], uuid) } 
                finally { try { pipe[1].close() } catch(e: Exception) {} }
            }.start()

            try {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { fis ->
                    val header = ByteArray(44)
                    var hRead = 0
                    while (hRead < 44) {
                        val c = fis.read(header, hRead, 44 - hRead)
                        if (c < 0) break
                        hRead += c
                    }

                    while (!isStopped.get()) {
                        val read = fis.read(tempRead)
                        if (read <= 0) break
                        inBuf.clear(); inBuf.put(tempRead, 0, read); inBuf.flip()
                        
                        outBuf.clear()
                        val processed = sharedProcessor?.process(inBuf, read, outBuf, outBuf.capacity()) ?: 0
                        if (processed > 0) {
                            outBuf.flip(); outBuf.get(tempWrite, 0, processed)
                            var offset = 0
                            while (offset < processed) {
                                val len = kotlin.math.min(processed - offset, callback.maxBufferSize)
                                callback.audioAvailable(tempWrite, offset, len)
                                offset += len
                            }
                        }
                    }
                }
                sharedProcessor?.flushQueue()
                var fRead: Int
                do {
                    outBuf.clear()
                    fRead = sharedProcessor?.process(null, 0, outBuf, outBuf.capacity()) ?: 0
                    if (fRead > 0) {
                        outBuf.flip(); outBuf.get(tempWrite, 0, fRead)
                        callback.audioAvailable(tempWrite, 0, fRead)
                    }
                } while (fRead > 0)
            } catch (e: Exception) { AppLogger.error("Stream Error", e) }
        }
        callback.done()
    }

    private fun getDefaultEngineFallback(): String {
        val tts = TextToSpeech(this, null)
        val p = tts.defaultEngine; tts.shutdown(); return p ?: "com.google.android.tts"
    }

    private fun getPkg(key: String, list: List<String>, def: String): String {
        val p = prefs.getString(key, "")
        if (!p.isNullOrEmpty() && isInstalled(p)) return p
        return list.firstOrNull { isInstalled(it) } ?: def
    }

    private fun isInstalled(pkg: String): Boolean = try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun initTTS(pkg: String, loc: Locale, onReady: (TextToSpeech) -> Unit) {
        if (pkg.isEmpty()) return
        var t: TextToSpeech? = null
        t = TextToSpeech(this, { if (it == TextToSpeech.SUCCESS) { t?.language = loc; onReady(t!!) } }, pkg)
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onDestroy() {
        super.onDestroy()
        sharedProcessor?.release()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

