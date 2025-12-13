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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

// Data class
data class LangChunk(val text: String, val lang: String)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null
    
    private val executor = Executors.newSingleThreadExecutor()
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    
    // TalkBack တွက် Standard Sample Rate (24kHz is good for mixing quality)
    private val TARGET_SAMPLE_RATE = 24000

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service", "Cherry SME TTS Service Created")
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherryTTS:WakeLock")
        wakeLock?.setReferenceCounted(false)
        
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        // Engine များကို Initialize လုပ်ခြင်း
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
                    // Listener အလွတ်ထားတာ အဆင်ပြေပါတယ် (Pipe နဲ့ဖမ်းမှာမို့လို့)
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

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
        // Stop flag အရင် reset လုပ်
        isStopped.set(true)
        currentTask?.cancel(true)
        isStopped.set(false)
        
        if (wakeLock?.isHeld == false) wakeLock?.acquire(60000)

        currentTask = executor.submit {
            try {
                // 1. Chunk ခွဲခြင်း
                val chunks = LanguageUtils.splitHelper(text) 
                
                // *** FIX 1: Chunk မရှိရင် (Space တွေကြီးပဲဖြစ်နေရင်) ချက်ချင်း return ***
                // TalkBack က start() မလာဘဲ done() လာရင် Error တက်တတ်လို့ အသံတိတ် start တစ်ခုပြပေးရမယ်
                if (chunks.isEmpty()) {
                    AppLogger.log("Split", "0 chunks - Sending silent success")
                    callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callback?.done()
                    return@submit
                }

                AppLogger.log("Split", "Text split into ${chunks.size} chunks")
                var hasStartedCallback = false

                // Chunk တစ်ခုချင်းစီကို အစဉ်လိုက် Process လုပ်မယ်
                for ((index, chunk) in chunks.withIndex()) {
                    if (isStopped.get()) break
                    
                    val engine = getEngine(chunk.lang) ?: continue
                    
                    // *** FIX 2: Chunk တစ်ခု စတိုင်း Rate/Pitch ကို သေချာပြန်ညှိပေးရမယ် ***
                    applyRateAndPitch(engine, chunk.lang, request)

                    // Audio Process လုပ်မယ်
                    val success = processStream(engine, chunk.text, callback, hasStartedCallback)
                    
                    // ပထမဆုံး Chunk အောင်မြင်တာနဲ့ Start ခေါ်ပြီးသားလို့ မှတ်ထားမယ်
                    if (success) hasStartedCallback = true
                }
                
                // ဘာမှ အသံမထွက်ခဲ့ရင်တောင် TalkBack Error မတက်အောင် start ခေါ်ပေးရမယ်
                if (!hasStartedCallback) {
                     callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

            } catch (e: Exception) {
                AppLogger.log("WorkerError", "Exception: ${e.message}")
                e.printStackTrace()
            } finally {
                if (!isStopped.get()) {
                    callback?.done()
                }
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }
    
    private fun applyRateAndPitch(engine: TextToSpeech, lang: String, request: SynthesisRequest?) {
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        // System Request (TalkBack ကပို့လိုက်တဲ့ Rate/Pitch)
        // Default 100 လို့ထားပေမဲ့ TalkBack က တစ်ခါတလေ 100 မက ပို့တတ်တယ်
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100
        
        // User Settings (SeekBar in App)
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

        // Calculation: (System * User) / 10000 -> 1.0f is Normal
        val finalRate = (sysRate * userRate) / 10000.0f
        val finalPitch = (sysPitch * userPitch) / 10000.0f
        
        // Engine ထဲကို ထည့်မယ်
        engine.setSpeechRate(finalRate)
        engine.setPitch(finalPitch)
    }

    private fun processStream(engine: TextToSpeech, text: String, callback: SynthesisCallback?, alreadyStarted: Boolean): Boolean {
        var didStart = alreadyStarted
        val uuid = UUID.randomUUID().toString()
        var readSide: ParcelFileDescriptor? = null
        var writeSide: ParcelFileDescriptor? = null

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            readSide = pipe[0]
            writeSide = pipe[1]

            val params = Bundle()
            // Request ID ထည့်ပေးရင် တချို့ Engine တွေမှာ ပိုငြိမ်တယ်
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)
            
            val result = engine.synthesizeToFile(text, params, writeSide, uuid)
            
            // Write side ကို close လုပ်မှ Read side က EOF (End of File) ကိုသိမှာ
            writeSide.close()
            writeSide = null 

            if (result != TextToSpeech.SUCCESS) {
                return didStart // Fail ဖြစ်ရင် ဘာမှဆက်မလုပ်ဘူး
            }

            val inputStream = FileInputStream(readSide.fileDescriptor)
            val headerBuffer = ByteArray(44)
            var headerBytesRead = 0
            
            // Header ဖတ်ခြင်း (WAV format ဖြစ်ဖို့များတယ်)
            while (headerBytesRead < 44) {
                 val c = inputStream.read(headerBuffer, headerBytesRead, 44 - headerBytesRead)
                 if (c == -1) break
                 headerBytesRead += c
            }

            if (headerBytesRead >= 44) {
                 val sourceRate = getSampleRateFromWav(headerBuffer)

                 // ပထမဆုံးအကြိမ်ဆိုရင် TalkBack ကို start() လှမ်းပြောမယ်
                 if (!didStart) {
                     // အရေးကြီးတယ် - Target Rate နဲ့ပဲ အမြဲ Start လုပ်ရမယ်
                     callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                     didStart = true
                 }
                 
                 val pcmBuffer = ByteArray(4096)
                 
                 while (true) {
                     if (isStopped.get()) break
                     val bytesRead = inputStream.read(pcmBuffer)
                     if (bytesRead == -1) break
                     
                     if (bytesRead > 0) {
                         // Resample လုပ်ပြီးမှ TalkBack ဆီပို့မယ်
                         val outputBytes = if (sourceRate != TARGET_SAMPLE_RATE) {
                             AudioResampler.resampleChunk(pcmBuffer, bytesRead, sourceRate, TARGET_SAMPLE_RATE)
                         } else {
                             pcmBuffer.copyOfRange(0, bytesRead)
                         }

                         if (outputBytes.isNotEmpty()) {
                             callback?.audioAvailable(outputBytes, 0, outputBytes.size)
                         }
                     }
                 }
            } 
        } catch (e: Exception) {
            AppLogger.log("PipeError", "Error: ${e.message}")
        } finally {
            try { readSide?.close() } catch (e: Exception) {}
            try { writeSide?.close() } catch (e: Exception) {}
        }
        return didStart
    }
    
    private fun getSampleRateFromWav(header: ByteArray): Int {
        if (header.size < 28) return 16000 
        // WAV header byte 24-27 is Sample Rate
        val rate = (header[24].toInt() and 0xFF) or
               ((header[25].toInt() and 0xFF) shl 8) or
               ((header[26].toInt() and 0xFF) shl 16) or
               ((header[27].toInt() and 0xFF) shl 24)
        return if (rate > 0) rate else 16000 
    }

    private fun getEngine(lang: String): TextToSpeech? {
        return when (lang) {
            "SHAN" -> if (shanEngine != null) shanEngine else englishEngine
            "MYANMAR" -> if (burmeseEngine != null) burmeseEngine else englishEngine
            else -> englishEngine
        }
    }

    override fun onStop() {
        isStopped.set(true)
        // Stop လုပ်ရင် Callback တွေကို ချက်ချင်းရပ်ပစ်မယ်
        currentTask?.cancel(true)
    }
    
    override fun onDestroy() {
        isStopped.set(true)
        executor.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onGetVoices(): List<Voice> { return listOf() }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onGetLanguage(): Array<String> { return arrayOf("eng", "USA", "") }
}

