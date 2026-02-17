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
    private var shnSession: OrtSession? = null
    private var myaSession: OrtSession? = null
    private var shnTokenMap: Map<Char, Long> = emptyMap()
    private var myaTokenMap: Map<Char, Long> = emptyMap()
    private var currentLanguage: String = "shn"
    
    @Volatile
    private var isCancelled = false

    override fun onCreate() {
        super.onCreate()
        ortEnv = OrtEnvironment.getEnvironment()
        try {
            val shnModelPath = copyAssetToInternal("shn/model.onnx")
            val myaModelPath = copyAssetToInternal("mya/model.onnx")

            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(2)

            shnSession = ortEnv?.createSession(shnModelPath, sessionOptions)
            myaSession = ortEnv?.createSession(myaModelPath, sessionOptions)

            shnTokenMap = loadTokens("shn/tokens.txt")
            myaTokenMap = loadTokens("mya/tokens.txt")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load models", e)
        }
    }

    private fun copyAssetToInternal(assetPath: String): String {
        val outFile = File(filesDir, assetPath)
        if (outFile.exists() && outFile.length() > 0) {
            return outFile.absolutePath
        }
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
                    val id = rawLine.trim().toLongOrNull()
                    if (id != null) {
                        map[' '] = id
                    }
                    return@forEach
                }

                val parts = rawLine.split(" ", "\t")
                if (parts.size == 2) {
                    val token = parts[0]
                    val id = parts[1].toLongOrNull()
                    if (token.length == 1 && id != null) {
                        map[token[0]] = id
                    }
                }
            }
        }
        return map
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return when {
            lang == "shn" || lang == "my" || lang == "mya" -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> {
        return when (currentLanguage) {
            "mya" -> arrayOf("my", "", "")
            else -> arrayOf("shn", "", "")
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return when {
            lang == "shn" -> {
                currentLanguage = "shn"
                TextToSpeech.LANG_AVAILABLE
            }
            lang == "my" || lang == "mya" -> {
                currentLanguage = "mya"
                TextToSpeech.LANG_AVAILABLE
            }
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onStop() {
        isCancelled = true
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        
        isCancelled = false

        val text = request.charSequenceText?.toString() ?: request.text ?: return
        val lang = request.language ?: currentLanguage

        val session: OrtSession?
        val tokenMap: Map<Char, Long>
        val blankId: Long
        when {
            lang == "shn" -> {
                session = shnSession
                tokenMap = shnTokenMap
                blankId = SHN_BLANK_ID
            }
            lang == "my" || lang == "mya" -> {
                session = myaSession
                tokenMap = myaTokenMap
                blankId = MYA_BLANK_ID
            }
            else -> {
                session = shnSession
                tokenMap = shnTokenMap
                blankId = SHN_BLANK_ID
            }
        }

        if (session == null) {
            callback.error()
            return
        }

        try {
            val sentences = text.split("။", "၊", "\n").filter { it.isNotBlank() }

            callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)

            val bufferSize = 4096
            val pcmBuffer = ByteArray(bufferSize * 2)

            for (sentence in sentences) {
                if (isCancelled) {
                    break
                }

                val sentenceWithPunctuation = "$sentence။"
                val ids = tokenizeWithBlanks(sentenceWithPunctuation, tokenMap, blankId)

                if (ids.size <= 1) continue

                val chunkAudio = runInference(session, ids)

                if (chunkAudio != null) {
                    var pos = 0
                    while (pos < chunkAudio.size) {
                        if (isCancelled) break
                        
                        val remaining = chunkAudio.size - pos
                        val count = minOf(bufferSize, remaining)
                        for (j in 0 until count) {
                            val sample = (chunkAudio[pos + j] * 32767.0f)
                                .coerceIn(-32768.0f, 32767.0f).toInt().toShort()
                            pcmBuffer[j * 2] = (sample.toInt() and 0xFF).toByte()
                            pcmBuffer[j * 2 + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
                        }
                        callback.audioAvailable(pcmBuffer, 0, count * 2)
                        pos += count
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
        shnSession?.close()
        myaSession?.close()
        ortEnv?.close()
        super.onDestroy()
    }
}

