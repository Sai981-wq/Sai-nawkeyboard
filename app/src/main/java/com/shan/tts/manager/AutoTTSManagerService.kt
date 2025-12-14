package com.shan.tts.manager

import android.content.Context
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
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

data class LangChunk(val text: String, val lang: String)

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
    
    @Volatile private var activeReadFd: ParcelFileDescriptor? = null
    @Volatile private var activeWriteFd: ParcelFileDescriptor? = null
    
    private val rateCache = HashMap<String, Int>()
    
    // MASTER RATE (All audio will be converted to this)
    private val MASTER_SAMPLE_RATE = 24000
    
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
        initEngine(shanPkgName) { shanEngine = it }
        
        burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
        initEngine(burmesePkgName) { 
            burmeseEngine = it
            it.language = Locale("my", "MM")
        }
        
        englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
        initEngine(englishPkgName) { 
            englishEngine = it
            it.language = Locale.US
        }
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

    override fun onStop() { 
        isStopped.set(true)
        currentTask?.cancel(true)
        try { activeReadFd?.close() } catch (e: Exception) {}
        try { activeWriteFd?.close() } catch (e: Exception) {}
        activeReadFd = null
        activeWriteFd = null
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        onStop() 
        isStopped.set(false)

        currentTask = controllerExecutor.submit {
            try {
                if (callback == null) return@submit

                val chunks = LanguageUtils.splitHelper(text) 
                if (chunks.isEmpty()) {
                    callback.done()
                    return@submit
                }
                
                // Start ONE session with Master Rate
                synchronized(callback) {
                    callback.start(MASTER_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

                var lastEngine: TextToSpeech? = null
                var lastRate = -1.0f
                var lastPitch = -1.0f

                for ((index, chunk) in chunks.withIndex()) {
                    if (isStopped.get()) break
                    
                    val engine = getEngine(chunk.lang) ?: continue
                    var activePkg = englishPkgName
                    if (engine === shanEngine) activePkg = shanPkgName
                    else if (engine === burmeseEngine) activePkg = burmesePkgName
                    
                    val (rateMultiplier, pitchMultiplier) = getRateAndPitch(chunk.lang, request)
                    
                    if (engine !== lastEngine || rateMultiplier != lastRate || pitchMultiplier != lastPitch) {
                        engine.setSpeechRate(rateMultiplier)
                        engine.setPitch(pitchMultiplier)
                        lastEngine = engine
                        lastRate = rateMultiplier
                        lastPitch = pitchMultiplier
                        try { Thread.sleep(10) } catch(e:Exception){}
                    }
                    
                    val params = Bundle()
                    val balanceVolume = getVolumeCorrection(activePkg)
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, balanceVolume)
                    
                    processDirectPipe(engine, activePkg, chunk.text, params, callback)
                }
            } catch (e: Exception) {
            } finally {
                if (!isStopped.get()) callback?.done()
            }
        }
    }
    
    private fun getVolumeCorrection(pkgName: String): Float {
        val lower = pkgName.lowercase(Locale.ROOT)
        if (lower.contains("espeak") || lower.contains("shan")) return 0.6f 
        if (lower.contains("vocalizer")) return 0.85f
        return 1.0f 
    }
    
    private fun getRateAndPitch(lang: String, request: SynthesisRequest?): Pair<Float, Float> {
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        val sysRate = (request?.speechRate ?: 100) / 100.0f
        val sysPitch = (request?.pitch ?: 100) / 100.0f
        
        var rateSeekbar = 50
        var pitchSeekbar = 50
        
        when(lang) {
            "SHAN" -> {
                rateSeekbar = prefs.getInt("rate_shan", 50)
                pitchSeekbar = prefs.getInt("pitch_shan", 50)
            }
            "MYANMAR" -> {
                rateSeekbar = prefs.getInt("rate_burmese", 50)
                pitchSeekbar = prefs.getInt("pitch_burmese", 50)
            }
            else -> {
                rateSeekbar = prefs.getInt("rate_english", 50)
                pitchSeekbar = prefs.getInt("pitch_english", 50)
            }
        }
        
        var userRate = rateSeekbar / 50.0f
        var userPitch = pitchSeekbar / 50.0f
        
        if (userRate < 0.2f) userRate = 0.2f
        if (userRate > 3.0f) userRate = 3.0f
        if (userPitch < 0.3f) userPitch = 0.3f
        if (userPitch > 2.0f) userPitch = 2.0f
        
        return Pair(sysRate * userRate, sysPitch * userPitch)
    }

    private fun processDirectPipe(engine: TextToSpeech, pkgName: String, text: String, params: Bundle, callback: SynthesisCallback) {
        val uuid = UUID.randomUUID().toString()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            activeReadFd = pipe[0]
            activeWriteFd = pipe[1]

            val readerFuture = pipeExecutor.submit {
                try {
                    val fd = activeReadFd?.fileDescriptor ?: return@submit
                    val fis = FileInputStream(fd)
                    
                    var engineRate = rateCache[pkgName] ?: 0
                    if (engineRate == 0) {
                        engineRate = TTSUtils.detectEngineSampleRate(engine, applicationContext, pkgName)
                        rateCache[pkgName] = engineRate
                    }

                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                         if (isStopped.get()) break
                         
                         if (bytesRead > 0) {
                             // HERE IS THE MAGIC FIX: Resample everything to MASTER_SAMPLE_RATE (24000)
                             // This prevents "freaking out" when mixing 22k and 24k engines
                             val resampledAudio = TTSUtils.resample(buffer, bytesRead, engineRate, MASTER_SAMPLE_RATE)
                             
                             if (resampledAudio.isNotEmpty()) {
                                 synchronized(callback) {
                                     try {
                                         callback.audioAvailable(resampledAudio, 0, resampledAudio.size)
                                     } catch (e: Exception) {}
                                 }
                             }
                         }
                    }
                    fis.close()
                } catch (e: Exception) {}
            }

            val targetFd = activeWriteFd
            if (targetFd != null) {
                engine.synthesizeToFile(text, params, targetFd, uuid)
            }
            try { activeWriteFd?.close(); activeWriteFd = null } catch(e:Exception){}

            readerFuture.get() 

        } catch (e: Exception) {
        } finally {
            try { activeReadFd?.close() } catch (e: Exception) {}
            try { activeWriteFd?.close() } catch (e: Exception) {}
        }
    }
    
    private fun getEngine(lang: String): TextToSpeech? {
        return when (lang) {
            "SHAN" -> if (shanEngine != null) shanEngine else englishEngine
            "MYANMAR" -> if (burmeseEngine != null) burmeseEngine else englishEngine
            else -> englishEngine
        }
    }

    override fun onDestroy() { 
        isStopped.set(true)
        controllerExecutor.shutdownNow()
        pipeExecutor.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        super.onDestroy()
    }
    
    override fun onGetVoices(): List<Voice> { return listOf() }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onGetLanguage(): Array<String> { return arrayOf("eng", "USA", "") }
}

