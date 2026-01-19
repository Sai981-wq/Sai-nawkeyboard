package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object TtsEngine {
    private val ttsCache = mutableMapOf<String, OfflineTts>()
    var tts: OfflineTts? = null
    var lang: String? = ""
    var country: String? = ""

    var volume: MutableState<Float> = mutableFloatStateOf(1.0F)
    var speed: MutableState<Float> = mutableFloatStateOf(1.0F)
    var speakerId: MutableState<Int> = mutableIntStateOf(0)

    fun getAvailableLanguages(context: Context): ArrayList<String> {
        val langCodes = java.util.ArrayList<String>()
        val db = LangDB.getInstance(context)
        val allLanguages = db.allInstalledLanguages
        for (language in allLanguages) {
            langCodes.add(language.lang)
        }
        return langCodes
    }

    fun createTts(context: Context, language: String) {
        if (tts == null || lang != language) {
            if (ttsCache.containsKey(language)) {
                tts = ttsCache[language]
                loadLanguageSettings(context, language)
            } else {
                initTts(context, language)
            }
        }
    }

    private fun loadLanguageSettings(context: Context, language: String) {
        val db = LangDB.getInstance(context)
        val currentLanguage = db.allInstalledLanguages.first { it.lang == language }
        this.lang = language
        this.country = currentLanguage.country
        this.speed.value = currentLanguage.speed
        this.speakerId.value = currentLanguage.sid
        this.volume.value = currentLanguage.volume
        PreferenceHelper(context).setCurrentLanguage(language)
    }

    fun removeLanguageFromCache(language: String) {
        ttsCache.remove(language)
    }

    private fun initTts(context: Context, lang: String) {
        loadLanguageSettings(context, lang)
        val externalFilesDir = context.getExternalFilesDir(null)!!.absolutePath
        val modelDir = "$externalFilesDir/$lang$country"

        val vitsConfig = OfflineTtsVitsModelConfig(
            model = "$modelDir/model.onnx",
            tokens = "$modelDir/tokens.txt",
            lexicon = "",
            dataDir = "",
            dictDir = ""
        )

        val modelConfig = OfflineTtsModelConfig(
            vits = vitsConfig,
            numThreads = 1,
            debug = true,
            provider = "cpu"
        )

        val config = OfflineTtsConfig(
            model = modelConfig,
            ruleFsts = "",
            maxNumSentences = 1
        )

        try {
            tts = OfflineTts(assetManager = null, config = config)
            ttsCache[lang] = tts!!
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun copyAssets(context: Context, path: String) {
        val assets: Array<String>?
        try {
            assets = context.assets.list(path)
            if (assets!!.isEmpty()) {
                copyFile(context, path)
            } else {
                val fullPath = "${context.getExternalFilesDir(null)}/$path"
                val dir = File(fullPath)
                dir.mkdirs()
                for (asset in assets.iterator()) {
                    val p: String = if (path == "") "" else "$path/"
                    copyAssets(context, p + asset)
                }
            }
        } catch (ex: IOException) {
            ex.printStackTrace()
        }
    }

    private fun copyFile(context: Context, filename: String) {
        try {
            val istream = context.assets.open(filename)
            val newFilename = context.getExternalFilesDir(null)!!.absolutePath + "/" + filename
            val file = File(newFilename)
            if (!file.exists()) {
                val ostream = FileOutputStream(newFilename)
                val buffer = ByteArray(1024)
                var read = 0
                while (read != -1) {
                    ostream.write(buffer, 0, read)
                    read = istream.read(buffer)
                }
                istream.close()
                ostream.flush()
                ostream.close()
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}

