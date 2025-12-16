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
    
    // OUTPUT FIXED: 24000Hz (High Quality Upsampling)
    private val OUTPUT_HZ = 24000
    
    override fun onCreate() {
        super.onCreate()
        try {
            settingsPrefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            
            shanPkgName = settingsPrefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName) { shanEngine = it }
            
            burmesePkgName = settingsPrefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName) { 
                it.language = Locale("my", "MM")
                burmeseEngine = it 
            }
            
            englishPkgName = settingsPrefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName) { 
                it.language = Locale.US
                englishEngine = it 
            }
            
            AudioProcessor.initSonic(16000, 1) // Default
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun initEngine(pkg: String?, onSuccess: (TextToSpeech) -> Unit) {
        if (pkg.isNullOrEmpty()) return
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    onSuccess(tempTTS!!)
                    tempTTS?.setOnUtteranceProgressListener(object : UtteranceProgressListener(){
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {}
                        override fun onError(id: String?) {}
                    })
                }
            }, pkg)
        } catch (e: Exception) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
        // Stop previous
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
                    // Always start AudioTrack with 24000Hz
                    callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

                for (chunk in rawChunks) {
                    if (isStopped.get()) break
                    
                    var effectiveLang = chunk.lang
                    if ((effectiveLang == "ENGLISH" || effectiveLang == "ENG") && containsThaiOrShanChar(chunk.text)) {
                        effectiveLang = "SHAN"
                    }

                    val engine = getEngine(effectiveLang) ?: continue
                    val activePkg = getPkgName(effectiveLang)
                    
                    // --- KEY CHANGE: GET SAVED HZ ---
                    // Scanner က ရှာပေးထားတဲ့ Hz ကို ယူမယ်။ မရှိရင် Fallback သုံးမယ်။
                    var inputRate = configPrefs.getInt("RATE_$activePkg", 0)
                    if (inputRate == 0) inputRate = getFallbackRate(activePkg)
                    
                    // Tell C++: "Hey, data coming is at THIS rate"
                    AudioProcessor.initSonic(inputRate, 1)
                    AudioProcessor.setConfig(1.0f, 1.0f) // Can add prefs speed here later

                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, getVolumeCorrection(activePkg))
                    val uuid = UUID.randomUUID().toString()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)
                    
                    processPipeCpp(engine, params, chunk.text, callback, uuid)
                }
            } catch (e: Exception) { e.printStackTrace()
            } finally { if (!isStopped.get()) callback?.done() }
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
    
    private fun processPipeCpp(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        var lR: ParcelFileDescriptor? = null; var lW: ParcelFileDescriptor? = null
        try {
            val pipe = ParcelFileDescriptor.createPipe(); lR = pipe[0]; lW = pipe[1]
            val fR = lR
            val readerFuture = pipeExecutor.submit {
                var fis: FileInputStream? = null
                try {
                    fis = FileInputStream(fR.fileDescriptor)
                    val buffer = ByteArray(4096)
                    
                    // --- NO HEADER PARSING HERE ---
                    // Just read raw bytes and push to C++
                    
                    while (!isStopped.get() && !Thread.currentThread().isInterrupted) {
                        val read = fis.read(buffer)
                        if (read == -1) break
                        if (read > 0) {
                            val out = AudioProcessor.processAudio(buffer, read)
                            if (out.isNotEmpty()) {
                                synchronized(callback) {
                                    try { callback.audioAvailable(out, 0, out.size) } catch (e: Exception) {}
                                }
                            }
                        }
                    }
                } catch (e: IOException) { 
                } finally { fis?.close(); fR?.close() }
            }
            engine.synthesizeToFile(text, params, lW!!, uuid)
            try { lW?.close() } catch(e:Exception){}
            try { readerFuture.get() } catch (e: Exception) { }
        } catch (e: Exception) { lR?.close(); lW?.close() }
    }

    private fun containsThaiOrShanChar(text: String): Boolean {
        for (char in text) {
            val code = char.code
            if (code in 0x0E00..0x0E7F || code in 0x1000..0x109F) return true
        }
        return false
    }

    private fun getRateAndPitch(lang: String, request: SynthesisRequest?): Pair<Float, Float> {
        // Keep your logic here
        return Pair(1.0f, 1.0f)
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
        isStopped.set(true)
        currentTask?.cancel(true)
        AudioProcessor.flush()
    }
    
    override fun onDestroy() { 
        isStopped.set(true)
        controllerExecutor.shutdownNow(); pipeExecutor.shutdownNow()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        AudioProcessor.flush(); super.onDestroy()
    }
    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

