package com.panglong.tts

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

class PanglongTtsService : TextToSpeechService() {
    private var ttsEngines = mutableMapOf<String, OfflineTts>()

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service Created. Loading models...")
        initModel("shan", "shan_model.onnx", "shan_tokens.txt")
        initModel("mya", "burmese_model.onnx", "burmese_tokens.txt")
        initModel("eng", "english_model.onnx", "english_tokens.txt")
    }

    private fun initModel(key: String, model: String, tokens: String) {
        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = model,
                        tokens = tokens,
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f
                    ),
                    numThreads = 1,
                    provider = "cpu"
                )
            )
            ttsEngines[key] = OfflineTts(assets, config)
            AppLogger.log("Loaded: $key")
        } catch (e: Exception) {
            AppLogger.log("Failed $key: ${e.message}")
        }
    }

    override fun onGetLanguage(): Array<String> = arrayOf("mya", "MM", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onStop() {}

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val lang = request?.language ?: "mya"
        
        AppLogger.log("Speaking ($lang): $text")

        val engineKey = when {
            lang.contains("shn") -> "shan"
            lang.contains("en") -> "eng"
            else -> "mya"
        }

        val tts = ttsEngines[engineKey] ?: ttsEngines["mya"]

        if (tts != null) {
            callback?.start(22050, 16, 1)
            try {
                val generated = tts.generate(text)
                val samples = generated.samples
                if (samples.isNotEmpty()) {
                    val audioBytes = floatArrayToByteArray(samples)
                    callback?.audioAvailable(audioBytes, 0, audioBytes.size)
                    AppLogger.log("generated ${samples.size} samples")
                }
                callback?.done()
            } catch (e: Exception) {
                AppLogger.log("Error: ${e.message}")
                callback?.error()
            }
        } else {
            AppLogger.log("Engine not found for $engineKey")
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
        ttsEngines.values.forEach { it.release() }
        super.onDestroy()
    }
}

