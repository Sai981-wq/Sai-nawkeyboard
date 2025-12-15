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
    
    private lateinit var configPrefs: SharedPreferences
    
    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service Created. Initializing engines...")
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
        
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
                    AppLogger.log("Engine Ready: $pkg")
                } else {
                    AppLogger.error("Engine Init Failed: $pkg")
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.error("Exception Init Engine: $pkg", e)
        }
    }

    override fun onStop() { 
        isStopped.set(true)
        currentTask?.cancel(true)
        try { activeReadFd?.close() } catch (e: Exception) {}
        try { activeWriteFd?.close() } catch (e: Exception) {}
        activeReadFd = null
        activeWriteFd = null
        AppLogger.log("Service Stopped")
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        AppLogger.log(">>> NEW REQUEST: ${text.take(30)}...")
        
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
                
                AppLogger.log("Split text into ${chunks.size} chunks.")

                val firstLang = chunks[0].lang
                val firstPkg = getPkgName(firstLang)
                
                var sessionRate = configPrefs.getInt("RATE_$firstPkg", 0)
                
                if (sessionRate < 8000) {
                     sessionRate = if (firstPkg.lowercase().contains("espeak")) 22050 else 24000
                     AppLogger.log("WARNING: Master rate was 0. Forced to $sessionRate Hz based on $firstPkg")
                } else {
                    AppLogger.log("Session Master Rate set to: $sessionRate Hz (from $firstPkg)")
                }

                synchronized(callback) {
                    callback.start(sessionRate, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

                var lastEngine: TextToSpeech? = null
                var lastRate = -1.0f
                var lastPitch = -1.0f

                for ((index, chunk) in chunks.withIndex()) {
                    if (isStopped.get()) break
                    
                    val engine = getEngine(chunk.lang) ?: continue
                    val activePkg = getPkgName(chunk.lang)
                    
                    val (rateMultiplier, pitchMultiplier) = getRateAndPitch(chunk.lang, request)
                    
                    if (engine !== lastEngine || rateMultiplier != lastRate || pitchMultiplier != lastPitch) {
                        engine.setSpeechRate(rateMultiplier)
                        engine.setPitch(pitchMultiplier)
                        lastEngine = engine
                        lastRate = rateMultiplier
                        lastPitch = pitchMultiplier
                        try { Thread.sleep(10) } catch(e:Exception){}
                        AppLogger.log("Switched Config -> Engine: $activePkg | Rate: $rateMultiplier | Pitch: $pitchMultiplier")
                    }
                    
                    val params = Bundle()
                    val balanceVolume = getVolumeCorrection(activePkg)
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, balanceVolume)
                    
                    var engineRate = configPrefs.getInt("RATE_$activePkg", 0)
                    if (engineRate < 8000) {
                        engineRate = if (activePkg.lowercase().contains("espeak") || activePkg.lowercase().contains("shan")) 22050 else 24000
                        AppLogger.log("Correction: $activePkg rate forced to $engineRate Hz")
                    }
                    
                    AppLogger.log("Processing Chunk $index: [${chunk.lang}] Rate: ${engineRate}Hz -> Master: ${sessionRate}Hz")
                    processDirectPipe(engine, engineRate, params, chunk.text, callback, sessionRate)
                }
            } catch (e: Exception) {
                AppLogger.error("Synthesis Critical Error", e)
            } finally {
                if (!isStopped.get()) {
                    callback?.done()
                    AppLogger.log("<<< REQUEST DONE")
                }
            }
        }
    }
    
    private fun getPkgName(lang: String): String {
        return when (lang) {
            "SHAN" -> shanPkgName
            "MYANMAR" -> burmesePkgName
            else -> englishPkgName
        }
    }
    
    private fun getVolumeCorrection(pkgName: String): Float {
        val lower = pkgName.lowercase(Locale.ROOT)
        if (lower.contains("espeak") || lower.contains("shan")) return 0.6f 
        if (lower.contains("vocalizer")) return 0.85f
        if (lower.contains("eloquence")) return 0.5f
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

    private fun processDirectPipe(engine: TextToSpeech, engineRate: Int, params: Bundle, text: String, callback: SynthesisCallback, sessionRate: Int) {
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
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    var totalBytes = 0
                    
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                         if (isStopped.get()) break
                         
                         if (bytesRead > 0) {
                             totalBytes += bytesRead
                             val finalAudio = if (engineRate != sessionRate) {
                                 TTSUtils.resample(buffer, bytesRead, engineRate, sessionRate)
                             } else {
                                 buffer.copyOfRange(0, bytesRead)
                             }
                             
                             synchronized(callback) {
                                 try {
                                     callback.audioAvailable(finalAudio, 0, finalAudio.size)
                                 } catch (e: Exception) {
                                     AppLogger.error("Audio Write Error", e)
                                 }
                             }
                         }
                    }
                    fis.close()
                    // AppLogger.log("Pipe Stream Finished: Read $totalBytes bytes")
                } catch (e: Exception) {
                    AppLogger.error("Pipe Reader Error", e)
                }
            }

            val targetFd = activeWriteFd
            if (targetFd != null) {
                val result = engine.synthesizeToFile(text, params, targetFd, uuid)
                if (result != TextToSpeech.SUCCESS) {
                     AppLogger.error("Engine synthesizeToFile Failed!")
                }
            }
            try { activeWriteFd?.close(); activeWriteFd = null } catch(e:Exception){}

            readerFuture.get() 

        } catch (e: Exception) {
            AppLogger.error("Pipe Setup Exception", e)
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

