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

data class WavInfo(val sampleRate: Int, val channels: Int)
data class LangChunk(val text: String, val lang: String)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null
    
    private val controllerExecutor = Executors.newSingleThreadExecutor()
    private val pipeExecutor = Executors.newCachedThreadPool()
    
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    
    @Volatile private var activeReadFd: ParcelFileDescriptor? = null
    @Volatile private var activeWriteFd: ParcelFileDescriptor? = null
    
    // Master Output Rate (TalkBack will receive this)
    private val TARGET_RATE = 24000 
    
    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service", "Cherry TTS: Log Monitor Active")
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherryTTS:WakeLock")
        wakeLock?.setReferenceCounted(false)
        
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        initEngine("Shan", prefs.getString("pref_shan_pkg", "com.espeak.ng")) { shanEngine = it }
        initEngine("Burmese", prefs.getString("pref_burmese_pkg", "com.google.android.tts")) { 
            burmeseEngine = it
            it.language = Locale("my", "MM")
        }
        initEngine("English", prefs.getString("pref_english_pkg", "com.google.android.tts")) { 
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
        } catch (e: Exception) { }
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
        
        onStop() 
        isStopped.set(false)
        
        if (wakeLock?.isHeld == false) wakeLock?.acquire(60000)

        currentTask = controllerExecutor.submit {
            try {
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
                    val (rate, pitch) = getRateAndPitch(chunk.lang, request)
                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    
                    engine.setSpeechRate(1.0f)
                    engine.setPitch(1.0f) 
                    
                    val success = processWithPipe(engine, chunk.text, params, callback, hasStartedCallback, reqId, index, rate, pitch)
                    
                    if (success) hasStartedCallback = true
                }
                
                if (!hasStartedCallback && !isStopped.get()) {
                     callback?.start(TARGET_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

            } catch (e: Exception) {
                AppLogger.log("Error", "[$reqId] ${e.message}")
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

    private fun processWithPipe(engine: TextToSpeech, text: String, params: Bundle, callback: SynthesisCallback?, alreadyStarted: Boolean, reqId: Long, chunkIdx: Int, speed: Float, pitch: Float): Boolean {
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
                    val header = ByteArray(44)
                    var totalRead = 0
                    
                    while (totalRead < 44) {
                         if (isStopped.get()) throw IOException("Stopped")
                         val c = fis.read(header, totalRead, 44 - totalRead)
                         if (c == -1) break
                         totalRead += c
                    }

                    if (totalRead == 44) {
                        val wavInfo = getWavInfo(header)
                        val enginePkg = engine.packageName ?: "Unknown"

                        // *** LOGGING AREA ***
                        val isRiff = header[0] == 'R'.toByte() && header[1] == 'I'.toByte()
                        val headerType = if (isRiff) "WAV Header" else "RAW (No Header)"
                        
                        // ဒီစာကြောင်းက Logcat မှာ ပေါ်လာမယ့် အဖြေပါ
                        AppLogger.log("HZ_CHECK", "Pkg: $enginePkg | Type: $headerType | Rate: ${wavInfo.sampleRate}")
                        // ********************

                        val engineRate = wavInfo.sampleRate
                        
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
                        
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                             if (isStopped.get()) break
                             
                             val sonicOutput = AudioProcessor.processAudio(buffer, bytesRead)
                             
                             if (sonicOutput.isNotEmpty()) {
                                 // Resample from DETECTED rate to 24000
                                 val finalOutput = AudioResampler.resample(sonicOutput, sonicOutput.size, engineRate, TARGET_RATE)
                                 
                                 if (finalOutput.isNotEmpty()) {
                                     synchronized(callback) {
                                         callback.audioAvailable(finalOutput, 0, finalOutput.size)
                                     }
                                 }
                             }
                        }
                    } else {
                        AppLogger.log("HZ_CHECK", "Failed to read header. Read $totalRead bytes")
                    }
                    fis.close()
                } catch (e: Exception) { 
                    AppLogger.log("HZ_CHECK", "Pipe Error: ${e.message}")
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
        } finally {
            try { activeReadFd?.close() } catch (e: Exception) {}
            try { activeWriteFd?.close() } catch (e: Exception) {}
        }
        return didStart
    }
    
    private fun getWavInfo(header: ByteArray): WavInfo {
        if (header.size < 44) return WavInfo(16000, 1)
        val rate = (header[24].toInt() and 0xFF) or 
                   ((header[25].toInt() and 0xFF) shl 8) or 
                   ((header[26].toInt() and 0xFF) shl 16) or 
                   ((header[27].toInt() and 0xFF) shl 24)
        val safeRate = if (rate in 4000..48000) rate else 16000 
        return WavInfo(safeRate, 1)
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

