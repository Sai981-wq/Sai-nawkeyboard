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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

// =======================
// Main Service Class
// =======================
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
    
    private val MAX_AUDIO_CHUNK_SIZE = 4096 
    
    override fun onCreate() {
        super.onCreate()
        
        // WakeLock for preventing sleep during long reading
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
             AppLogger.log("CHERRY_DEBUG", "Init Error $name: ${e.message}")
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
                    callback?.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
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
                
                // If nothing played, just finish to satisfy system
                if (!hasStartedCallback && !isStopped.get()) {
                     callback?.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
                     callback?.done()
                }

            } catch (e: Exception) {
                AppLogger.log("CHERRY_DEBUG", "Critical Error [$reqId]: ${e.message}")
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
                    
                    // 1. Read WAV Header loop to ensure full 44 bytes
                    val headerBuffer = ByteArray(44)
                    var totalHeaderRead = 0
                    while (totalHeaderRead < 44) {
                        val count = fis.read(headerBuffer, totalHeaderRead, 44 - totalHeaderRead)
                        if (count == -1) break
                        totalHeaderRead += count
                    }

                    var detectedRate = 24000 // Default fallback
                    var hasValidHeader = false

                    if (totalHeaderRead == 44 && InternalWavUtils.isValidWav(headerBuffer)) {
                        detectedRate = InternalWavUtils.getSampleRate(headerBuffer)
                        hasValidHeader = true
                    } else {
                        // Header ဖတ်မရရင် Google ဆို 24k, တခြားဆို 16k မှန်းမယ်
                        if (enginePkgName.lowercase(Locale.ROOT).contains("google")) detectedRate = 24000 else detectedRate = 16000
                    }

                    // 2. Direct Sound Mode (Killer Fix for "Original Sound")
                    // If speed/pitch normal, bypass everything and write direct
                    val isNormalSpeed = abs(speed - 1.0f) < 0.05f
                    val isNormalPitch = abs(pitch - 1.0f) < 0.05f
                    val useDirectMode = isNormalSpeed && isNormalPitch

                    if (!useDirectMode) {
                        // Configure Sonic only if we need to change speed/pitch
                        AudioProcessor.initSonic(detectedRate, 1)
                        AudioProcessor.setConfig(speed, pitch, 1.0f)
                    }
                    
                    if (!didStart) {
                        synchronized(callback) {
                            // Important: Start callback with the ACTUAL detected rate
                            // This prevents "chipmunk" voice and removes need for Resampling
                            callback.start(detectedRate, AudioFormat.ENCODING_PCM_16BIT, 1)
                        }
                        didStart = true
                    }
                    
                    // Process the header/initial bytes if they weren't a valid header
                    if (!hasValidHeader && totalHeaderRead > 0) {
                         if (useDirectMode) {
                             writeToCallback(headerBuffer, totalHeaderRead, callback)
                         } else {
                             val processed = AudioProcessor.processAudio(headerBuffer, totalHeaderRead)
                             writeToCallback(processed, processed.size, callback)
                         }
                    }

                    // 3. Main Audio Loop
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                         if (isStopped.get()) break
                         
                         if (useDirectMode) {
                             // DIRECT PASS-THROUGH (Best Quality)
                             writeToCallback(buffer, bytesRead, callback)
                         } else {
                             // SONIC PROCESSING
                             val sonicOutput = AudioProcessor.processAudio(buffer, bytesRead)
                             if (sonicOutput.isNotEmpty()) {
                                 writeToCallback(sonicOutput, sonicOutput.size, callback)
                             }
                         }
                    }
                    fis.close()
                } catch (e: IOException) { 
                    // Suppress EBADF / Interrupted logs when stopping
                    val msg = e.message ?: ""
                    if (!isStopped.get() && !msg.contains("EBADF") && !msg.contains("interrupted") && !msg.contains("Bad file descriptor")) {
                         AppLogger.log("CHERRY_DEBUG", "Pipe Read Error: $msg")
                    }
                } catch (e: Exception) {
                    if (!isStopped.get()) AppLogger.log("CHERRY_DEBUG", "Pipe Error: ${e.message}")
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

            // Wait for engine to finish writing
            while (!isStopped.get() && synthesisLatch.count > 0) {
                try {
                    synthesisLatch.await(500, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) { }
            }

            readerFuture.get() 

        } catch (e: Exception) {
             AppLogger.log("CHERRY_DEBUG", "Process Exception: ${e.message}")
        } finally {
            closePipeSafely()
        }
        return didStart
    }
    
    // Simple writer helper (No Resampling needed anymore because we use dynamic callback.start)
    private fun writeToCallback(buffer: ByteArray, size: Int, callback: SynthesisCallback) {
        if (size <= 0) return
        var offset = 0
        while (offset < size) {
             if (isStopped.get()) break
             val chunkLength = min(MAX_AUDIO_CHUNK_SIZE, size - offset)
             synchronized(callback) {
                 try {
                     // Pass 'buffer' with offset and length
                     // If buffer is bigger than size, this logic handles it
                     val tempBuffer: ByteArray
                     if (offset == 0 && size == buffer.size) {
                         tempBuffer = buffer // Optimization
                     } else {
                         tempBuffer = ByteArray(chunkLength)
                         System.arraycopy(buffer, offset, tempBuffer, 0, chunkLength)
                     }
                     callback.audioAvailable(tempBuffer, 0, chunkLength)
                 } catch (e: Exception) {}
             }
             offset += chunkLength
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

// =======================
// Internal Helper Object
// =======================
object InternalWavUtils {
    fun isValidWav(header: ByteArray): Boolean {
        if (header.size < 44) return false
        val riff = String(header, 0, 4)
        val wave = String(header, 8, 4)
        return riff == "RIFF" && wave == "WAVE"
    }

    fun getSampleRate(header: ByteArray): Int {
        if (header.size < 44) return 24000
        return ByteBuffer.wrap(header, 24, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
    }
}
