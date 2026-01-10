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
    private var activeModelKey: String? = null
    private var activeTts: OfflineTts? = null
    private var isModelLoading = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("✅ Service Created.")
        preloadModel("eng")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun preloadModel(langKey: String) {
        if (isModelLoading.get()) return
        executor.submit { loadModelBlocking(langKey) }
    }

    private fun loadModelBlocking(langKey: String) {
        synchronized(lock) {
            if (activeModelKey == langKey && activeTts != null) return
        }
        isModelLoading.set(true)
        
        try {
            synchronized(lock) {
                if (activeModelKey != langKey) {
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
                return
            }

            // AppLogger.log("⏳ Loading $langKey...")
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
    override fun onStop() { isStopped = true }

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
        synchronized(lock) {
            if (activeModelKey == engineKey && activeTts != null) tts = activeTts
        }

        if (tts == null) {
            if (!isModelLoading.get()) preloadModel(engineKey)
            playSilence(callback)
            return
        }

        try {
            // ==========================================
            // FIX: ဂဏန်းများကို စာလုံးပြောင်းခြင်း
            // ==========================================
            var cleanText = text.lowercase()
            
            if (engineKey == "eng") {
                // ဂဏန်းတွေတွေ့ရင် စာလုံးပြောင်းမယ် (English Model အတွက်)
                cleanText = cleanText
                    .replace("0", " zero ")
                    .replace("1", " one ")
                    .replace("2", " two ")
                    .replace("3", " three ")
                    .replace("4", " four ")
                    .replace("5", " five ")
                    .replace("6", " six ")
                    .replace("7", " seven ")
                    .replace("8", " eight ")
                    .replace("9", " nine ")
                    .replace("%", " percent ")
                    .replace(":", " ") // 12:00 -> 12 00
                
                // အခြား မလိုအပ်သော သင်္ကေတများကို ဖယ်ရှားမယ်
                cleanText = cleanText.replace(Regex("[^a-z\\s]"), "")
            }

            // Log ထုတ်ကြည့်မယ် (ဂဏန်းမပါတော့တာ သေချာအောင်)
            val shortLog = if (text.length > 15) text.substring(0, 15) + "..." else text
            AppLogger.log("🗣️ Req: '$shortLog' -> Clean: '$cleanText'")
            
            val generated = tts!!.generate(cleanText)
            val samples = generated.samples
            val sampleRate = generated.sampleRate

            if (isStopped) { safeError(callback); return }

            if (samples.isNotEmpty()) {
                val audioBytes = floatArrayToByteArray(samples)
                
                // Audio စတင်ခြင်း
                callback?.start(sampleRate, 16, 1)
                
                // Chunking (4KB)
                val maxBufferSize = 4096
                var offset = 0
                while (offset < audioBytes.size) {
                    if (isStopped) break
                    val bytesToWrite = min(maxBufferSize, audioBytes.size - offset)
                    callback?.audioAvailable(audioBytes, offset, bytesToWrite)
                    offset += bytesToWrite
                }
                callback?.done()
            } else {
                playSilence(callback)
            }
        } catch (e: Throwable) {
            AppLogger.log("⚠️ Error: ${e.message}")
            playSilence(callback)
        }
    }

    private fun playSilence(callback: SynthesisCallback?) {
        try {
            callback?.start(16000, 16, 1)
            callback?.audioAvailable(ByteArray(3200), 0, 3200)
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

