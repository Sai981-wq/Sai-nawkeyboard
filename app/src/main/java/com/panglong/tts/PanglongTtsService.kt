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
import java.util.concurrent.Executors

class PanglongTtsService : TextToSpeechService() {
    private val lock = Any()
    private var activeModelKey: String? = null
    private var activeTts: OfflineTts? = null
    
    // Background Thread (နောက်ကွယ်မှာ Model တင်ပေးမယ့်အရာ)
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("✅ Service Started (Async Mode)")
    }

    // Model ကို နောက်ကွယ်မှာ ဖြည်းဖြည်းချင်း တင်မယ့် function
    private fun preloadModel(langKey: String) {
        executor.submit {
            synchronized(lock) {
                if (activeModelKey == langKey && activeTts != null) return@synchronized
                loadModelBlocking(langKey)
            }
        }
    }

    // တကယ် Model တင်မယ့် function
    private fun loadModelBlocking(langKey: String): OfflineTts? {
        // ရှိပြီးသားဆို ပြန်သုံးမယ်
        if (activeModelKey == langKey && activeTts != null) return activeTts

        AppLogger.log("♻️ Switching to $langKey...")

        try {
            activeTts?.release()
            activeTts = null
            activeModelKey = null
            System.gc() // RAM ရှင်းမယ်
        } catch (e: Exception) { }

        val (modelFile, tokensFile) = when (langKey) {
            "shan" -> Pair("shan_model.onnx", "shan_tokens.txt")
            "eng" -> Pair("english_model.onnx", "english_tokens.txt")
            else -> Pair("burmese_model.onnx", "burmese_tokens.txt")
        }

        return try {
            val assetFiles = assets.list("") ?: emptyArray()
            if (!assetFiles.contains(modelFile) || !assetFiles.contains(tokensFile)) {
                AppLogger.log("❌ Missing: $modelFile")
                return null
            }

            AppLogger.log("⏳ Loading $langKey (Heavy)...")
            
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
            activeTts = tts
            activeModelKey = langKey
            AppLogger.log("✅ Ready: $langKey")
            tts
        } catch (e: Throwable) {
            AppLogger.log("🔥 Load Error: ${e.message}")
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
        AppLogger.log("📥 System Check: $lang")
        // ဒီနေရာမှာ ကြိုတင် Load ခိုင်းထားလိုက်မယ် (ဒါဆို စာဖတ်ချိန်ကျရင် မြန်သွားမယ်)
        val key = if (lang?.contains("en") == true) "eng" else if (lang?.contains("shn") == true) "shan" else "mya"
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
        if (text.isBlank()) { safeDone(callback); return }
        
        isStopped = false
        val lang = request?.language ?: "mya"
        val shortText = if (text.length > 15) text.substring(0, 15) + "..." else text
        AppLogger.log("🗣️ Req: '$shortText'")

        // Model ကို ရယူခြင်း (မရှိသေးရင် ဒီနေရာမှာ ခဏစောင့်မယ်)
        val engineKey = when {
            lang.contains("shn") || text.contains("shan_char_check") -> "shan"
            lang.contains("en") -> "eng"
            else -> "mya"
        }

        var tts: OfflineTts? = null
        synchronized(lock) {
            tts = loadModelBlocking(engineKey) ?: loadModelBlocking("mya")
        }

        if (isStopped) {
            AppLogger.log("🛑 Aborted before speak")
            return
        }

        if (tts != null) {
            try {
                val generated = tts!!.generate(text)
                val samples = generated.samples
                val sampleRate = generated.sampleRate

                // အကယ်၍ Timeout ဖြစ်သွားပြီးမှ ဒီနေရာရောက်လာရင် Crash မဖြစ်အောင် Try-Catch ခံမယ်
                if (safeStart(callback, sampleRate)) {
                    if (samples.isNotEmpty()) {
                        val audioBytes = floatArrayToByteArray(samples)
                        val maxBufferSize = 4096
                        var offset = 0
                        while (offset < audioBytes.size) {
                            if (isStopped) break
                            val bytesToWrite = min(maxBufferSize, audioBytes.size - offset)
                            // အရေးအကြီးဆုံးနေရာ (Safe Write)
                            val success = safeWrite(callback, audioBytes, offset, bytesToWrite)
                            if (!success) break // ပို့လို့မရတော့ရင် ရပ်လိုက်မယ်
                            offset += bytesToWrite
                        }
                    }
                    safeDone(callback)
                    AppLogger.log("✅ Done")
                }
            } catch (e: Throwable) {
                AppLogger.log("⚠️ Error: ${e.message}")
                safeError(callback)
            }
        } else {
            safeError(callback)
        }
    }

    // --- Safety Wrappers (Crash ကာကွယ်ရေး) ---

    private fun safeStart(callback: SynthesisCallback?, sampleRate: Int): Boolean {
        return try {
            if (isStopped) return false
            callback?.start(sampleRate, 16, 1)
            true
        } catch (e: Throwable) {
            AppLogger.log("⚠️ Callback Dead (Start): ${e.message}")
            false
        }
    }

    private fun safeWrite(callback: SynthesisCallback?, buffer: ByteArray, offset: Int, length: Int): Boolean {
        return try {
            if (isStopped) return false
            callback?.audioAvailable(buffer, offset, length)
            true
        } catch (e: Throwable) {
            AppLogger.log("⚠️ Callback Dead (Write): ${e.message}")
            false
        }
    }

    private fun safeDone(callback: SynthesisCallback?) {
        try { callback?.done() } catch (e: Throwable) {}
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

