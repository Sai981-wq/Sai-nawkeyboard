package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
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
    private lateinit var configPrefs: SharedPreferences

    private val stopped = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)

        val defaultEngine = try {
            val tts = TextToSpeech(this, null)
            val p = tts.defaultEngine ?: "com.google.android.tts"
            tts.shutdown()
            p
        } catch (e: Exception) {
            "com.google.android.tts"
        }

        shanPkg = resolveEngine("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defaultEngine)
        burmesePkg = resolveEngine("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defaultEngine)
        englishPkg = resolveEngine("pref_english_pkg", listOf("com.google.android.tts"), defaultEngine)

        initEngine(shanPkg, Locale("shn")) { shanEngine = it }
        initEngine(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }
        initEngine(englishPkg, Locale.US) { englishEngine = it }
    }

    private fun resolveEngine(key: String, list: List<String>, fallback: String): String {
        val user = prefs.getString(key, "") ?: ""
        if (user.isNotEmpty() && isInstalled(user)) return user
        for (p in list) if (isInstalled(p)) return p
        return fallback
    }

    private fun isInstalled(pkg: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun initEngine(pkg: String, locale: Locale, cb: (TextToSpeech) -> Unit) {
        try {
            TextToSpeech(this, { s ->
                if (s == TextToSpeech.SUCCESS) {
                    cb(it.language(locale))
                }
            }, pkg)
        } catch (_: Exception) {
        }
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("eng", "US", "")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return when (lang) {
            "shn" -> if (shanEngine != null) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
            "my", "mya", "bur" -> if (burmeseEngine != null) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
            else -> if (englishEngine != null) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onStop() {
        stopped.set(true)
        AudioProcessor.flush()
    }

    override fun onSynthesizeText(req: SynthesisRequest?, cb: SynthesisCallback?) {
        if (req == null || cb == null) return
        stopped.set(false)

        val text = req.charSequenceText.toString()
        val rate = req.speechRate / 100f
        val pitch = req.pitch / 100f

        cb.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)

        val parts = TTSUtils.splitHelper(text)
        for (p in parts) {
            if (stopped.get()) break
            val data = engineForLang(p.lang)
            val engine = data.first ?: continue

            engine.setSpeechRate(rate)
            engine.setPitch(pitch)

            stream(engine, data.second, p.text, cb)
        }

        if (!stopped.get()) cb.done()
    }

    private fun stream(engine: TextToSpeech, pkg: String, text: String, cb: SynthesisCallback) {
        val rate = configPrefs.getInt("RATE_$pkg", 22050)
        AudioProcessor.initSonic(rate, 1)

        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        executor.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = ByteArray(4096)
            val inBuf = ByteBuffer.allocateDirect(4096)
            val outBuf = ByteBuffer.allocateDirect(8192)
            val outArr = ByteArray(8192)

            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { input ->
                    while (!stopped.get()) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        inBuf.clear()
                        inBuf.put(buf, 0, r)
                        inBuf.flip()

                        var out = AudioProcessor.processAudio(inBuf, r, outBuf, outBuf.capacity())
                        while (out > 0 && !stopped.get()) {
                            outBuf.get(outArr, 0, out)
                            send(outArr, out, cb)
                            outBuf.clear()
                            out = AudioProcessor.processAudio(inBuf, 0, outBuf, outBuf.capacity())
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        engine.synthesizeToFile(text, Bundle(), writeFd, UUID.randomUUID().toString())
        try {
            writeFd.close()
        } catch (_: IOException) {
        }
    }

    private fun send(buf: ByteArray, len: Int, cb: SynthesisCallback) {
        var off = 0
        val max = cb.maxBufferSize
        while (off < len && !stopped.get()) {
            val n = min(len - off, max)
            cb.audioAvailable(buf, off, n)
            off += n
        }
    }

    private fun engineForLang(lang: String): Pair<TextToSpeech?, String> {
        return when (lang) {
            "SHAN", "shn" -> Pair(shanEngine ?: englishEngine, shanPkg)
            "MYANMAR", "my", "mya", "bur" -> Pair(burmeseEngine ?: englishEngine, burmesePkg)
            else -> Pair(englishEngine, englishPkg)
        }
    }

    override fun onDestroy() {
        stopped.set(true)
        executor.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        AudioProcessor.stop()
        super.onDestroy()
    }
}
