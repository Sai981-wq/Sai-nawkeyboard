package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.UUID
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""

    private lateinit var prefs: SharedPreferences

    // ရှုပ်ထွေးတဲ့ Thread Pool တွေ မသုံးတော့ဘူး
    // ရိုးရှင်းတဲ့ Boolean Flag လေးတစ်ခုပဲ သုံးမယ်
    @Volatile private var mIsStopped = false
    
    // Pipe တွေကို ဒီမှာခဏသိမ်းမယ် (Stop လုပ်ရင် ပိတ်ပစ်ဖို့)
    @Volatile private var currentReadFd: ParcelFileDescriptor? = null
    @Volatile private var currentWriteFd: ParcelFileDescriptor? = null

    private val OUTPUT_HZ = 24000 
    private var currentInputRate = 0

    override fun onCreate() {
        super.onCreate()
        // Engine တွေ Load လုပ်မယ်
        try {
            prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName, Locale("shn")) { shanEngine = it }
            burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }
            englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName, Locale.US) { englishEngine = it }
        } catch (e: Exception) { }
    }

    private fun initEngine(pkg: String?, locale: Locale, onSuccess: (TextToSpeech) -> Unit) {
        if (pkg.isNullOrEmpty()) return
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try { tempTTS?.language = locale } catch (e: Exception) {}
                    onSuccess(tempTTS!!)
                }
            }, pkg)
        } catch (e: Exception) { }
    }

    // Android System က သူ့အလိုလို တန်းစီပြီး ဒီ Function ကို ခေါ်ပေးပါတယ်
    // ကျွန်တော်တို့က ဒီထဲမှာ ဝင်လာတာကို ဖတ်ပေးလိုက်ရုံပါပဲ
    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        if (text.isNullOrEmpty() || callback == null) return

        mIsStopped = false // အလုပ်စပြီ
        
        // အသစ်စတိုင်း C++ Memory ကို ရှင်းမယ် (Deadlock မဖြစ်အောင်)
        resetSonicStream()

        val rawChunks = TTSUtils.splitHelper(text)
        
        synchronized(callback) {
            callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
        }

        for (chunk in rawChunks) {
            // Stop အမိန့်လာရင် ချက်ချင်း ရပ်မယ်
            if (mIsStopped) break

            val engineData = getEngineDataForLang(chunk.lang)
            val engine = engineData.engine ?: continue
            val engineInputRate = determineInputRate(engineData.pkgName)

            // Engine Setting ချိန်မယ်
            try {
                engine.setSpeechRate(request.speechRate / 100.0f)
                engine.setPitch(request.pitch / 100.0f)
            } catch (e: Exception) {}

            // Sonic Config (Rate မတူမှ Init လုပ်မယ်)
            if (currentInputRate != engineInputRate) {
                AudioProcessor.initSonic(engineInputRate, 1)
                currentInputRate = engineInputRate
            } else {
                AudioProcessor.flush() 
            }
            
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, getVolumeCorrection(engineData.pkgName))
            val uuid = UUID.randomUUID().toString()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

            // အသံဖတ်မယ်
            processAudioChunk(engine, params, chunk.text, callback, uuid)
        }
        
        // ပြီးသွားရင် Done ခေါ်မယ် (Stop လုပ်ခံရရင် မခေါ်ဘူး)
        if (!mIsStopped) {
            callback.done()
        }
    }

    private fun processAudioChunk(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            currentReadFd = pipe[0]
            currentWriteFd = pipe[1]

            // Writer Thread (Engine က File ထဲရေးဖို့ သီးသန့် Thread တစ်ခုလိုလို့ပါ)
            // ဒါပေမဲ့ Executor တွေ Queue တွေ မသုံးတော့ပါဘူး
            val writerThread = Thread {
                try {
                    engine.synthesizeToFile(text, params, currentWriteFd!!, uuid)
                } catch (e: Exception) {
                } finally {
                    try { currentWriteFd?.close() } catch (e: Exception) {}
                }
            }
            writerThread.start()

            // Main Thread မှာပဲ ဖတ်မယ် (Reader)
            val inBuffer = ByteBuffer.allocateDirect(4096)
            val outBuffer = ByteArray(8192)
            var fis: FileInputStream? = null
            
            try {
                fis = FileInputStream(currentReadFd!!.fileDescriptor)
                val channel = fis.channel
                
                while (!mIsStopped) {
                    inBuffer.clear()
                    val read = channel.read(inBuffer)
                    
                    if (read == -1) {
                        // Chunk ပြီးသွားရင် လက်ကျန်ရှင်းထုတ်
                        AudioProcessor.flush()
                        var flushBytes = 1
                        while (flushBytes > 0 && !mIsStopped) {
                            flushBytes = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                            if (flushBytes > 0) sendAudioToSystem(outBuffer, flushBytes, callback)
                        }
                        break
                    }
                    
                    if (read > 0) {
                        var bytesProcessed = AudioProcessor.processAudio(inBuffer, read, outBuffer)
                        if (bytesProcessed > 0) sendAudioToSystem(outBuffer, bytesProcessed, callback)
                        
                        while (bytesProcessed > 0 && !mIsStopped) {
                            bytesProcessed = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                            if (bytesProcessed > 0) sendAudioToSystem(outBuffer, bytesProcessed, callback)
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { fis?.close() } catch (e: Exception) {}
            }

            // Writer ပြီးတဲ့အထိ စောင့်မယ် (Join)
            try { writerThread.join() } catch (e: Exception) {}

        } catch (e: Exception) {
        } finally {
            try { currentWriteFd?.close() } catch (e: Exception) {}
            try { currentReadFd?.close() } catch (e: Exception) {}
        }
    }

    private fun resetSonicStream() {
        try {
            AudioProcessor.stop()
            currentInputRate = 0
        } catch (e: Exception) {}
    }

    // System က Stop လုပ်ခိုင်းရင် ဒီကောင်အလုပ်လုပ်မယ်
    override fun onStop() {
        mIsStopped = true // Flag ထောင်လိုက်တာနဲ့ Loop တွေအကုန်ရပ်မယ်
        
        // Pipe တွေကို အတင်းပိတ်မယ် (ဒါမှ Read/Write တွေလွတ်ပြီး Thread သေမှာ)
        try { currentWriteFd?.close() } catch (e: Exception) {}
        try { currentReadFd?.close() } catch (e: Exception) {}
        
        // Engine တွေကိုရပ်မယ်
        try {
            shanEngine?.stop()
            burmeseEngine?.stop()
            englishEngine?.stop()
        } catch (e: Exception) {}
        
        AudioProcessor.stop()
    }
    
    private fun sendAudioToSystem(data: ByteArray, length: Int, callback: SynthesisCallback) {
        if (length <= 0) return
        synchronized(callback) {
            try {
                var offset = 0
                while (offset < length) {
                    val chunkLen = min(4096, length - offset)
                    callback.audioAvailable(data, offset, chunkLen)
                    offset += chunkLen
                }
            } catch (e: Exception) {}
        }
    }

    // Helper functions (Rate, PkgName, Volume) - အရင်အတိုင်း
    private fun determineInputRate(pkgName: String): Int {
        val lowerPkg = pkgName.lowercase(Locale.ROOT)
        return when {
            lowerPkg.contains("com.shan.tts") -> 22050
            lowerPkg.contains("eloquence") -> 11025
            lowerPkg.contains("espeak") || lowerPkg.contains("shan") -> 22050
            lowerPkg.contains("google") -> 24000
            lowerPkg.contains("samsung") -> 22050 
            lowerPkg.contains("vocalizer") -> 22050
            else -> 16000 
        }
    }

    data class EngineData(val engine: TextToSpeech?, val pkgName: String, val rateKey: String, val pitchKey: String)
    private fun getEngineDataForLang(lang: String): EngineData {
        return when (lang) {
            "SHAN" -> EngineData(if (shanEngine != null) shanEngine else englishEngine, shanPkgName, "rate_shan", "pitch_shan")
            "MYANMAR" -> EngineData(if (burmeseEngine != null) burmeseEngine else englishEngine, burmesePkgName, "rate_burmese", "pitch_burmese")
            else -> EngineData(englishEngine, englishPkgName, "rate_english", "pitch_english")
        }
    }

    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return when {
            l.contains("vocalizer") -> 0.85f; l.contains("eloquence") -> 0.6f; else -> 1.0f
        }
    }

    override fun onDestroy() {
        onStop()
        super.onDestroy()
    }

    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

