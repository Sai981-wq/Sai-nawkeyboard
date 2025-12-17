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
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AutoTTSManagerService : TextToSpeechService() {
    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var configPrefs: SharedPreferences

    private val executor = Executors.newSingleThreadExecutor()
    private val isStopped = AtomicBoolean(false)

    private val OUTPUT_RATE = 24000

    override fun onCreate() {
        super.onCreate()
        settingsPrefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)

        initEngine(settingsPrefs.getString("pref_shan_pkg", "com.espeak.ng"), Locale("shn")) { shanEngine = it }
        initEngine(settingsPrefs.getString("pref_burmese_pkg", "com.google.android.tts"), Locale("my", "MM")) { burmeseEngine = it }
        initEngine(settingsPrefs.getString("pref_english_pkg", "com.google.android.tts"), Locale.US) { englishEngine = it }
    }

    private fun initEngine(pkg: String?, locale: Locale, onReady: (TextToSpeech) -> Unit) {
        if (pkg.isNullOrEmpty()) return
        
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(applicationContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                try { tts.language = locale } catch (_: Exception) {}
                onReady(tts)
            }
        }, pkg)
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (callback == null || request == null) return
        stopAll()
        isStopped.set(false)

        executor.execute {
            val chunks = LanguageUtils.splitHelper(request.charSequenceText.toString())
            
            synchronized(callback) {
                callback.start(OUTPUT_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
            }

            for (chunk in chunks) {
                if (isStopped.get()) break
                val engine = getEngine(chunk.lang) ?: continue
                val pkg = getPkg(chunk.lang)
                
                val inputRate = configPrefs.getInt("RATE_$pkg", 0).takeIf { it > 0 } ?: fallbackRate(pkg)

                AudioProcessor.initSonic(inputRate, 1)

                val params = Bundle()
                val id = UUID.randomUUID().toString()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)

                process(engine, params, chunk.text, callback, id)
            }
            callback.done()
        }
    }

    private fun process(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, id: String) {
        var pipe: Array<ParcelFileDescriptor>? = null
        try {
            pipe = ParcelFileDescriptor.createPipe()
            val reader = pipe[0]
            val writer = pipe[1]

            val result = engine.synthesizeToFile(text, params, writer, id)
            
            writer.close() 

            if (result == TextToSpeech.SUCCESS) {
                FileInputStream(reader.fileDescriptor).use { fis ->
                    val buf = ByteArray(8192)
                    while (!isStopped.get()) {
                        val r = fis.read(buf)
                        if (r == -1) break
                        if (r > 0) {
                            val out = AudioProcessor.processAudio(buf, r)
                            if (out.isNotEmpty()) {
                                synchronized(callback) {
                                    callback.audioAvailable(out, 0, out.size)
                                }
                            }
                        }
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        AudioProcessor.flush()
    }

    private fun stopAll() {
        isStopped.set(true)
        AudioProcessor.flush()
    }

    private fun fallbackRate(pkg: String): Int {
        val p = pkg.lowercase(Locale.ROOT)
        return when {
            p.contains("eloquence") -> 11025
            p.contains("espeak") || p.contains("shan") -> 22050
            else -> 24000
        }
    }

    private fun getEngine(lang: String) = when (lang) {
        "SHAN" -> shanEngine ?: englishEngine
        "MYANMAR" -> burmeseEngine ?: englishEngine
        else -> englishEngine
    }

    private fun getPkg(lang: String) = when (lang) {
        "SHAN" -> settingsPrefs.getString("pref_shan_pkg", "com.espeak.ng")!!
        "MYANMAR" -> settingsPrefs.getString("pref_burmese_pkg", "com.google.android.tts")!!
        else -> settingsPrefs.getString("pref_english_pkg", "com.google.android.tts")!!
    }

    override fun onStop() { stopAll() }

    override fun onDestroy() {
        stopAll()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        executor.shutdownNow()
        super.onDestroy()
    }
    
    override fun onGetVoices(): List<Voice> = emptyList()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

