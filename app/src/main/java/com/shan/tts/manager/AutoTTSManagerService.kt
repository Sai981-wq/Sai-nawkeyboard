package com.shan.tts.manager

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
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
    private var wakeLock: PowerManager.WakeLock? = null
    
    @Volatile private var activeReadFd: ParcelFileDescriptor? = null
    @Volatile private var activeWriteFd: ParcelFileDescriptor? = null
    
    private val TARGET_RATE = 24000 
    
    override fun onCreate() {
        super.onCreate()
        // LOG: Service စတင်ကြောင်း မှတ်တမ်း
        AppLogger.log("CHERRY_DEBUG", "=== Service Started: Logging Enabled ===")
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherryTTS:WakeLock")
        wakeLock?.setReferenceCounted(false)
        
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
        initEngine("Shan", shanPkgName) { shanEngine = it }
        
        burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
        initEngine("Burmese", burmesePkgName) { 
            burmeseEngine = it
            it.language = Locale("my", "MM")
        }
        
        englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
        initEngine("English", englishPkgName) { 
            englishEngine = it
            it.language = Locale.US
        }
    }

    private fun initEngine(name: String, pkg: String?, onSuccess: (TextToSpeech) -> Unit) {
        AppLogger.log("CHERRY_DEBUG", "Init Engine: $name ($pkg)")
        if (pkg.isNullOrEmpty()) return
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    onSuccess(tempTTS!!)
                    AppLogger.log("CHERRY_DEBUG", "$name Engine Initialized Successfully")
                    tempTTS?.setOnUtteranceProgressListener(object : UtteranceProgressListener(){
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {}
                        override fun onError(id: String?) {}
                    })
                } else {
                    AppLogger.log("CHERRY_DEBUG", "Failed to Init $name")
                }
            }, pkg)
        } catch (e: Exception) { 
             AppLogger.log("CHERRY_DEBUG", "Exception Init $name: ${e.message}")
        }
    }

    override fun onStop() { 
        isStopped.set(true)
        currentTask?.cancel(true)
        try { activeReadFd?.close() } catch (e: Exception) {}
        try { activeWriteFd?.close() } catch (e: Exception) {}
        activeReadFd = null
        activeWriteFd = null
        AudioProcessor.flush()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val reqId = System.currentTimeMillis() % 1000
        
        // LOG: စာစဖတ်ပြီ
        AppLogger.log("CHERRY_DEBUG", "[$reqId] Request Received: '${text.take(20)}...'")
        
        onStop() 
        isStopped.set(false)
        
        if (wakeLock?.isHeld == false) wakeLock?.acquire(60000)

        currentTask = controllerExecutor.submit {
            try {
                val chunks = LanguageUtils.splitHelper(text) 
                AppLogger.log("CHERRY_DEBUG", "[$reqId] Split into ${chunks.size} chunks")
                
                if (chunks.isEmpty()) {
                    callback?.start(TARGET_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callback?.done()
                    return@submit
                }

                var hasStartedCallback = false

                for ((index, chunk) in chunks.withIndex()) {
                    if (isStopped.get()) break
                    
                    val engine = getEngine(chunk.lang) ?: continue
                    
                    // Engine ရွေးချယ်မှု Log
                    var activePkg = englishPkgName
                    if (engine === shanEngine) activePkg = shanPkgName
                    else if (engine === burmeseEngine) activePkg = burmesePkgName
                    
                    AppLogger.log("CHERRY_DEBUG", "[$reqId] Chunk $index (${chunk.lang}) -> Using Pkg: $activePkg")

                    val (rate, pitch) = getRateAndPitch(chunk.lang, request)
                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    
                    engine.setSpeechRate(1.0f)
                    engine.setPitch(1.0f) 
                    
                    val success = processWithPipe(engine, activePkg, chunk.text, params, callback, hasStartedCallback, reqId, index, rate, pitch)
                    
                    if (success) hasStartedCallback = true
                }
                
                if (!hasStartedCallback && !isStopped.get()) {
                     callback?.start(TARGET_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

            } catch (e: Exception) {
                AppLogger.log("CHERRY_DEBUG", "Error [$reqId]: ${e.message}")
            } finally {
                if (!isStopped.get()) callback?.done()
                if (wakeLock?.isHeld == true) wakeLock?.release()
                AppLogger.log("CHERRY_DEBUG", "[$reqId] Finished.")
            }
        }
    }
    
    private fun getRateAndPitch(lang: String, request: SynthesisRequest?): Pair<Float, Float> {
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        val sysRate = (request?.speechRate ?: 100) / 100.0f
        val sysPitch = (request?.pitch ?: 100) / 100.0f
        
        var seekbarValue = 50 
        when(lang) {
            "SHAN" -> seekbarValue = prefs.getInt("pitch_shan", 50)
            "MYANMAR" -> seekbarValue = prefs.getInt("pitch_burmese", 50)
            else -> seekbarValue = prefs.getInt("pitch_english", 50)
        }
        
        val userPitchMultiplier = 0.5f + (seekbarValue / 100.0f)
        val finalPitch = sysPitch * userPitchMultiplier
        return Pair(sysRate, finalPitch)
    }

    private fun getEngineConfig(packageName: String): Int {
        val lowerPkg = packageName.toLowerCase(Locale.ROOT)
        
        // 1. Google = 24000
        if (lowerPkg.contains("google")) return 24000
        
        // 2. ETI = 11025
        if (lowerPkg.contains("eloquence") || lowerPkg.contains("eti")) return 11025
        
        // 3. Shan/Saomai/eSpeak = 22050
        if (lowerPkg.contains("shan") || 
            lowerPkg.contains("saomai") || 
            lowerPkg.contains("myanmartts") || 
            lowerPkg.contains("espeak")) {
            return 22050
        }
        
        // 4. Vocalizer = 22050
        if (lowerPkg.contains("vocalizer")) return 22050
        
        return 16000
    }

    private fun processWithPipe(engine: TextToSpeech, enginePkgName: String, text: String, params: Bundle, callback: SynthesisCallback?, alreadyStarted: Boolean, reqId: Long, chunkIdx: Int, speed: Float, pitch: Float): Boolean {
        if (callback == null) return alreadyStarted

        var didStart = alreadyStarted
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
                    
                    val engineRate = getEngineConfig(enginePkgName)
                    
                    // LOG: Hz ဆုံးဖြတ်ချက်
                    AppLogger.log("CHERRY_DEBUG", "CONFIG: Package '$enginePkgName' detected as $engineRate Hz")
                    
                    AudioProcessor.initSonic(engineRate, 1)
                    AudioProcessor.setConfig(speed, pitch, 1.0f) 
                    
                    if (!didStart) {
                        synchronized(callback) {
                            callback.start(TARGET_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                        }
                        didStart = true
                    }
                    
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    var totalProcessed = 0
                    
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                         if (isStopped.get()) break
                         
                         val sonicOutput = AudioProcessor.processAudio(buffer, bytesRead)
                         
                         if (sonicOutput.isNotEmpty()) {
                             val finalOutput = AudioResampler.resample(sonicOutput, sonicOutput.size, engineRate, TARGET_RATE)
                             
                             if (finalOutput.isNotEmpty()) {
                                 synchronized(callback) {
                                     callback.audioAvailable(finalOutput, 0, finalOutput.size)
                                 }
                                 totalProcessed += finalOutput.size
                             }
                         }
                    }
                    // LOG: ပြီးဆုံးမှု အခြေအနေ
                    AppLogger.log("CHERRY_DEBUG", "Stream Finished. Total Output: $totalProcessed bytes sent to TalkBack.")
                    
                    fis.close()
                } catch (e: Exception) { 
                    AppLogger.log("CHERRY_DEBUG", "Pipe Error: ${e.message}")
                }
            }

            val targetFd = activeWriteFd
            if (targetFd != null) {
                try {
                    engine.synthesizeToFile(text, params, targetFd, uuid)
                } catch (e: Exception) {}
            }
            try { activeWriteFd?.close(); activeWriteFd = null } catch(e:Exception){}

            readerFuture.get() 

        } catch (e: Exception) {
            AppLogger.log("CHERRY_DEBUG", "Process Exception: ${e.message}")
        } finally {
            try { activeReadFd?.close() } catch (e: Exception) {}
            try { activeWriteFd?.close() } catch (e: Exception) {}
        }
        return didStart
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

