package com.panglong.tts

import android.content.Intent
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlin.math.min
import java.util.concurrent.Executors

class PanglongTtsService : TextToSpeechService() {
    private val lock = Any()
    
    // Model သိမ်းဆည်းရာ
    private var activeModelKey: String? = null
    private var activeTts: OfflineTts? = null
    
    // Background Worker
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var isStopped = false
    @Volatile private var isFilesReading = false // ဖိုင်ဖတ်နေလား စစ်မယ်

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("✅ Service Created.")
        // English ကို ချက်ချင်းတင်မယ်
        triggerModelLoad("eng")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // Model တင်ခိုင်းသည့် Function (Thread အသစ်ဖြင့်)
    private fun triggerModelLoad(langKey: String) {
        synchronized(lock) {
            // လက်ရှိသုံးနေတာနဲ့ တူရင် ဘာမှလုပ်စရာမလို
            if (activeModelKey == langKey && activeTts != null) return
            // ဖိုင်ဖတ်နေတုန်းဆိုရင် ခဏစောင့်ခိုင်းမယ် (ထပ်မတင်ဘူး)
            if (isFilesReading) return 
        }

        executor.submit {
            loadModelReal(langKey)
        }
    }

    private fun loadModelReal(langKey: String) {
        isFilesReading = true
        AppLogger.log("♻️ START Loading: $langKey")

        val (modelFile, tokensFile) = when (langKey) {
            "shan" -> Pair("shan_model.onnx", "shan_tokens.txt")
            "eng" -> Pair("english_model.onnx", "english_tokens.txt")
            else -> Pair("burmese_model.onnx", "burmese_tokens.txt")
        }

        try {
            // ၁။ ဖိုင်ရှိမရှိ အရင်စစ်မယ်
            val assetFiles = assets.list("") ?: emptyArray()
            if (!assetFiles.contains(modelFile)) {
                AppLogger.log("❌ File Not Found: $modelFile")
                isFilesReading = false
                return
            }

            // ၂။ RAM ရှင်းမယ်
            synchronized(lock) {
                if (activeModelKey != langKey) {
                    activeTts?.release()
                    activeTts = null
                    System.gc()
                }
            }

            AppLogger.log("⏳ Reading $langKey from Storage...")
            
            // ၃။ Model တည်ဆောက်မယ် (Sherpa-ONNX)
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile,
                        tokens = tokensFile,
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f
                    ),
                    numThreads = 1,
                    provider = "cpu"
                )
            )
            // ဒီအဆင့်က ကြာတတ်ပါတယ် (Lock မခံပါဘူး)
            val tts = OfflineTts(assets, config)
            
            // ၄။ ပြီးမှ Lock ခံပြီး သိမ်းမယ်
            synchronized(lock) {
                activeTts = tts
                activeModelKey = langKey
            }
            AppLogger.log("✅ MODEL READY: $langKey")
            
        } catch (e: Throwable) {
            AppLogger.log("🔥 Load Error: ${e.message}")
            e.printStackTrace()
        } finally {
            isFilesReading = false // ပြီးသွားပြီ (သို့) Error တက်လည်း ပြန်ဖွင့်ပေးမယ်
        }
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (lang != null && (lang.contains("en") || lang.contains("my") || lang.contains("shn"))) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val key = if (lang?.contains("en") == true) "eng" else if (lang?.contains("shn") == true) "shan" else "mya"
        triggerModelLoad(key)
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetLanguage(): Array<String> = arrayOf("mya", "MM", "")

    override fun onStop() {
        isStopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        if (text.isBlank()) { callback?.done(); return }
        
        isStopped = false
        val lang = request?.language ?: "mya"
        val engineKey = when {
            lang.contains("shn") || text.contains("shan_char_check") -> "shan"
            lang.contains("en") -> "eng"
            else -> "mya"
        }

        var tts: OfflineTts? = null
        
        // Model အဆင်သင့်ဖြစ်မဖြစ် စစ်ဆေးမယ်
        synchronized(lock) {
            if (activeModelKey == engineKey && activeTts != null) {
                tts = activeTts
            }
        }

        // Model မရှိရင် (Silence Trick)
        if (tts == null) {
            // Log မှာ မပွားအောင် နည်းနည်းထိန်းမယ်
            if (!isFilesReading) {
                 AppLogger.log("⚠️ Retrying load for $engineKey...")
                 triggerModelLoad(engineKey)
            }
            // Crash မဖြစ်အောင် Silence ပို့မယ်
            playSilence(callback)
            return
        }

        // Model ရှိရင် အသံထွက်မယ်
        try {
            val shortText = if (text.length > 15) text.substring(0, 15) + "..." else text
            AppLogger.log("🗣️ Speaking: $shortText")
            
            val generated = tts!!.generate(text)
            val samples = generated.samples
            val sampleRate = generated.sampleRate

            if (isStopped) { safeError(callback); return }

            callback?.start(sampleRate, 16, 1)
            if (samples.isNotEmpty()) {
                val audioBytes = floatArrayToByteArray(samples)
                val maxBufferSize = 4096
                var offset = 0
                while (offset < audioBytes.size) {
                    if (isStopped) break
                    val bytesToWrite = min(maxBufferSize, audioBytes.size - offset)
                    callback?.audioAvailable(audioBytes, offset, bytesToWrite)
                    offset += bytesToWrite
                }
            }
            callback?.done()
        } catch (e: Throwable) {
            AppLogger.log("⚠️ TTS Error: ${e.message}")
            playSilence(callback) // Error တက်ရင်လည်း Silence နဲ့ကာကွယ်မယ်
        }
    }

    private fun playSilence(callback: SynthesisCallback?) {
        try {
            callback?.start(16000, 16, 1)
            val silence = ByteArray(8000) // 0.5 စက္ကန့်စာ
            callback?.audioAvailable(silence, 0, silence.size)
            callback?.done()
        } catch (e: Throwable) { }
    }

    private fun safeError(callback: SynthesisCallback?) {
        try { callback?.error() } catch (e: Throwable) {}
    }

    private fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val bytes = ByteArray(floats.size * 2)
        for (i in floats.indices) {
            val shortVal = (floats[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
            bytes[i * 2] = (shortVal.toInt() and 0x00FF).toByte()
            bytes[i * 2 + 1] = ((shortVal.toInt() shr 8) and 0x00FF).toByte()
        }
        return bytes
    }
    
    override fun onDestroy() {
        activeTts?.release()
        executor.shutdown()
        super.onDestroy()
    }
}

