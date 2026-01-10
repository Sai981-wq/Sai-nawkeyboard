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
import java.util.concurrent.atomic.AtomicBoolean

class PanglongTtsService : TextToSpeechService() {
    private val lock = Any()
    
    // Model သိမ်းဆည်းရာ
    private var activeModelKey: String? = null
    private var activeTts: OfflineTts? = null
    
    // Model တင်နေလား စစ်ဆေးရန် (Atomic သုံးထားလို့ Lock မလိုပါ)
    private var isModelLoading = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("✅ Service Created.")
        // English ကို နောက်ကွယ်မှာ ချက်ချင်းတင်မယ်
        preloadModel("eng")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun preloadModel(langKey: String) {
        // တင်နေတုန်းဆိုရင် ထပ်မတင်ဘူး
        if (isModelLoading.get()) return
        
        // ရှိပြီးသားဆိုရင် ထပ်မတင်ဘူး
        synchronized(lock) {
            if (activeModelKey == langKey && activeTts != null) return
        }

        executor.submit {
            loadModelBlocking(langKey)
        }
    }

    private fun loadModelBlocking(langKey: String) {
        isModelLoading.set(true)
        AppLogger.log("♻️ Loading process started for $langKey...")

        // RAM ရှင်း
        try {
            synchronized(lock) {
                if (activeModelKey != langKey) { // တခြားဟာတင်မှာမို့ အဟောင်းဖျက်
                    activeTts?.release()
                    activeTts = null
                    System.gc()
                }
            }
        } catch (e: Exception) { }

        val (modelFile, tokensFile) = when (langKey) {
            "shan" -> Pair("shan_model.onnx", "shan_tokens.txt")
            "eng" -> Pair("english_model.onnx", "english_tokens.txt")
            else -> Pair("burmese_model.onnx", "burmese_tokens.txt")
        }

        try {
            val assetFiles = assets.list("") ?: emptyArray()
            if (!assetFiles.contains(modelFile)) {
                AppLogger.log("❌ Missing: $modelFile")
                isModelLoading.set(false)
                return
            }

            AppLogger.log("⏳ Reading $langKey from disk (Wait 10s)...")
            
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
            }
            AppLogger.log("✅ MODEL READY: $langKey")
        } catch (e: Throwable) {
            AppLogger.log("🔥 Load Failed: ${e.message}")
        } finally {
            isModelLoading.set(false)
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
        preloadModel(key)
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetLanguage(): Array<String> = arrayOf("mya", "MM", "")

    override fun onStop() {
        isStopped = true
        AppLogger.log("🛑 Stop Signal Received")
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

        // --- အရေးကြီးဆုံး အပိုင်း (Non-Blocking Logic) ---
        var tts: OfflineTts? = null
        
        synchronized(lock) {
            // Model က ကိုယ်လိုချင်တာနဲ့ ကိုက်ညီမှ ယူမယ်
            if (activeModelKey == engineKey && activeTts != null) {
                tts = activeTts
            }
        }

        // Model မရှိဘူးလား? (ဒါဆို မစောင့်ဘူး၊ Silence ပို့ပြီး ထွက်မယ်)
        if (tts == null) {
            AppLogger.log("⚠️ Model not ready yet. Sending SILENCE to prevent crash.")
            // နောက်ကွယ်မှာ အမြန်တင်ခိုင်းလိုက်မယ်
            preloadModel(engineKey) 
            // အသံတိတ်ပို့မယ်
            playSilence(callback)
            return
        }

        // Model ရှိရင် ပုံမှန်အတိုင်း ဖတ်မယ်
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
            playSilence(callback) 
        }
    }

    // အသံတိတ် လွှတ်ပေးသည့် Function (0.5 စက္ကန့်စာ)
    private fun playSilence(callback: SynthesisCallback?) {
        try {
            // 16000Hz PCM Audio
            callback?.start(16000, 16, 1)
            // 0 တွေချည်းပါတဲ့ Array (အသံတိတ်)
            val silence = ByteArray(16000) 
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

