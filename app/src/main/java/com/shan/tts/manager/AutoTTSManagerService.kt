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
import android.speech.tts.Voice
import java.io.FileInputStream
import java.nio.ByteBuffer
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

    @Volatile private var mIsStopped = false
    
    // Dynamic Language
    private var currentLanguage: String = "eng"
    private var currentCountry: String = "USA"

    private val OUTPUT_HZ = 24000 
    private var currentInputRate = 0

    override fun onCreate() {
        super.onCreate()
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

    @Synchronized
    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        if (text.isNullOrEmpty() || callback == null) return

        mIsStopped = false 

        try {
            resetSonicStream()

            val rawChunks = TTSUtils.splitHelper(text)
            
            // Jieshuo မှလာသော မူရင်း Parameters များ
            val originalParams = request?.params ?: Bundle()
            
            synchronized(callback) {
                callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
            }

            for (chunk in rawChunks) {
                if (mIsStopped) break

                val engineData = getEngineDataForLang(chunk.lang)
                val engine = engineData.engine ?: continue
                val engineInputRate = determineInputRate(engineData.pkgName)

                // ---------------------------------------------------------
                // RATE & PITCH ချိန်ညှိမှုများကို အကုန်ဖြုတ်ထားပါသည်
                // ---------------------------------------------------------
                // ယခုအခါ Engine သည် သူ့မူရင်းအသံ (Default) အတိုင်းသာ ထွက်ပါမည်။
                // User Setting နှင့် Jieshuo Setting များကို လျစ်လျူရှုထားပါမည်။
                
                if (currentInputRate != engineInputRate) {
                    AudioProcessor.initSonic(engineInputRate, 1)
                    currentInputRate = engineInputRate
                } else {
                    AudioProcessor.flush() 
                }
                
                // Bundle ကို ပြုပြင်ခြင်းမရှိဘဲ (No Remove) မူရင်းအတိုင်း လက်ဆင့်ကမ်းပါမည်
                val engineParams = Bundle(originalParams)
                
                // Volume Correction တစ်ခုတော့ ထားခဲ့ပါမယ် (အသံအရမ်းတိုးမှာစိုးလို့ပါ)
                val volCorrection = getVolumeCorrection(engineData.pkgName)
                if (volCorrection != 1.0f) {
                    engineParams.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volCorrection)
                }
                
                // Utterance ID မပါရင် ထည့်ပေးရပါမယ် (မထည့်ရင် Error တက်နိုင်လို့ပါ)
                var uuid = originalParams.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID)
                if (uuid == null) {
                    uuid = UUID.randomUUID().toString()
                    engineParams.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)
                }

                processAudioChunk(engine, engineParams, chunk.text, callback, uuid)
            }
            
            if (!mIsStopped) {
                callback.done()
            }
        } finally {
        }
    }

    private fun processAudioChunk(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        var pipe: Array<ParcelFileDescriptor>? = null
        try {
            pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]

            val writerThread = Thread {
                try {
                    engine.synthesizeToFile(text, params, writeFd, uuid)
                } catch (e: Exception) {
                } finally {
                    try { writeFd.close() } catch (e: Exception) {}
                }
            }
            writerThread.start()

            val inBuffer = ByteBuffer.allocateDirect(4096)
            val outBuffer = ByteArray(8192)
            
            FileInputStream(readFd.fileDescriptor).use { fis ->
                val channel = fis.channel
                while (!mIsStopped) {
                    inBuffer.clear()
                    val readBytes = channel.read(inBuffer)

                    if (readBytes == -1) {
                        AudioProcessor.flush()
                        var flushLength: Int
                        do {
                            flushLength = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                            if (flushLength > 0) {
                                sendAudioToSystem(outBuffer, flushLength, callback)
                            }
                        } while (flushLength > 0 && !mIsStopped)
                        break
                    }

                    if (readBytes > 0) {
                        var processed = AudioProcessor.processAudio(inBuffer, readBytes, outBuffer)
                        if (processed > 0) {
                            sendAudioToSystem(outBuffer, processed, callback)
                        }
                        
                        do {
                            processed = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                            if (processed > 0) {
                                sendAudioToSystem(outBuffer, processed, callback)
                            }
                        } while (processed > 0 && !mIsStopped)
                    }
                }
            }
            
            try { writerThread.join(500) } catch (e: Exception) {}

        } catch (e: Exception) {
        } finally {
            try { pipe?.get(0)?.close() } catch (e: Exception) {}
            try { pipe?.get(1)?.close() } catch (e: Exception) {}
        }
    }

    private fun sendAudioToSystem(data: ByteArray, length: Int, callback: SynthesisCallback) {
        if (length <= 0) return
        val maxBufferSize = callback.maxBufferSize 
        var offset = 0
        synchronized(callback) {
            try {
                while (offset < length) {
                    val bytesToWrite = min(maxBufferSize, length - offset)
                    callback.audioAvailable(data, offset, bytesToWrite)
                    offset += bytesToWrite
                }
            } catch (e: Exception) {}
        }
    }

    private fun resetSonicStream() {
        try {
            AudioProcessor.stop()
            currentInputRate = 0
        } catch (e: Exception) {}
    }

    override fun onStop() {
        mIsStopped = true 
        try {
            shanEngine?.stop()
            burmeseEngine?.stop()
            englishEngine?.stop()
        } catch (e: Exception) {}
        AudioProcessor.stop()
    }
    
    override fun onGetVoices(): List<Voice> {
        return listOf(
            Voice("shn-MM", Locale("shn", "MM"), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, setOf()),
            Voice("my-MM", Locale("my", "MM"), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, setOf()),
            Voice("en-US", Locale.US, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, setOf())
        )
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val checkLang = lang?.lowercase(Locale.ROOT) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        return if (checkLang == "shn" || checkLang == "my" || checkLang == "en") {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result == TextToSpeech.LANG_COUNTRY_AVAILABLE || result == TextToSpeech.LANG_AVAILABLE) {
            currentLanguage = lang ?: "eng"
            currentCountry = country ?: ""
            return result
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf(currentLanguage, currentCountry, "")
    }

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
}

