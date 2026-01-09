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
    private var isModelLoading = false 

    // Background Worker
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("✅ Service Created.")
        
        // [နည်းဗျူဟာ ၁] Warm-up: Service စဖွင့်တာနဲ့ English Model ကို ချက်ချင်းတင်မယ်
        // ဖုန်းဖွင့်ဖွင့်ချင်း TalkBack သုံးနိုင်အောင်ပါ
        preloadModel("eng")
    }

    // [နည်းဗျူဟာ ၃] Foreground/Sticky: Service ကို အရှင်မွေးခြင်း
    // RAM ပြည့်လို့ အသတ်ခံရရင်တောင် System ကို ပြန်ဖွင့်ခိုင်းမယ်
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.log("🛡️ Sticky Service Active")
        return START_STICKY
    }

    private fun preloadModel(langKey: String) {
        synchronized(lock) {
            if (activeModelKey == langKey && activeTts != null) return
            if (isModelLoading) return 
        }

        executor.submit {
            loadModelBlocking(langKey)
        }
    }

    private fun loadModelBlocking(langKey: String): OfflineTts? {
        synchronized(lock) {
            isModelLoading = true
            if (activeModelKey == langKey && activeTts != null) {
                isModelLoading = false
                return activeTts
            }
        }

        AppLogger.log("♻️ Switching to $langKey...")

        // RAM ရှင်း
        try {
            activeTts?.release()
            activeTts = null
            System.gc()
        } catch (e: Exception) { }

        val (modelFile, tokensFile) = when (langKey) {
            "shan" -> Pair("shan_model.onnx", "shan_tokens.txt")
            "eng" -> Pair("english_model.onnx", "english_tokens.txt")
            else -> Pair("burmese_model.onnx", "burmese_tokens.txt")
        }

        return try {
            val assetFiles = assets.list("") ?: emptyArray()
            if (!assetFiles.contains(modelFile)) {
                AppLogger.log("❌ Missing: $modelFile")
                synchronized(lock) { isModelLoading = false }
                return null
            }

            AppLogger.log("⏳ Loading $langKey (9-10s)...")
            
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
            val tts = OfflineTts(assets, config)
            
            synchronized(lock) {
                activeTts = tts
                activeModelKey = langKey
                isModelLoading = false
            }
            AppLogger.log("✅ Ready: $langKey")
            tts
        } catch (e: Throwable) {
            AppLogger.log("🔥 Load Failed: ${e.message}")
            synchronized(lock) { isModelLoading = false }
            null
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
        // System က မေးလာရင် Model ကို အသင့်ဖြစ်အောင် ပြင်ထားမယ်
        preloadModel(key)
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetLanguage(): Array<String> = arrayOf("mya", "MM", "")

    override fun onStop() {
        isStopped = true
        AppLogger.log("🛑 Stop Signal")
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

        // *** Silence Trick (Crash ကာကွယ်နည်း) ***
        var tts: OfflineTts? = null
        synchronized(lock) {
            // Model မရှိသေးရင် (သို့) တင်နေတုန်းဆိုရင်
            if (isModelLoading || activeModelKey != engineKey) {
                if (isModelLoading) {
                     AppLogger.log("⚠️ Loading... Sending Silence.")
                     playSilence(callback) // Crash မဖြစ်အောင် အသံတိတ်လွှတ်မယ်
                     return
                }
                // မတင်ရသေးရင် အခုတင်မယ်
                preloadModel(engineKey)
                playSilence(callback)
                return
            }
            tts = activeTts
        }

        if (tts != null) {
            try {
                // Log စာရှည်ရင် ဖြတ်မယ်
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
                AppLogger.log("⚠️ Error: ${e.message}")
                // Error တက်ရင်လည်း Silence လွှတ်လိုက်မယ် (Crash မဖြစ်အောင်)
                playSilence(callback) 
            }
        } else {
            playSilence(callback)
        }
    }

    // အသံတိတ် လွှတ်ပေးသည့် Function (အသက်ကယ်ဆေး)
    private fun playSilence(callback: SynthesisCallback?) {
        try {
            // 16000Hz, 16bit, Mono အသံတိတ်
            callback?.start(16000, 16, 1)
            val silence = ByteArray(3200) // 0.1 စက္ကန့်စာ အသံတိတ်
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

