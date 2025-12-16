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
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class AutoTTSManagerService : TextToSpeechService() {
    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null
    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""
    private val controllerExecutor = Executors.newSingleThreadExecutor()
    private val pipeExecutor = Executors.newCachedThreadPool()
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    private lateinit var configPrefs: SharedPreferences
    private lateinit var settingsPrefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        settingsPrefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
        shanPkgName = settingsPrefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
        initEngine(shanPkgName) { shanEngine = it }
        burmesePkgName = settingsPrefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
        initEngine(burmesePkgName) { it.language = Locale("my", "MM"); burmeseEngine = it }
        englishPkgName = settingsPrefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
        initEngine(englishPkgName) { it.language = Locale.US; englishEngine = it }
    }

    private fun initEngine(pkg: String, onSuccess: (TextToSpeech) -> Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(applicationContext, { if (it == TextToSpeech.SUCCESS) onSuccess(tts!!) }, pkg)
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        isStopped.set(false)
        currentTask?.cancel(true)
        currentTask = controllerExecutor.submit {
            val chunks = LanguageUtils.splitHelper(text)
            callback?.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
            var currentEngineRate = 0
            for (chunk in chunks) {
                if (isStopped.get()) break
                val engine = getEngine(chunk.lang) ?: continue
                val pkg = getPkgName(chunk.lang)
                val (rate, pitch) = getRateAndPitch(chunk.lang, request)
                engine.setSpeechRate(rate)
                engine.setPitch(pitch)
                var eRate = configPrefs.getInt("RATE_$pkg", 24000)
                if (eRate != currentEngineRate) {
                    AudioProcessor.flush()
                    AudioProcessor.initSonic(eRate, 1)
                    currentEngineRate = eRate
                }
                AudioProcessor.setConfig(1.0f, 1.0f)
                processPipe(engine, chunk.text, callback!!)
            }
            callback?.done()
        }
    }

    private fun processPipe(engine: TextToSpeech, text: String, callback: SynthesisCallback) {
        val pipe = ParcelFileDescriptor.createPipe()
        pipeExecutor.submit {
            val fis = FileInputStream(pipe[0].fileDescriptor)
            val buffer = ByteArray(4096)
            var leftover: Byte? = null
            try {
                var read = fis.read(buffer)
                while (read != -1 && !isStopped.get()) {
                    var data = buffer
                    var len = read
                    if (leftover != null) {
                        val next = ByteArray(read + 1)
                        next[0] = leftover!!
                        System.arraycopy(buffer, 0, next, 1, read)
                        data = next; len = read + 1; leftover = null
                    }
                    if (len % 2 != 0) { leftover = data[len - 1]; len-- }
                    if (len > 0) {
                        val out = AudioProcessor.processAudio(data, len)
                        if (out.isNotEmpty()) callback.audioAvailable(out, 0, out.size)
                    }
                    read = fis.read(buffer)
                }
            } catch (e: Exception) {} finally { fis.close(); pipe[0].close() }
        }
        engine.synthesizeToFile(text, null, pipe[1], UUID.randomUUID().toString())
        pipe[1].close()
    }

    private fun getEngine(l: String) = if (l == "SHAN") shanEngine else if (l == "MYANMAR") burmeseEngine else englishEngine
    private fun getPkgName(l: String) = if (l == "SHAN") shanPkgName else if (l == "MYANMAR") burmesePkgName else englishPkgName
    private fun getRateAndPitch(l: String, r: SynthesisRequest?): Pair<Float, Float> = Pair(1.0f, 1.0f)
    override fun onStop() { isStopped.set(true); AudioProcessor.flush() }
    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

