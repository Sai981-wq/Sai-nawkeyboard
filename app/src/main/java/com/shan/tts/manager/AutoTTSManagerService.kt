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
import android.util.Log
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class AutoTTSManagerService : TextToSpeechService() {

    private val TAG = "AutoTTS_Service"
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

    private val OUTPUT_HZ = 24000

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        try {
            settingsPrefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)

            shanPkgName = settingsPrefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName, Locale("shn")) { shanEngine = it }

            burmesePkgName = settingsPrefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = settingsPrefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName, Locale.US) { englishEngine = it }

            AudioProcessor.initSonic(16000, 1)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
        }
    }

    private fun initEngine(pkg: String?, locale: Locale, onSuccess: (TextToSpeech) -> Unit) {
        if (pkg.isNullOrEmpty()) return
        Log.d(TAG, "Initializing Engine: $pkg for locale: $locale")
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "Engine Bound: $pkg")
                    try { tempTTS?.language = locale } catch (e: Exception) {}
                    onSuccess(tempTTS!!)
                    tempTTS?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {}
                        override fun onError(id: String?) {
                            Log.e(TAG, "Engine Error: $pkg")
                        }
                    })
                } else {
                    Log.e(TAG, "Failed to bind: $pkg")
                }
            }, pkg)
        } catch (e: Exception) {
            Log.e(TAG, "Crash initializing $pkg", e)
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        Log.d(TAG, "New Request: $text")

        isStopped.set(true)
        currentTask?.cancel(true)
        AudioProcessor.flush()
        isStopped.set(false)

        currentTask = controllerExecutor.submit {
            try {
                if (callback == null) return@submit
                val rawChunks = LanguageUtils.splitHelper(text)
                if (rawChunks.isEmpty()) {
                    callback.done()
                    return@submit
                }

                synchronized(callback) {
                    callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

                for (chunk in rawChunks) {
                    if (isStopped.get()) {
                        Log.d(TAG, "Task Stopped during loop")
                        break
                    }

                    val engine = getEngine(chunk.lang) ?: continue
                    val activePkg = getPkgName(chunk.lang)

                    var inputRate = configPrefs.getInt("RATE_$activePkg", 0)
                    if (inputRate == 0) inputRate = getFallbackRate(activePkg)

                    Log.d(TAG, "Processing Chunk: [${chunk.lang}] '${chunk.text}' -> Engine: $activePkg ($inputRate Hz)")

                    AudioProcessor.initSonic(inputRate, 1)
                    AudioProcessor.setConfig(1.0f, 1.0f)

                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, getVolumeCorrection(activePkg))
                    val uuid = UUID.randomUUID().toString()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

                    processPipeCpp(engine, params, chunk.text, callback, uuid)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Synthesize Exception", e)
            } finally {
                if (!isStopped.get()) {
                    Log.d(TAG, "Synthesize Done")
                    callback?.done()
                }
            }
        }
    }

    private fun processPipeCpp(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        var lR: ParcelFileDescriptor? = null
        var lW: ParcelFileDescriptor? = null
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            lR = pipe[0]
            lW = pipe[1]
            val fR = lR

            val readerFuture = pipeExecutor.submit {
                var fis: FileInputStream? = null
                try {
                    fis = FileInputStream(fR.fileDescriptor)
                    val buffer = ByteArray(16384)
                    var totalRead = 0

                    while (!isStopped.get() && !Thread.currentThread().isInterrupted) {
                        val read = fis.read(buffer)
                        if (read == -1) break
                        if (read > 0) {
                            totalRead += read
                            val out = AudioProcessor.processAudio(buffer, read)
                            if (out.isNotEmpty()) {
                                synchronized(callback) {
                                    try { callback.audioAvailable(out, 0, out.size) } catch (e: Exception) {
                                        Log.e(TAG, "Audio write failed", e)
                                    }
                                }
                            }
                        }
                    }
                    Log.d(TAG, "Pipe finished. Total read: $totalRead bytes")
                } catch (e: IOException) {
                    Log.e(TAG, "Pipe Read Error", e)
                } finally {
                    try { fis?.close() } catch (e: Exception) {}
                    try { fR?.close() } catch (e: Exception) {}
                }
            }

            engine.synthesizeToFile(text, params, lW!!, uuid)
            try { lW?.close() } catch (e: Exception) {}
            try { readerFuture.get() } catch (e: Exception) {
                Log.e(TAG, "Reader Thread Error", e)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Pipe Setup Error", e)
            try { lR?.close() } catch (e: Exception) {}
            try { lW?.close() } catch (e: Exception) {}
        }
    }

    private fun getFallbackRate(pkg: String): Int {
        val lower = pkg.lowercase(Locale.ROOT)
        return when {
            lower.contains("eloquence") -> 11025
            lower.contains("espeak") || lower.contains("shan") -> 22050
            lower.contains("google") -> 24000
            else -> 16000
        }
    }

    private fun getPkgName(lang: String) = when (lang) {
        "SHAN" -> shanPkgName
        "MYANMAR" -> burmesePkgName
        else -> englishPkgName
    }

    private fun getEngine(lang: String) = when (lang) {
        "SHAN" -> if (shanEngine != null) shanEngine else englishEngine
        "MYANMAR" -> if (burmeseEngine != null) burmeseEngine else englishEngine
        else -> englishEngine
    }

    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return when {
            l.contains("vocalizer") -> 0.85f
            l.contains("eloquence") -> 0.6f
            else -> 1.0f
        }
    }

    override fun onStop() {
        Log.d(TAG, "onStop Called")
        isStopped.set(true)
        currentTask?.cancel(true)
        AudioProcessor.flush()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy Called")
        isStopped.set(true)
        controllerExecutor.shutdownNow()
        pipeExecutor.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        AudioProcessor.flush()
        super.onDestroy()
    }

    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

