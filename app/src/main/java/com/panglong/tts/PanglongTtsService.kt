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
        AppLogger.log("Service Started. Lazy loading enabled.")
    }

    private fun getOrLoadModel(langKey: String): OfflineTts? {
        if (ttsEngines.containsKey(langKey)) {
            return ttsEngines[langKey]
        }

        val (modelFile, tokensFile) = when (langKey) {
            "shan" -> Pair("shan_model.onnx", "shan_tokens.txt")
            "eng" -> Pair("english_model.onnx", "english_tokens.txt")
            else -> Pair("burmese_model.onnx", "burmese_tokens.txt")
        }

        return try {
            val assetFiles = assets.list("") ?: emptyArray()
            if (!assetFiles.contains(modelFile) || !assetFiles.contains(tokensFile)) {
                AppLogger.log("Missing: $modelFile")
                return null
            }

            AppLogger.log("Loading $langKey...")
            
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
            AppLogger.log("Loaded: $langKey")
            tts
        } catch (e: Exception) {
            AppLogger.log("Error loading $langKey: ${e.message}")
            null
        }
    }

    override fun onGetLanguage(): Array<String> = arrayOf("mya", "MM", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onStop() {}

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val lang = request?.language ?: "mya"
        
        AppLogger.log("Req: $text")

        val engineKey = when {
            lang.contains("shn") || text.contains("shan_char_check") -> "shan"
            lang.contains("en") -> "eng"
            else -> "mya"
        }

        val tts = getOrLoadModel(engineKey) ?: getOrLoadModel("mya")

        if (tts != null) {
            // Sherpa VITS default is often 22050Hz
            callback?.start(22050, 16, 1)
            try {
                val generated = tts.generate(text)
                val samples = generated.samples
                
                if (samples.isNotEmpty()) {
                    val audioBytes = floatArrayToByteArray(samples)
                    
                    // Fix: Send audio in small chunks (4KB)
                    val maxBufferSize = 4096
                    var offset = 0
                    while (offset < audioBytes.size) {
                        val bytesToWrite = min(maxBufferSize, audioBytes.size - offset)
                        callback?.audioAvailable(audioBytes, offset, bytesToWrite)
                        offset += bytesToWrite
                    }
                    AppLogger.log("Sent ${audioBytes.size} bytes")
                }
                callback?.done()
            } catch (e: Exception) {
                AppLogger.log("TTS Error: ${e.message}")
                callback?.error()
            }
        } else {
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

