package com.panglong.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import java.nio.FloatBuffer
import java.io.File
import java.io.FileOutputStream

class PanglongTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "PanglongTTS"
        private const val SAMPLE_RATE = 22050
        private const val SHN_BLANK_ID = 0L
        private const val MYA_BLANK_ID = 56L
    }

    private var ortEnv: OrtEnvironment? = null
    private var activeSession: OrtSession? = null
    private var activeLanguage: String = ""
    private var activeTokenMap: Map<Char, Long> = emptyMap()
    private var activeBlankId: Long = 0L

    override fun onCreate() {
        super.onCreate()
        ortEnv = OrtEnvironment.getEnvironment()
    }

    private fun loadModelForLanguage(lang: String) {
        val targetLang = if (lang == "my" || lang == "mya") "mya" else "shn"
        if (activeLanguage == targetLang && activeSession != null) return

        activeSession?.close()
        activeSession = null
        activeTokenMap = emptyMap()

        try {
            val modelPath = copyAssetToInternal("$targetLang/model.onnx")
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(2)
            
            activeSession = ortEnv?.createSession(modelPath, sessionOptions)
            activeTokenMap = loadTokens("$targetLang/tokens.txt")
            activeLanguage = targetLang
            activeBlankId = if (targetLang == "mya") MYA_BLANK_ID else SHN_BLANK_ID
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
        }
    }

    private fun copyAssetToInternal(assetPath: String): String {
        val outFile = File(filesDir, assetPath)
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath
        
        outFile.parentFile?.mkdirs()
        assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        }
        return outFile.absolutePath
    }

    private fun loadTokens(assetPath: String): Map<Char, Long> {
        val map = mutableMapOf<Char, Long>()
        assets.open(assetPath).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val rawLine = line.trimEnd()
                if (rawLine.isEmpty()) return@forEach
                
                if (rawLine.length >= 2 && rawLine[0] == ' ' && rawLine[1] == ' ') {
                    rawLine.trim().toLongOrNull()?.let { map[' '] = it }
                    return@forEach
                }
                
                val parts = rawLine.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val token = parts[0]
                    val id = parts.last().toLongOrNull()
                    if (token.length == 1 && id != null) {
                        map[token[0]] = id
                    }
                }
            }
        }
        return map
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return when (lang) {
            "shn", "my", "mya" -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf(if (activeLanguage == "mya") "my" else "shn", "", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result == TextToSpeech.LANG_AVAILABLE && lang != null) {
            loadModelForLanguage(lang)
        }
        return result
    }

    override fun onStop() {
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        
        val text = request.charSequenceText?.toString() ?: request.text ?: return
        val lang = request.language ?: "shn"

        loadModelForLanguage(lang)
        
        val session = activeSession
        if (session == null) {
            callback.error()
            return
        }

        try {
            callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)

            val sentences = text.split(Regex("(?<=[။၊!?.])|\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            
            for (sentence in sentences) {
                val ids = tokenizeWithBlanks(sentence, activeTokenMap, activeBlankId)
                if (ids.size <= 1) continue
                
                val maxTokens = 200
                for (i in ids.indices step maxTokens) {
                    val chunkIds = ids.subList(i, minOf(i + maxTokens, ids.size))
                    if (chunkIds.size <= 1) continue
                    
                    val chunkAudio = runInference(session, chunkIds)
                    if (chunkAudio != null) {
                        val maxAbs = chunkAudio.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
                        val gain = if (maxAbs > 0.01f) 0.95f / maxAbs else 1.0f
                        
                        val pcmBuffer = ByteArray(chunkAudio.size * 2)
                        for (j in chunkAudio.indices) {
                            val sample = (chunkAudio[j] * gain * 32767.0f).coerceIn(-32768.0f, 32767.0f).toInt().toShort()
                            pcmBuffer[j * 2] = (sample.toInt() and 0xFF).toByte()
                            pcmBuffer[j * 2 + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
                        }
                        
                        callback.audioAvailable(pcmBuffer, 0, pcmBuffer.size)
                    }
                }
            }
            callback.done()
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis error", e)
            callback.error()
        }
    }

    private fun tokenizeWithBlanks(text: String, tokenMap: Map<Char, Long>, blankId: Long): List<Long> {
        val ids = mutableListOf<Long>()
        ids.add(blankId)
        for (ch in text) {
            val id = tokenMap[ch]
            if (id != null) {
                ids.add(id)
                ids.add(blankId)
            } else if (ch == ' ' && ' ' in tokenMap) {
                ids.add(tokenMap[' ']!!)
                ids.add(blankId)
            }
        }
        return ids
    }

    private fun runInference(session: OrtSession, ids: List<Long>): FloatArray? {
        val env = ortEnv ?: return null
        
        val inputIds = LongBuffer.wrap(ids.toLongArray())
        val inputLengths = LongBuffer.wrap(longArrayOf(ids.size.toLong()))
        val noiseScale = FloatBuffer.wrap(floatArrayOf(0.667f))
        val lengthScale = FloatBuffer.wrap(floatArrayOf(1.0f))
        val noiseScaleW = FloatBuffer.wrap(floatArrayOf(0.8f))

        val xTensor = OnnxTensor.createTensor(env, inputIds, longArrayOf(1, ids.size.toLong()))
        val xLenTensor = OnnxTensor.createTensor(env, inputLengths, longArrayOf(1))
        val nsTensor = OnnxTensor.createTensor(env, noiseScale, longArrayOf(1))
        val lsTensor = OnnxTensor.createTensor(env, lengthScale, longArrayOf(1))
        val nswTensor = OnnxTensor.createTensor(env, noiseScaleW, longArrayOf(1))

        val inputs = mapOf(
            "x" to xTensor,
            "x_length" to xLenTensor,
            "noise_scale" to nsTensor,
            "length_scale" to lsTensor,
            "noise_scale_w" to nswTensor
        )

        val result = session.run(inputs)
        val outputTensor = result[0] as OnnxTensor
        val outputData = outputTensor.floatBuffer

        val audioData = FloatArray(outputData.remaining())
        outputData.get(audioData)

        xTensor.close()
        xLenTensor.close()
        nsTensor.close()
        lsTensor.close()
        nswTensor.close()
        result.close()

        return audioData
    }

    override fun onDestroy() {
        activeSession?.close()
        ortEnv?.close()
        super.onDestroy()
    }
}

