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
data class WavInfo(val sampleRate: Int, val channels: Int)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null
    
    // Executor
    private val controllerExecutor = Executors.newSingleThreadExecutor()
    private val pipeExecutor = Executors.newCachedThreadPool()
    
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    
    // *** KILL SWITCH VARIABLES (Global References) ***
    // onStop ဝင်လာရင် ဒီကောင်တွေကို လှမ်းပိတ်မှ Thread တွေ ချက်ချင်းရပ်မှာပါ
    @Volatile private var activeReadFd: ParcelFileDescriptor? = null
    @Volatile private var activeWriteFd: ParcelFileDescriptor? = null
    
    private val TARGET_SAMPLE_RATE = 24000 
    
    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service", "Cherry TTS: Traffic Jam Fix (Aggressive Stop)")
        
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
                    AppLogger.log("Init", "$name initialized SUCCESS")
                    onSuccess(tempTTS!!)
                    tempTTS?.setOnUtteranceProgressListener(object : UtteranceProgressListener(){
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {}
                        override fun onError(id: String?) {}
                    })
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.log("InitError", "Crash on $name: ${e.message}")
        }
    }

    // *** THE FIX IS HERE (onStop) ***
    override fun onStop() { 
        // 1. Flag တင်မယ်
        isStopped.set(true)
        
        // 2. Task ကို Cancel လုပ်မယ်
        currentTask?.cancel(true)
        
        // 3. *** AGGRESSIVE KILL *** (ပိုက်ကို ရိုက်ချိုးလိုက်မယ်)
        // ဒါမှ Read/Write လုပ်နေတဲ့ Thread တွေက IOException တက်ပြီး ချက်ချင်းရပ်သွားမှာ
        try { activeReadFd?.close() } catch (e: Exception) {}
        try { activeWriteFd?.close() } catch (e: Exception) {}
        
        activeReadFd = null
        activeWriteFd = null
        
        AppLogger.log("Flow", "STOP COMMAND: Queue Flushed & Pipes Broken")
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val reqId = System.currentTimeMillis() % 1000
        
        // 1. အရင်အလုပ်ဟောင်းတွေကို အရင်ရှင်းမယ်
        onStop() 
        isStopped.set(false) // ပြန်ဖွင့်
        
        if (wakeLock?.isHeld == false) wakeLock?.acquire(60000)

        // 2. Task အသစ် စမယ်
        currentTask = controllerExecutor.submit {
            try {
                val chunks = LanguageUtils.splitHelper(text) 
                
                if (chunks.isEmpty()) {
                    callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callback?.done()
                    return@submit
                }

                var hasStartedCallback = false

                for ((index, chunk) in chunks.withIndex()) {
                    // Check if stopped
                    if (isStopped.get() || Thread.currentThread().isInterrupted) break
                    
                    val engine = getEngine(chunk.lang) ?: continue
                    val params = applyRateAndPitch(engine, chunk.lang, request)
                    
                    val success = processWithPipe(engine, chunk.text, params, callback, hasStartedCallback, reqId, index)
                    
                    if (success) hasStartedCallback = true
                }
                
                if (!hasStartedCallback && !isStopped.get()) {
                     callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

            } catch (e: Exception) {
                // Ignore errors (mostly interruptions)
            } finally {
                if (!isStopped.get()) callback?.done()
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }
    
    private fun applyRateAndPitch(engine: TextToSpeech, lang: String, request: SynthesisRequest?): Bundle {
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100
        
        var userRate = 100
        var userPitch = 100
        
        when(lang) {
            "SHAN" -> {
                userRate = prefs.getInt("rate_shan", 100)
                userPitch = prefs.getInt("pitch_shan", 100)
            }
            "MYANMAR" -> {
                userRate = prefs.getInt("rate_burmese", 100)
                userPitch = prefs.getInt("pitch_burmese", 100)
            }
            else -> {
                userRate = prefs.getInt("rate_english", 100)
                userPitch = prefs.getInt("pitch_english", 100)
            }
        }

        val finalRate = (sysRate * userRate) / 10000.0f
        val finalPitch = (sysPitch * userPitch) / 10000.0f
        
        engine.setSpeechRate(finalRate)
        engine.setPitch(finalPitch)

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) 
        return params
    }

    private fun processWithPipe(engine: TextToSpeech, text: String, params: Bundle, callback: SynthesisCallback?, alreadyStarted: Boolean, reqId: Long, chunkIdx: Int): Boolean {
        if (callback == null) return alreadyStarted

        var didStart = alreadyStarted
        val uuid = UUID.randomUUID().toString()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            
            // *** Assign to Global Variables for Killing ***
            activeReadFd = pipe[0]
            activeWriteFd = pipe[1]

            // *** READER TASK ***
            val readerFuture = pipeExecutor.submit {
                try {
                    // Local reference to avoid null issues if global is cleared
                    val fd = activeReadFd?.fileDescriptor ?: return@submit
                    val fis = FileInputStream(fd)
                    val header = ByteArray(44)
                    var totalRead = 0
                    
                    while (totalRead < 44) {
                         if (isStopped.get()) throw IOException("Stopped") // Fast Exit
                         val c = fis.read(header, totalRead, 44 - totalRead)
                         if (c == -1) break
                         totalRead += c
                    }

                    if (totalRead == 44) {
                        val wavInfo = getWavInfo(header)
                        
                        if (!didStart) {
                            synchronized(callback) {
                                callback.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                            }
                            didStart = true
                        }
                        
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                             if (isStopped.get()) break
                             
                             // *** Force Resample Logic ***
                             val outputBytes = if (wavInfo.sampleRate != TARGET_SAMPLE_RATE) {
                                 AudioResampler.simpleResample(buffer, bytesRead, wavInfo.sampleRate, TARGET_SAMPLE_RATE)
                             } else {
                                 buffer.copyOfRange(0, bytesRead)
                             }
                             
                             if (outputBytes.isNotEmpty()) {
                                 synchronized(callback) {
                                     callback.audioAvailable(outputBytes, 0, outputBytes.size)
                                 }
                             }
                        }
                    }
                    fis.close()
                } catch (e: Exception) {
                    // Expected during stop
                }
            }

            // *** WRITER TASK ***
            try {
                engine.synthesizeToFile(text, params, activeWriteFd, uuid)
            } catch (e: Exception) {
                // Ignore write errors if pipe is closed
            }
            
            // Close Writer
            try { activeWriteFd?.close(); activeWriteFd = null } catch(e:Exception){}

            readerFuture.get() 

        } catch (e: Exception) {
             // Ignore
        } finally {
            // Cleanup just in case
            try { activeReadFd?.close() } catch (e: Exception) {}
            try { activeWriteFd?.close() } catch (e: Exception) {}
        }
        return didStart
    }
    
    private fun getWavInfo(header: ByteArray): WavInfo {
        if (header.size < 44) return WavInfo(16000, 1)
        val rate = (header[24].toInt() and 0xFF) or ((header[25].toInt() and 0xFF) shl 8) or ((header[26].toInt() and 0xFF) shl 16) or ((header[27].toInt() and 0xFF) shl 24)
        val safeRate = if (rate > 0) rate else 16000
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

