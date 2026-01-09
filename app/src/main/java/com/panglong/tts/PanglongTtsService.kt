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
    private var ttsEngines = mutableMapOf<String, OfflineTts>()

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("🔵 [Lifecycle] onCreate: Service Created")
        AppLogger.log("ℹ️ [Info] Lazy loading enabled. Waiting for requests...")
    }

    // Model ခေါ်ယူခြင်းနှင့် တည်ဆောက်ခြင်း လုပ်ငန်းစဉ်
    private fun getOrLoadModel(langKey: String): OfflineTts? {
        AppLogger.log("🔍 [ModelCheck] Requesting model for: $langKey")

        if (ttsEngines.containsKey(langKey)) {
            AppLogger.log("✅ [Cache] Using loaded model: $langKey")
            return ttsEngines[langKey]
        }

        val (modelFile, tokensFile) = when (langKey) {
            "shan" -> Pair("shan_model.onnx", "shan_tokens.txt")
            "eng" -> Pair("english_model.onnx", "english_tokens.txt")
            else -> Pair("burmese_model.onnx", "burmese_tokens.txt")
        }

        return try {
            AppLogger.log("📂 [FileCheck] Checking assets: $modelFile, $tokensFile")
            val assetFiles = assets.list("") ?: emptyArray()
            
            if (!assetFiles.contains(modelFile) || !assetFiles.contains(tokensFile)) {
                AppLogger.log("❌ [FileError] MISSING FILE: $modelFile or $tokensFile")
                return null
            }

            AppLogger.log("⏳ [Load] Initializing Sherpa-ONNX for $langKey...")
            
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
            AppLogger.log("✅ [Success] Model loaded: $langKey")
            tts
        } catch (e: Exception) {
            AppLogger.log("❌ [Exception] Load Failed ($langKey): ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // System က ဘာသာစကား ရမရ လာစစ်ဆေးသည့်အဆင့်
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val result = if (lang != null && (lang.contains("en") || lang.contains("my") || lang.contains("shn"))) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        // Log အရမ်းများမှာစိုးရင် ဒီလိုင်းကို ပိတ်ထားနိုင်ပါတယ်
        // AppLogger.log("❓ [CheckLang] $lang-$country -> Result: $result")
        return result
    }

    // System က ဘာသာစကားကို ရွေးချယ်လိုက်သည့်အဆင့်
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        AppLogger.log("📥 [SystemSelect] onLoadLanguage: $lang-$country")
        
        // Model ကြိုတင်ပြင်ဆင်ခြင်း
        if (lang?.contains("en") == true) getOrLoadModel("eng")
        else if (lang?.contains("shn") == true) getOrLoadModel("shan")
        else getOrLoadModel("mya")

        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetLanguage(): Array<String> {
        AppLogger.log("ℹ️ [GetLang] System requested default language")
        return arrayOf("mya", "MM", "")
    }

    override fun onStop() {
        AppLogger.log("🛑 [Stop] Synthesis stopped by user/system")
    }

    // အဓိက စာဖတ်သည့် လုပ်ငန်းစဉ်
    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val lang = request?.language ?: "mya"
        
        AppLogger.log("🗣️ [Speak] Req: '$text' (Lang: $lang)")

        // ၁။ ဘာသာစကားခွဲခြားခြင်း
        val engineKey = when {
            lang.contains("shn") || text.contains("shan_char_check") -> "shan"
            lang.contains("en") -> "eng"
            else -> "mya"
        }
        AppLogger.log("⚙️ [Engine] Selected Engine: $engineKey")

        // ၂။ Model ရယူခြင်း
        val tts = getOrLoadModel(engineKey) ?: getOrLoadModel("mya")

        if (tts != null) {
            // ၃။ Audio စတင်ခြင်း
            AppLogger.log("🎵 [Audio] Start: 22050Hz, 16bit, Mono")
            callback?.start(22050, 16, 1)

            try {
                // ၄။ အသံထုတ်လုပ်ခြင်း (Generate)
                AppLogger.log("⚡ [Sherpa] Generating audio...")
                val generated = tts.generate(text)
                val samples = generated.samples
                AppLogger.log("📊 [Sherpa] Generated ${samples.size} float samples")

                if (samples.isNotEmpty()) {
                    // ၅။ Byte ပြောင်းခြင်း
                    val audioBytes = floatArrayToByteArray(samples)
                    AppLogger.log("📦 [Data] Converted to ${audioBytes.size} bytes")
                    
                    // ၆။ အပိုင်းလိုက်ခွဲပို့ခြင်း (Chunking) - Buffer Error ကာကွယ်ရန်
                    val maxBufferSize = 4096
                    var offset = 0
                    var chunkCount = 0
                    
                    AppLogger.log("🚀 [Stream] Starting chunks loop...")
                    while (offset < audioBytes.size) {
                        val bytesToWrite = min(maxBufferSize, audioBytes.size - offset)
                        callback?.audioAvailable(audioBytes, offset, bytesToWrite)
                        offset += bytesToWrite
                        chunkCount++
                    }
                    AppLogger.log("🏁 [Stream] Sent $chunkCount chunks successfully")
                } else {
                    AppLogger.log("⚠️ [Warning] Generated samples are empty!")
                }
                
                // ၇။ ပြီးဆုံးခြင်း
                callback?.done()
                AppLogger.log("✅ [Done] Synthesis Complete")

            } catch (e: Exception) {
                AppLogger.log("❌ [Error] Synthesis Failed: ${e.message}")
                e.printStackTrace()
                callback?.error()
            }
        } else {
            AppLogger.log("❌ [Fatal] Engine is NULL for $engineKey")
            callback?.error()
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
        AppLogger.log("🔴 [Lifecycle] onDestroy: Releasing resources...")
        ttsEngines.values.forEach { it.release() }
        super.onDestroy()
    }
}

