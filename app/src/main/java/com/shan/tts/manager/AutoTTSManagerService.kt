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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

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
    private val MAX_AUDIO_CHUNK_SIZE = 4096 
    
    override fun onCreate() {
        super.onCreate()
        
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
        } catch (e: Exception) { 
             AppLogger.log("CHERRY_DEBUG", "Exception Init $name: ${e.message}")
        }
    }

    override fun onStop() { 
        isStopped.set(true)
        currentTask?.cancel(true)
        closePipeSafely()
        AudioProcessor.flush()
    }
    
    private fun closePipeSafely() {
        try { activeReadFd?.close() } catch (e: Exception) {}
        try { activeWriteFd?.close() } catch (e: Exception) {}
        activeReadFd = null
        activeWriteFd = null
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val reqId = System.currentTimeMillis() % 1000
        
        onStop()
        isStopped.set(false)
        
        if (wakeLock?.isHeld == false) wakeLock?.acquire(60000)

        currentTask = controllerExecutor.submit {
            try {
                if (englishEngine == null && burmeseEngine == null) {
                    Thread.sleep(500)
                }

                val chunks = LanguageUtils.splitHelper(text) 
                
                if (chunks.isEmpty()) {
                    callback?.start(TARGET_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callback?.done()
                    return@submit
                }

                var hasStartedCallback = false

                for ((index, chunk) in chunks.withIndex()) {
                    if (isStopped.get()) break
                    
                    val engine = getEngine(chunk.lang) ?: continue
                    
                    var activePkg = englishPkgName
                    if (engine === shanEngine) activePkg = shanPkgName
                    else if (engine === burmeseEngine) activePkg = burmesePkgName
                    
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
            }
        }
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
        
        var userRateMultiplier = rateSeekbar / 50.0f
        if (userRateMultiplier < 0.4f) userRateMultiplier = 0.4f 

        var userPitchMultiplier = pitchSeekbar / 50.0f
        if (userPitchMultiplier < 0.4f) userPitchMultiplier = 0.4f 

        var finalRate = sysRate * userRateMultiplier
        val finalPitch = sysPitch * userPitchMultiplier
        
        if (finalRate > 3.5f) finalRate = 3.5f
        if (finalRate < 0.1f) finalRate = 0.1f

        return Pair(finalRate, finalPitch)
    }

    private fun processWithPipe(engine: TextToSpeech, enginePkgName: String, text: String, params: Bundle, callback: SynthesisCallback?, alreadyStarted: Boolean, reqId: Long, chunkIdx: Int, speed: Float, pitch: Float): Boolean {
        if (callback == null) return alreadyStarted

        var didStart = alreadyStarted
        val uuid = UUID.randomUUID().toString()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

        val synthesisLatch = CountDownLatch(1)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == uuid) {
                    try { activeWriteFd?.close(); activeWriteFd = null } catch (e: Exception) {}
                    synthesisLatch.countDown()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == uuid) {
                    try { activeWriteFd?.close(); activeWriteFd = null } catch (e: Exception) {}
                    synthesisLatch.countDown()
                }
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                onError(utteranceId)
            }
        })

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            activeReadFd = pipe[0]
            activeWriteFd = pipe[1]

            val readerFuture = pipeExecutor.submit {
                try {
                    val fd = activeReadFd?.fileDescriptor ?: return@submit
                    val fis = FileInputStream(fd)
                    
                    val headerBuffer = ByteArray(44)
                    var bytesRead = fis.read(headerBuffer)

                    var detectedRate = 24000 
                    var hasHeader = false

                    if (bytesRead == 44 && WavUtils.isValidWav(headerBuffer)) {
                        detectedRate = WavUtils.getSampleRate(headerBuffer)
                        hasHeader = true
                    } else if (bytesRead > 0) {
                        if (enginePkgName.lowercase(Locale.ROOT).contains("google")) detectedRate = 24000 else detectedRate = 16000
                    }

                    AudioProcessor.initSonic(detectedRate, 1)
                    AudioProcessor.setConfig(speed, pitch, 1.0f) 
                    
                    if (!didStart) {
                        synchronized(callback) {
                            callback.start(TARGET_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                        }
                        didStart = true
                    }
                    
                    if (!hasHeader && bytesRead > 0) {
                         val initialChunk = AudioProcessor.processAudio(headerBuffer, bytesRead)
                         writeToCallback(initialChunk, detectedRate, callback)
                    }

                    val buffer = ByteArray(4096)
                    
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                         if (isStopped.get()) break
                         
                         val sonicOutput = AudioProcessor.processAudio(buffer, bytesRead)
                         writeToCallback(sonicOutput, detectedRate, callback)
                    }
                    fis.close()
                } catch (e: Exception) { 
                    if (!isStopped.get()) AppLogger.log("CHERRY_DEBUG", "Pipe Read Error: ${e.message}")
                }
            }

            val targetFd = activeWriteFd
            if (targetFd != null) {
                val result = engine.synthesizeToFile(text, params, targetFd, uuid)
                if (result == TextToSpeech.ERROR) {
                    try { activeWriteFd?.close() } catch(e:Exception){}
                    synthesisLatch.countDown()
                }
            }

            try {
                synthesisLatch.await(10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) { }

            readerFuture.get() 

        } catch (e: Exception) {
             AppLogger.log("CHERRY_DEBUG", "Process Exception: ${e.message}")
        } finally {
            closePipeSafely()
        }
        return didStart
    }
    
    private fun writeToCallback(sonicOutput: ByteArray, inputRate: Int, callback: SynthesisCallback) {
         if (sonicOutput.isNotEmpty()) {
             val finalOutput = AudioResampler.resample(sonicOutput, sonicOutput.size, inputRate, TARGET_RATE)
             
             if (finalOutput.isNotEmpty()) {
                 var offset = 0
                 while (offset < finalOutput.size) {
                     if (isStopped.get()) break
                     
                     val chunkLength = min(MAX_AUDIO_CHUNK_SIZE, finalOutput.size - offset)
                     
                     synchronized(callback) {
                         try {
                             callback.audioAvailable(finalOutput, offset, chunkLength)
                         } catch (e: Exception) {}
                     }
                     offset += chunkLength
                 }
             }
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
