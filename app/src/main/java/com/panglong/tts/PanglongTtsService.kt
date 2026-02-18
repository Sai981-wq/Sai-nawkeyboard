package com.panglong.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream

class PanglongTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "PanglongTTS"
    }

    private var shnTts: OfflineTts? = null
    private var myaTts: OfflineTts? = null
    private var currentLanguage: String = "shn"
    
    @Volatile
    private var isCancelled = false

    override fun onCreate() {
        super.onCreate()
        try {
            // Piper models require espeak-ng-data for phonemization
            val dataDir = copyAssetFolderToInternal("espeak-ng-data")
            
            // SHN Model
            val shnModelPath = copyAssetToInternal("shn/model.onnx")
            val shnTokensPath = copyAssetToInternal("shn/tokens.txt")
            
            val shnConfig = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = shnModelPath,
                        tokens = shnTokensPath,
                        dataDir = dataDir
                    ),
                    numThreads = 1,
                    debug = false
                )
            )
            shnTts = OfflineTts(assets, shnConfig)

            // MYA Model
            val myaModelPath = copyAssetToInternal("mya/model.onnx")
            val myaTokensPath = copyAssetToInternal("mya/tokens.txt")
            
            val myaConfig = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = myaModelPath,
                        tokens = myaTokensPath,
                        dataDir = dataDir
                    ),
                    numThreads = 1,
                    debug = false
                )
            )
            myaTts = OfflineTts(assets, myaConfig)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SherpaOnnx", e)
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

    private fun copyAssetFolderToInternal(folderPath: String): String {
        val outDir = File(filesDir, folderPath)
        if (outDir.exists() && outDir.isDirectory) {
            return outDir.absolutePath
        }
        
        assets.list(folderPath)?.forEach { fileName ->
            val subPath = "$folderPath/$fileName"
            val isDir = try {
                assets.list(subPath)?.isNotEmpty() ?: false
            } catch (e: Exception) {
                false
            }
            
            if (isDir) {
                copyAssetFolderToInternal(subPath)
            } else {
                copyAssetToInternal(subPath)
            }
        }
        return outDir.absolutePath
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

        val tts = if (lang == "my" || lang == "mya") myaTts else shnTts
        if (tts == null) {
            callback.error()
            return
        }

        try {
            val sampleRate = tts.sampleRate()
            callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)

            val audio = tts.generate(text, sid = 0, speed = 1.0f)
            val samples = audio.samples
            
            if (samples.isNotEmpty()) {
                val bufferSize = 4096
                val pcmBuffer = ByteArray(bufferSize * 2)
                var pos = 0
                while (pos < samples.size) {
                    if (isCancelled) break
                    
                    val remaining = samples.size - pos
                    val count = minOf(bufferSize, remaining)
                    for (j in 0 until count) {
                        val sample = (samples[pos + j] * 32767.0f)
                            .coerceIn(-32768.0f, 32767.0f).toInt().toShort()
                        pcmBuffer[j * 2] = (sample.toInt() and 0xFF).toByte()
                        pcmBuffer[j * 2 + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
                    }
                    callback.audioAvailable(pcmBuffer, 0, count * 2)
                    pos += count
                }
            }
            callback.done()

        } catch (e: Exception) {
            Log.e(TAG, "Synthesis error", e)
            callback.error()
        }
    }

    override fun onDestroy() {
        shnTts?.release()
        myaTts?.release()
        super.onDestroy()
    }
}
