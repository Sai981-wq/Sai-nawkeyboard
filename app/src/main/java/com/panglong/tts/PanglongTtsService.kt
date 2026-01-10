package com.panglong.tts

import android.content.Intent
import android.media.AudioFormat
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
        AppLogger.log("🔵 [Lifecycle] Service Created")
        AppLogger.log("⚙️ [Init] Starting Preload for English...")
        preloadModel("eng")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.log("🛡️ [Service] Sticky Mode Activated")
        return START_STICKY
    }

    private fun preloadModel(langKey: String) {
        if (isModelLoading.get()) {
            AppLogger.log("⚠️ [Load] Skipping: Already loading a model")
            return
        }
        executor.submit { loadModelBlocking(langKey) }
    }

    private fun loadModelBlocking(langKey: String) {
        synchronized(lock) {
            if (activeModelKey == langKey && activeTts != null) {
                AppLogger.log("ℹ️ [Load] $langKey already active")
                return
            }
        }
        
        isModelLoading.set(true)
        AppLogger.log("🔄 [Load] Switching to: $langKey")
        
        // RAM ရှင်းလင်းရေး
        try {
            synchronized(lock) {
                if (activeModelKey != langKey) {
                    activeTts?.release()
                    activeTts = null
                    System.gc()
                    AppLogger.log("🧹 [Memory] Old model released")
                }
            }
        } catch (e: Exception) { 
            AppLogger.log("⚠️ [Memory] Release warning: ${e.message}")
        }

        val (modelFile, tokensFile) = when (langKey) {
            "shan" -> Pair("shan_model.onnx", "shan_tokens.txt")
            "eng" -> Pair("english_model.onnx", "english_tokens.txt")
            else -> Pair("burmese_model.onnx", "burmese_tokens.txt")
        }

        try {
            val assetFiles = assets.list("") ?: emptyArray()
            if (!assetFiles.contains(modelFile)) {
                AppLogger.log("❌ [File] Missing: $modelFile")
                return
            }

            AppLogger.log("📂 [File] Found $modelFile. Initializing Sherpa...")
            
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
            AppLogger.log("✅ [Load] MODEL READY: $langKey")
            
        } catch (e: Throwable) {
            AppLogger.log("🔥 [Load] CRITICAL ERROR: ${e.message}")
            e.printStackTrace()
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
        AppLogger.log("📥 [System] Language Request: $key")
        preloadModel(key)
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetLanguage(): Array<String> = arrayOf("mya", "MM", "")
    
    override fun onStop() { 
        isStopped = true
        AppLogger.log("🛑 [Control] Stop Requested")
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        if (text.isBlank()) { callback?.done(); return }
        
        isStopped = false
        val lang = request?.language ?: "mya"
        
        // Log Raw Request
        val shortRaw = if (text.length > 20) text.substring(0, 20) + "..." else text
        AppLogger.log("🗣️ [Req] Lang: $lang | Text: '$shortRaw'")

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
            AppLogger.log("⚠️ [Status] Model not ready. Sending Silence.")
            playSilence(callback)
            return
        }

        try {
            // Text Processing
            var cleanText = text
            if (engineKey == "eng") {
                cleanText = text.lowercase()
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
                    .replace(Regex("[^a-z\\s]"), "")
                AppLogger.log("🧹 [Clean] English text processed")
            }

            // Generation
            AppLogger.log("⚡ [Sherpa] Generating audio...")
            val generated = tts!!.generate(cleanText)
            val samples = generated.samples
            val sampleRate = generated.sampleRate

            if (isStopped) { 
                AppLogger.log("🛑 [Control] Stopped before playback")
                safeError(callback)
                return 
            }

            if (samples.isNotEmpty()) {
                val audioBytes = floatArrayToByteArray(samples)
                AppLogger.log("📊 [Data] Size: ${audioBytes.size} bytes | Rate: $sampleRate Hz")

                // === FIX HERE: CORRECT AUDIO FORMAT ===
                // 16 = Invalid
                // AudioFormat.ENCODING_PCM_16BIT = 2 (Valid)
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                
                AppLogger.log("🔊 [Play] Starting: Rate=$sampleRate, Format=$audioFormat")
                val startResult = callback?.start(sampleRate, audioFormat, 1)
                
                if (startResult != TextToSpeech.SUCCESS) {
                     AppLogger.log("❌ [Play] Start Failed with code: $startResult")
                     safeError(callback)
                     return
                }
                
                val maxBufferSize = 4096
                var offset = 0
                var chunkCount = 0
                
                while (offset < audioBytes.size) {
                    if (isStopped) break
                    val bytesToWrite = min(maxBufferSize, audioBytes.size - offset)
                    callback?.audioAvailable(audioBytes, offset, bytesToWrite)
                    offset += bytesToWrite
                    chunkCount++
                }
                
                AppLogger.log("✅ [Done] Sent $chunkCount chunks successfully")
                callback?.done()
            } else {
                AppLogger.log("⚠️ [Sherpa] Generated empty samples")
                playSilence(callback)
            }
        } catch (e: Throwable) {
            AppLogger.log("❌ [Error] Exception: ${e.message}")
            e.printStackTrace()
            playSilence(callback)
        }
    }

    private fun playSilence(callback: SynthesisCallback?) {
        try {
            AppLogger.log("🔇 [Silence] Playing 0.5s silence")
            callback?.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)
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
        AppLogger.log("🔴 [Lifecycle] Service Destroyed")
        super.onDestroy()
    }
}

