package com.panglong.tts

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.Locale

class PanglongTtsService : TextToSpeechService() {
    private var ttsEngines = mutableMapOf<String, OfflineTts>()
    private val defaultLang = "mya"

    override fun onCreate() {
        super.onCreate()
        // Assets folder ထဲတွင် အောက်ပါဖိုင်များ ရှိနေရပါမည်
        initializeEngine("shan", "shan_model.onnx", "shan_tokens.txt")
        initializeEngine("mya", "burmese_model.onnx", "burmese_tokens.txt")
        initializeEngine("eng", "english_model.onnx", "english_tokens.txt")
    }

    private fun initializeEngine(langCode: String, model: String, tokens: String) {
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
                    debug = false,
                    provider = "cpu"
                )
            )
            val tts = OfflineTts(assets, config)
            ttsEngines[langCode] = tts
        } catch (e: Exception) {
            Log.e("PanglongTTS", "Failed to init $langCode: ${e.message}")
        }
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("mya", "MM", "")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (lang == "mya" || lang == "shn" || lang == "eng" || lang == "en") {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onStop() {
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val locale = request?.language ?: defaultLang
        
        val engineKey = when {
            locale.contains("shn") -> "shan"
            locale.contains("en") -> "eng"
            else -> "mya"
        }

        val tts = ttsEngines[engineKey] ?: ttsEngines["mya"]

        if (tts != null) {
            // Sample rate 16000 or 24000 (Sherpa default is usually 22050 or 24000 for VITS)
            // ဒီမှာ 24000Hz လို့ သတ်မှတ်ထားပါတယ်
            callback?.start(16000, 24000, 1)
            
            // ပြင်ဆင်ထားသည့်အပိုင်း
            val generatedAudio = tts.generate(text)
            val samples = generatedAudio.samples // အသံဖိုင်ကို ဒီနေရာမှာ ဆွဲထုတ်ပါတယ်

            if (samples.isNotEmpty()) {
                val audioBytes = FloatArrayToByteArray(samples)
                callback?.audioAvailable(audioBytes, 0, audioBytes.size)
            }
            callback?.done()
        } else {
            callback?.error()
        }
    }

    private fun FloatArrayToByteArray(floats: FloatArray): ByteArray {
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

