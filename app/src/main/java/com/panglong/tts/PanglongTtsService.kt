package com.panglong.tts

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlin.math.min

class PanglongTtsService : TextToSpeechService() {
    // Thread Safe ဖြစ်အောင် Lock ထည့်ထားခြင်း
    private val lock = Any()
    private var ttsEngines = mutableMapOf<String, OfflineTts>()

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("🔵 [Lifecycle] Service Created. Initializing...")
    }

    // Model ခေါ်ယူခြင်းကို တန်းစီစနစ် (Synchronized) ဖြင့် ပြုလုပ်ခြင်း
    private fun getOrLoadModel(langKey: String): OfflineTts? {
        synchronized(lock) {
            if (ttsEngines.containsKey(langKey)) {
                return ttsEngines[langKey]
            }

            val (modelFile, tokensFile) = when (langKey) {
                "shan" -> Pair("shan_model.onnx", "shan_tokens.txt")
                "eng" -> Pair("english_model.onnx", "english_tokens.txt")
                else -> Pair("burmese_model.onnx", "burmese_tokens.txt")
            }

            return try {
                AppLogger.log("📂 [Check] Checking: $modelFile")
                val assetFiles = assets.list("") ?: emptyArray()
                
                if (!assetFiles.contains(modelFile) || !assetFiles.contains(tokensFile)) {
                    AppLogger.log("❌ [Error] Missing: $modelFile")
                    return null
                }

                // RAM မလောက်ရင် Crash မဖြစ်အောင် အရင်ရှင်းထုတ်မယ်
                System.gc()
                
                AppLogger.log("⏳ [Load] Loading $langKey (Please wait)...")
                
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
                ttsEngines[langKey] = tts
                AppLogger.log("✅ [Success] Loaded: $langKey")
                tts
            } catch (e: Throwable) {
                // Exception သာမက Native Error များပါ ဖမ်းယူခြင်း
                AppLogger.log("🔥 [CRASH PREVENTED] Load Failed: ${e.message}")
                e.printStackTrace()
                null
            }
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
        AppLogger.log("📥 [System] Select: $lang-$country")
        
        // Background Thread ဖြင့် Model ကို ကြိုတင်မတင်တော့ဘဲ လိုအပ်မှ ခေါ်သုံးစေခြင်း
        // ဒါက Crash ဖြစ်နိုင်ခြေကို လျှော့ချပေးပါတယ်
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetLanguage(): Array<String> = arrayOf("mya", "MM", "")
    override fun onStop() {
        AppLogger.log("🛑 [Stop] Requested.")
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        if (text.isBlank()) {
            callback?.done()
            return
        }

        val lang = request?.language ?: "mya"
        AppLogger.log("🗣️ [Req] '$text' ($lang)")

        synchronized(lock) {
            val engineKey = when {
                lang.contains("shn") || text.contains("shan_char_check") -> "shan"
                lang.contains("en") -> "eng"
                else -> "mya"
            }

            val tts = getOrLoadModel(engineKey) ?: getOrLoadModel("mya")

            if (tts != null) {
                // English Model တချို့က 16000Hz ဖြစ်တတ်လို့ 22050Hz နဲ့ မကိုက်ရင် Crash တတ်ပါတယ်
                // Default အနေနဲ့ 22050 ကို ထားထားပေးမယ့် အောက်က Try-Catch က ကာကွယ်ပေးပါလိမ့်မယ်
                val sampleRate = 22050 
                callback?.start(sampleRate, 16, 1)

                try {
                    val generated = tts.generate(text)
                    val samples = generated.samples

                    if (samples.isNotEmpty()) {
                        val audioBytes = floatArrayToByteArray(samples)
                        
                        // Chunking System (Buffer Overflow ကာကွယ်ရန်)
                        val maxBufferSize = 4096
                        var offset = 0
                        while (offset < audioBytes.size) {
                            val bytesToWrite = min(maxBufferSize, audioBytes.size - offset)
                            callback?.audioAvailable(audioBytes, offset, bytesToWrite)
                            offset += bytesToWrite
                        }
                        AppLogger.log("✅ Sent ${audioBytes.size} bytes")
                    } else {
                        AppLogger.log("⚠️ Generated silence.")
                    }
                    callback?.done()
                } catch (e: Throwable) {
                    AppLogger.log("🔥 [CRASH] During synthesis: ${e.message}")
                    callback?.error()
                }
            } else {
                AppLogger.log("❌ Engine NULL for $engineKey")
                callback?.error()
            }
        }
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
        AppLogger.log("🔴 Service Destroyed")
        ttsEngines.values.forEach { it.release() }
        super.onDestroy()
    }
}

