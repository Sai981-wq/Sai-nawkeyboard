package com.mettavoice.tts

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.microsoft.cognitiveservices.speech.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

class ShanTtsService : TextToSpeechService() {

    companion object {
        init {
            try {
                System.loadLibrary("native-lib")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        private const val OUTPUT_SAMPLE_RATE = 24000
        private const val OUTPUT_CHANNEL_COUNT = 1
        private const val OUTPUT_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BIN_FILENAME = "audio.bin"
        private const val INDEX_FILENAME = "index.txt"
        private const val CROSSFADE_SAMPLES = 80
        private const val FADE_SAMPLES = 48
    }

    private external fun sonicCreateStream(sampleRate: Int, numChannels: Int): Long
    private external fun sonicDestroyStream(streamId: Long)
    private external fun sonicSetSpeed(streamId: Long, speed: Float)
    private external fun sonicSetPitch(streamId: Long, pitch: Float)
    private external fun sonicWriteShortToStream(streamId: Long, audioData: ShortArray, len: Int): Int
    private external fun sonicReadShortFromStream(streamId: Long, audioData: ShortArray, len: Int): Int
    private external fun sonicFlushStream(streamId: Long)
    private external fun sonicSamplesAvailable(streamId: Long): Int
    private external fun initOpusDecoder(sampleRate: Int)
    private external fun decodeOpus(encodedData: ByteArray, len: Int): ShortArray?
    private external fun destroyOpusDecoder()

    private var charMap: Map<String, String>? = null
    private var singleCharMap: Map<String, String>? = null
    private var phraseMap: Map<String, String>? = null
    private val indexMap = HashMap<String, Pair<Long, Int>>()
    private var randomAccessFile: RandomAccessFile? = null
    @Volatile private var isOpusInit = false

    @Volatile private var isStopped = false
    @Volatile private var isDirectStopped = false
    private var directAudioTrack: AudioTrack? = null
    private var englishEngine: TextToSpeech? = null
    @Volatile private var isEnglishReady = false
    private val utteranceLatches = ConcurrentHashMap<String, CountDownLatch>()
    private var cpuWakeLock: PowerManager.WakeLock? = null

    private val cachedSynthesizers = ConcurrentHashMap<String, SpeechSynthesizer>()
    private val cachedConfigs = ConcurrentHashMap<String, EmbeddedSpeechConfig>()
    private val neuralLock = Any()

    private val neuralSingleCharMap = mapOf(
        "က" to "ကကြီး", "ခ" to "ခခွေး", "ဂ" to "ဂငယ်", "ဃ" to "ဂါ့ကြီး", "င" to "ငါ့",
        "စ" to "စလုံး", "ဆ" to "ဆလိမ်", "ဇ" to "ဇာ့ကွဲ", "ဈ" to "ဈမျဉ်းဆွဲ", "ဉ" to "ဉာ့ကလေး",
        "ည" to "ညာ့", "ဋ" to "တာ့သန်လျင်းချိတ်", "ဌ" to "ဌဝမ်းဘဲ", "ဍ" to "ဒါ့ရင်ကောက်", "ဎ" to "ဎာ့ရေမှုတ်",
        "ဏ" to "ဏကြီး", "တ" to "တဝမ်းပူ", "ထ" to "ထဆင်ထူး", "ဒ" to "ဒာ့ထွေး", "ဓ" to "ဓအောက်ခြိုက်",
        "န" to "နာ့ငယ်", "ပ" to "ပါ့စောက်", "ဖ" to "ဖဦးထုပ်", "ဗ" to "ဗထက်ခြိုက်", "ဘ" to "ဘာ့ကုန်း",
        "မ" to "မာ့", "ယ" to "ယပက်လက်", "ရ" to "ရကောက်", "လ" to "လာ့", "ဝ" to "ဝါ့",
        "သ" to "သာ့", "ဟ" to "ဟာ့", "ဠ" to "ဠာ့ကြီး", "အ" to "အာ့", "ဣ" to "အိ",
        "ဤ" to "အီ", "ဥ" to "အု", "ဦ" to "အူ", "ဧ" to "အေ", "ဩ" to "အော", "ဪ" to "အော်",
        "ာ" to "ရေးချ", "ါ" to "မောက်ချ", "ိ" to "လုံးကြီးတင်", "ီ" to "လုံးကြီးတင်ဆန်ခတ်",
        "ု" to "တစ်ချောင်းငင်", "ူ" to "နှစ်ချောင်းငင်", "ေ" to "သဝေထိုး", "ဲ" to "နောက်ပြစ်",
        "ံ" to "သေးသေးတင်", "့" to "အောက်ကမြစ်", "း" to "ဝစ်ဆနှစ်လုံးပေါက်", "ျ" to "ယပင့်",
        "ြ" to "ရယစ်", "ွ" to "ဝဆွဲ", "ှ" to "ဟထိုး", "်" to "အ သတ်", "္" to "အ ဆင့်",
        "၌" to "နိုက်", "၍" to "ရွေ့", "၎င်း" to "လည်းကောင်း", "၏" to "အိ", "ဿ" to "သကြီး",
        "ဏ္ဍ" to "ဏကြီး အဆင့် ဒရင်ကောက်", "ဍ္ဎ" to "ဒရင်ကောက် အဆင့် ဒါ့ရေမှုတ်",
        "၊" to "ပုဒ်ဖြတ်", "။" to "ပုဒ်မ", "၁" to "တစ်"
    )

    private fun broadcastLogToUI(context: Context, message: String) {
        Log.e("ShanTts_Error", message)
        try {
            val intent = Intent("com.mettavoice.tts.TTS_LOG")
            intent.putExtra("log_msg", message)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MettaVoice::CpuWakeLock")
        cpuWakeLock?.setReferenceCounted(false)
        initResources(this)
        initEnglishEngine()
    }

    private fun initEnglishEngine() {
        val prefs = getSharedPreferences("mettavoice_tts_prefs", Context.MODE_PRIVATE)
        val enginePkg = prefs.getString("pref_secondary_engine", "com.google.android.tts")
        englishEngine = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                englishEngine?.language = Locale.US
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    englishEngine?.setAudioAttributes(attrs)
                }
                isEnglishReady = true
            }
        }, enginePkg)

        englishEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { utteranceLatches.remove(it)?.countDown() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { utteranceLatches.remove(it)?.countDown() }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { utteranceLatches.remove(it)?.countDown() }
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { utteranceLatches.remove(it)?.countDown() }
            }
        })
    }

    private fun clearAllNeuralCaches() {
        synchronized(neuralLock) {
            cachedSynthesizers.values.forEach { try { it.close() } catch (_: Exception) {} }
            cachedConfigs.values.forEach { try { it.close() } catch (_: Exception) {} }
            cachedSynthesizers.clear()
            cachedConfigs.clear()
        }
    }

    private fun clearNeuralCacheFor(voiceFolder: String) {
        synchronized(neuralLock) {
            try { cachedSynthesizers[voiceFolder]?.close() } catch (_: Exception) {}
            try { cachedConfigs[voiceFolder]?.close() } catch (_: Exception) {}
            cachedSynthesizers.remove(voiceFolder)
            cachedConfigs.remove(voiceFolder)
        }
    }

    fun initResources(context: Context) {
        val prefs = context.getSharedPreferences("mettavoice_tts_prefs", Context.MODE_PRIVATE)
        val currentVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else pInfo.versionCode.toLong()
        } catch (e: Exception) { 1L }

        val savedVersion = prefs.getLong("assets_version", -1L)
        val isUpdate = savedVersion != currentVersion

        if (isUpdate) {
            copyAsset(context, BIN_FILENAME, true)
            copyAsset(context, INDEX_FILENAME, true)
        }

        if (charMap == null) {
            charMap = loadMapFromFile(context, "mapping.txt")
            singleCharMap = loadMapFromFile(context, "mapping_single.txt")
            phraseMap = loadMapFromFile(context, "mapping_phrase.txt")
        }
        if (indexMap.isEmpty()) loadIndexMap(context)
        if (randomAccessFile == null) {
            val binFile = File(context.filesDir, BIN_FILENAME)
            if (binFile.exists()) {
                try {
                    randomAccessFile = RandomAccessFile(binFile, "r")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        if (!isOpusInit) {
            try {
                initOpusDecoder(OUTPUT_SAMPLE_RATE)
                isOpusInit = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (isUpdate) {
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                if (Build.VERSION.SDK_INT >= 31) {
                    unzipAsset(context, "Nilar.zip", File(context.filesDir, "Nilar"))
                    unzipAsset(context, "Thiha.zip", File(context.filesDir, "Thiha"))
                }
                prefs.edit().putLong("assets_version", currentVersion).apply()
                clearAllNeuralCaches()

                val voiceType = prefs.getString("pref_primary_voice", "legacy")
                if (voiceType != "legacy" && voiceType != null && Build.VERSION.SDK_INT >= 31) {
                    try {
                        val voiceFolder = if (voiceType.equals("nilar", ignoreCase = true)) "Nilar" else "Thiha"
                        val synth = getNeuralSynthesizer(context, voiceFolder)
                        synth?.StartSpeakingTextAsync(" ")?.get()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.start()
        } else {
            val voiceType = prefs.getString("pref_primary_voice", "legacy")
            if (voiceType != "legacy" && voiceType != null && Build.VERSION.SDK_INT >= 31) {
                Thread {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    try {
                        val voiceFolder = if (voiceType.equals("nilar", ignoreCase = true)) "Nilar" else "Thiha"
                        val synth = getNeuralSynthesizer(context, voiceFolder)
                        synth?.StartSpeakingTextAsync(" ")?.get()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.start()
            }
        }
    }

    private fun unzipAsset(context: Context, zipName: String, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        try {
            context.assets.open(zipName).use { ais ->
                java.util.zip.ZipInputStream(ais).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val file = File(targetDir, entry.name)
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun copyAsset(context: Context, filename: String, force: Boolean) {
        val file = File(context.filesDir, filename)
        if (force || !file.exists() || file.length() == 0L) {
            try {
                if (file.exists()) file.delete()
                context.assets.open(filename).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isExpired(): Boolean = false

    private fun convertNumberToBurmese(numStr: String): String {
        var standardStr = ""
        for (c in numStr) {
            when (c) {
                '၀' -> standardStr += "0"
                '၁' -> standardStr += "1"
                '၂' -> standardStr += "2"
                '၃' -> standardStr += "3"
                '၄' -> standardStr += "4"
                '၅' -> standardStr += "5"
                '၆' -> standardStr += "6"
                '၇' -> standardStr += "7"
                '၈' -> standardStr += "8"
                '၉' -> standardStr += "9"
                else -> standardStr += c 
            }
        }

        if (standardStr.isEmpty()) return numStr
        if (standardStr.all { it == '0' }) return standardStr.map { "သုည" }.joinToString("")
        if (standardStr == "0") return "သုည"

        if (standardStr.length > 8 || standardStr.startsWith("0")) {
            val names = arrayOf("သုည", "တစ်", "နှစ်", "သုံး", "လေး", "ငါး", "ခြောက်", "ခုနစ်", "ရှစ်", "ကိုး")
            return standardStr.map { 
                if (it in '0'..'9') names[it - '0'] else it.toString() 
            }.joinToString("")
        }

        val numNames = arrayOf("", "တစ်", "နှစ်", "သုံး", "လေး", "ငါး", "ခြောက်", "ခုနစ်", "ရှစ်", "ကိုး")
        val placeNames = arrayOf("", "ဆယ်", "ရာ", "ထောင်", "သောင်း", "သိန်း", "သန်း", "ကုဋေ")
        val placeMods = arrayOf("", "ဆယ့်", "ရာ့", "ထောင့်", "သောင်း", "သိန်း", "သန်း", "ကုဋေ")

        var res = ""
        val len = standardStr.length

        for (i in standardStr.indices) {
            val c = standardStr[i]
            if (c !in '0'..'9') {
                res += c
                continue
            }
            
            val d = c - '0'
            if (d == 0) continue

            val place = len - 1 - i
            val hasNextNonZero = standardStr.substring(i + 1).any { it in '1'..'9' }
            val placeStr = if (place in 1..7) {
                if (hasNextNonZero && place < 4) placeMods[place] else placeNames[place]
            } else {
                ""
            }

            val digitName = if (d == 1 && place == 1 && i == 0) "" else numNames[d]
            res += digitName + placeStr
        }
        return res
    }

    private fun convertNumbersInText(text: String): String {
        var processed = text.replace(Regex("([၀-၉]),([၀-၉])"), "$1$2")
        while (processed.contains(Regex("([၀-၉]),([၀-၉])"))) {
            processed = processed.replace(Regex("([၀-၉]),([၀-၉])"), "$1$2")
        }

        val regex = Regex("[၀-၉]+")
        return regex.replace(processed) { matchResult ->
            convertNumberToBurmese(matchResult.value)
        }
    }

    private fun fixTypo(text: String, readNumbers: Boolean): String {
        var processedText = text.replace("\u1025\u103A", "\u1009\u103A")
            .replace(Regex("\u1040(?=[\u102B-\u103E]*[\u1000-\u1021][\u103A\u1039])"), "\u101D")
        
        if (readNumbers) {
            processedText = convertNumbersInText(processedText)
        }
        return processedText
    }

    private fun chunkTextForNeural(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (i in text.indices) {
            val c = text[i]
            currentChunk.append(c)
            
            if (c == '။' || c == '\n' || c == '?' || c == '!' || c == '.') {
                if (currentChunk.isNotBlank()) {
                    chunks.add(currentChunk.toString().trim())
                    currentChunk.clear()
                }
            } else if (c == '၊' || c == ',') {
                if (currentChunk.length > 80) {
                    if (currentChunk.isNotBlank()) {
                        chunks.add(currentChunk.toString().trim())
                        currentChunk.clear()
                    }
                }
            }
        }
        if (currentChunk.isNotBlank()) {
            chunks.add(currentChunk.toString().trim())
        }
        return chunks
    }

    private fun loadMapFromFile(context: Context, filename: String): Map<String, String> {
        val tempMap = mutableMapOf<String, String>()
        try {
            context.assets.open(filename).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split("=", limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0].trim()
                            val value = parts[1].trim()
                            if (!key.equals("rate", ignoreCase = true) &&
                                !key.equals("pitch", ignoreCase = true) &&
                                !key.equals("speed", ignoreCase = true)) {
                                tempMap[key] = value
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return tempMap
    }

    private fun loadIndexMap(context: Context) {
        val indexFile = File(context.filesDir, INDEX_FILENAME)
        if (!indexFile.exists()) return
        try {
            indexFile.forEachLine { line ->
                val parts = line.split(":", limit = 3)
                if (parts.size == 3) {
                    val name = parts[0].trim()
                    val offset = parts[1].trim().toLong()
                    val length = parts[2].trim().toInt()
                    indexMap[name] = Pair(offset, length)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (lang != null) {
            if (lang.equals("my", ignoreCase = true) || lang.equals("mya", ignoreCase = true)) {
                return if (country != null && (country.equals("MM", ignoreCase = true) || country.equals("MMR", ignoreCase = true))) {
                    TextToSpeech.LANG_COUNTRY_AVAILABLE
                } else {
                    TextToSpeech.LANG_AVAILABLE
                }
            }
            if (lang.equals("en", ignoreCase = true) || lang.equals("eng", ignoreCase = true)) {
                return TextToSpeech.LANG_AVAILABLE
            }
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetLanguage(): Array<String> = arrayOf("mya", "MMR", "")

    override fun onStop() {
        isStopped = true
        isDirectStopped = true
        try {
            englishEngine?.stop()
        } catch (_: Exception) {}
        try {
            directAudioTrack?.pause()
            directAudioTrack?.flush()
        } catch (_: Exception) {}
        utteranceLatches.values.forEach { it.countDown() }
        utteranceLatches.clear()
    }

    private fun getDecryptionKey(dir: File): String {
        if (!dir.exists() || !dir.isDirectory) return ""
        val files = dir.listFiles() ?: return ""
        for (file in files) {
            if (file.isFile && file.name == "am_v5_decoder.bin") {
                try {
                    val bytes = ByteArray(4096)
                    val fis = java.io.FileInputStream(file)
                    val length = fis.read(bytes)
                    fis.close()
                    if (length > 0) {
                        val content = String(bytes, 0, length, Charsets.UTF_8)
                        val markerIndex = content.indexOf("ffffffff")
                        if (markerIndex != -1) {
                            return content.substring(0, markerIndex).trim()
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            } else if (file.isDirectory) {
                val key = getDecryptionKey(file)
                if (key.isNotEmpty()) return key
            }
        }
        return ""
    }

    private fun getNeuralSynthesizer(context: Context, voiceFolder: String): SpeechSynthesizer? {
        synchronized(neuralLock) {
            if (cachedSynthesizers.containsKey(voiceFolder)) {
                return cachedSynthesizers[voiceFolder]
            }

            try {
                val paths = arrayListOf<String>(context.filesDir.absolutePath)
                val config = EmbeddedSpeechConfig.fromPaths(paths)
                
                val tempSynthesizer = SpeechSynthesizer(config, null)
                val voiceListResult = tempSynthesizer.getVoicesAsync("").get()
                var detectedVoiceName = ""
                
                if (voiceListResult.voices != null) {
                    for (v in voiceListResult.voices) {
                        if (v.name.contains(voiceFolder, ignoreCase = true) || v.shortName.contains(voiceFolder, ignoreCase = true)) {
                            detectedVoiceName = v.name
                            break
                        }
                    }
                    if (detectedVoiceName.isEmpty() && voiceListResult.voices.isNotEmpty()) {
                        detectedVoiceName = voiceListResult.voices[0].name
                    }
                }
                tempSynthesizer.close()
                
                if (detectedVoiceName.isNotEmpty()) {
                    val voiceBaseDir = File(context.filesDir, voiceFolder)
                    val decryptionKey = getDecryptionKey(voiceBaseDir)
                    
                    config.setSpeechSynthesisVoice(detectedVoiceName, decryptionKey)
                    if (decryptionKey.isNotEmpty()) {
                        config.setProperty(PropertyId.SpeechServiceConnection_SynthModelKey, decryptionKey)
                    }
                    config.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm)
                    
                    val synth = SpeechSynthesizer(config, null)
                    cachedConfigs[voiceFolder] = config
                    cachedSynthesizers[voiceFolder] = synth
                    return synth
                } else {
                    config.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }
    }

    private fun writeDirectlyToOutput(byteArray: ByteArray, callback: SynthesisCallback?, track: AudioTrack?): Int {
        var totalWritten = 0
        if (callback != null) {
            val limit = if (callback.maxBufferSize > 0) callback.maxBufferSize else 8192
            var offset = 0
            while (offset < byteArray.size && !isStopped) {
                val chunk = min(limit, byteArray.size - offset)
                if (chunk <= 0) break
                if (callback.audioAvailable(byteArray, offset, chunk) == TextToSpeech.ERROR) {
                    isStopped = true
                    break
                }
                totalWritten += chunk
                offset += chunk
            }
        } else if (track != null) {
            try {
                val res = track.write(byteArray, 0, byteArray.size)
                if (res > 0) totalWritten = res
                else if (res < 0) isDirectStopped = true
            } catch (e: Exception) {
                isDirectStopped = true
            }
        }
        return totalWritten
    }

    private fun synthesizeNeural(context: Context, text: String, voiceType: String?, rate: Float, pitch: Float, callback: SynthesisCallback?, track: AudioTrack?, volume: Int): Int {
        var totalBytesGenerated = 0
        try {
            val voiceFolder = if (voiceType == "nilar") "Nilar" else "Thiha"
            val synthesizer = getNeuralSynthesizer(context, voiceFolder)
            
            if (synthesizer == null) return -1 
            
            val result = synthesizer.StartSpeakingTextAsync(text).get()
            
            if (result.reason == ResultReason.SynthesizingAudioStarted || result.reason == ResultReason.SynthesizingAudioCompleted) {
                val stream = AudioDataStream.fromResult(result)
                val bufferSize = 2048
                val buffer = ByteArray(bufferSize)
                var bytesRead: Long
                
                val bypassSonic = (rate == 1.0f && pitch == 1.0f)
                var streamId: Long = -1
                var shortBuffer: ShortArray? = null
                var outputBuffer: ShortArray? = null
                
                if (!bypassSonic) {
                    streamId = sonicCreateStream(OUTPUT_SAMPLE_RATE, OUTPUT_CHANNEL_COUNT)
                    sonicSetSpeed(streamId, rate)
                    sonicSetPitch(streamId, pitch)
                    shortBuffer = ShortArray(2048)
                    outputBuffer = ShortArray(4096)
                }
                
                while (true) {
                    if (isStopped || (track != null && isDirectStopped)) break
                    
                    bytesRead = stream.readData(buffer)
                    if (bytesRead <= 0) break
                    
                    val shortsRead = (bytesRead / 2).toInt()
                    val pcmShorts = ShortArray(shortsRead)
                    ByteBuffer.wrap(buffer, 0, bytesRead.toInt()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmShorts)
                    
                    if (bypassSonic) {
                        applyGain(pcmShorts, shortsRead, 1.0f * (volume / 100f))
                        val outBytes = ByteArray(shortsRead * 2)
                        for (i in 0 until shortsRead) {
                            outBytes[i * 2] = (pcmShorts[i].toInt() and 0xFF).toByte()
                            outBytes[i * 2 + 1] = ((pcmShorts[i].toInt() ushr 8) and 0xFF).toByte()
                        }
                        totalBytesGenerated += writeDirectlyToOutput(outBytes, callback, track)
                    } else {
                        totalBytesGenerated += feedToSonic(streamId, pcmShorts, shortBuffer!!, 2048, outputBuffer!!, callback, track, volume, 1.0f)
                    }
                }
                
                if (!bypassSonic) {
                    sonicFlushStream(streamId)
                    totalBytesGenerated += processSonicOutput(streamId, outputBuffer!!, callback, track, volume, 1.0f)
                    sonicDestroyStream(streamId)
                }
                stream.close()
                
            } else if (result.reason == ResultReason.Canceled) {
                val cancellation = SpeechSynthesisCancellationDetails.fromResult(result)
                if (cancellation.reason == CancellationReason.Error) {
                    clearNeuralCacheFor(voiceFolder)
                    result.close()
                    return -1 
                }
            }
            result.close()
        } catch (e: Throwable) {
            e.printStackTrace()
            return -1 
        }
        return totalBytesGenerated
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        
        val prefs = getSharedPreferences("mettavoice_tts_prefs", Context.MODE_PRIVATE)
        val readNumbers = prefs.getBoolean("pref_read_numbers", true)
        val voiceType = prefs.getString("pref_primary_voice", "legacy")
        val useNeural = voiceType != "legacy" && voiceType != null && Build.VERSION.SDK_INT >= 31
        
        val rawText = request.charSequenceText?.toString() ?: ""
        val text = if (isExpired()) "စမ်းသပ်ကာလ ပြီးဆုံးသွားပါပြီ အချောသတ်ဗားရှင်းကို စောင့်မျှော်ပေးပါ" else fixTypo(rawText, readNumbers)
        isStopped = false
        isDirectStopped = false

        if (text.isBlank()) {
            callback.start(OUTPUT_SAMPLE_RATE, OUTPUT_ENCODING, OUTPUT_CHANNEL_COUNT)
            generateSilentAudio(callback)
            safeCallbackDone(callback)
            return
        }

        try {
            val cpuTimeout = Math.max(120000L, (text.length * 300).toLong())
            try { cpuWakeLock?.acquire(cpuTimeout) } catch (e: Exception) {}
            
            val chunks = TTSUtils.splitText(text)
            val systemRate = request.speechRate / 100.0f
            val systemPitch = request.pitch / 100.0f
            
            val finalRate = (systemRate * prefs.getFloat("pref_speed", 0.8f)).coerceIn(0.1f, 4.0f)
            val finalPitch = (systemPitch * prefs.getFloat("pref_pitch", 1.0f)).coerceIn(0.5f, 2.0f)
            val volume = prefs.getInt("pref_volume", 100)
            val readPunctuation = prefs.getBoolean("pref_read_punctuation", false)

            val startStatus = callback.start(OUTPUT_SAMPLE_RATE, OUTPUT_ENCODING, OUTPUT_CHANNEL_COUNT)
            if (startStatus == TextToSpeech.ERROR) {
                return
            }

            var myanmarBytesAccumulated = 0
            var myanmarStartTime = 0L
            var lastMyanmarText = ""

            for (chunk in chunks) {
                if (isStopped) break
                
                if (chunk.lang == "MYANMAR") {
                    if (myanmarBytesAccumulated == 0) {
                        myanmarStartTime = System.currentTimeMillis()
                    }
                    
                    if (!useNeural) {
                        myanmarBytesAccumulated += synthesizeBurmese(chunk.text, finalRate, finalPitch, callback, null, readPunctuation, volume)
                    } else {
                        val neuralChunks = chunkTextForNeural(chunk.text)
                        for (nChunk in neuralChunks) {
                            if (isStopped) break
                            
                            val nChunkTrimmed = nChunk.trim()
                            val textToSynthesize = if (neuralSingleCharMap.containsKey(nChunkTrimmed)) {
                                neuralSingleCharMap[nChunkTrimmed]!!
                            } else {
                                nChunk
                            }
                            
                            val neuralBytes = synthesizeNeural(this, textToSynthesize, voiceType, finalRate, finalPitch, callback, null, volume)
                            if (neuralBytes > 0) {
                                myanmarBytesAccumulated += neuralBytes
                            }
                        }
                    }
                    lastMyanmarText = chunk.text
                    
                } else if (chunk.lang == "ENGLISH" && isEnglishReady) {
                    if (myanmarBytesAccumulated > 0) {
                        val expectedDurationMs = (myanmarBytesAccumulated * 1000L) / (OUTPUT_SAMPLE_RATE * OUTPUT_CHANNEL_COUNT * 2)
                        val elapsedTime = System.currentTimeMillis() - myanmarStartTime
                        
                        val lastMyTextTrimmed = lastMyanmarText.trim()
                        val hasDot = lastMyTextTrimmed.endsWith(".") || lastMyTextTrimmed.endsWith("။")
                        
                        val gap = if (useNeural) {
                            if (hasDot) 50L else 150L 
                        } else {
                            if (hasDot) 50L else 50L 
                        }
                        
                        val sleepTime = expectedDurationMs - elapsedTime + gap 
                        
                        if (sleepTime > 0) {
                            var waitTime = sleepTime
                            while (waitTime > 0 && !isStopped) {
                                val step = Math.min(30L, waitTime)
                                try { Thread.sleep(step) } catch (e: Exception) {}
                                waitTime -= step
                            }
                        }
                        myanmarBytesAccumulated = 0
                    }

                    if (isStopped) break

                    val utteranceId = "utt_${System.nanoTime()}"
                    val latch = CountDownLatch(1)
                    utteranceLatches[utteranceId] = latch
                    
                    englishEngine?.setSpeechRate(systemRate)
                    englishEngine?.setPitch(systemPitch)
                    
                    val params = Bundle()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    
                    val result = englishEngine?.speak(chunk.text, TextToSpeech.QUEUE_ADD, params, utteranceId)
                    if (result == TextToSpeech.SUCCESS) {
                        try {
                            val timeoutMs = Math.max(3000L, (chunk.text.length * 200).toLong())
                            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                            
                            val lastEnTextTrimmed = chunk.text.trim()
                            val hasDot = lastEnTextTrimmed.endsWith(".") || lastEnTextTrimmed.endsWith("။")
                            
                            val gap = if (useNeural) {
                                0L
                            } else {
                                if (hasDot) 50L else 50L 
                            }
                            
                            if (!isStopped && gap > 0) Thread.sleep(gap)
                        } catch (e: InterruptedException) {
                            isStopped = true
                        }
                    } else {
                        utteranceLatches.remove(utteranceId)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            safeCallbackDone(callback)
            try {
                if (cpuWakeLock?.isHeld == true) {
                    cpuWakeLock?.release()
                }
            } catch (e: Exception) {}
        }
    }

    private fun prepareDirectAudioTrackForAutoTTS() {
        if (directAudioTrack != null) {
            if (directAudioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                return
            } else {
                try {
                    directAudioTrack?.release()
                } catch (_: Exception) {}
                directAudioTrack = null
            }
        }
        
        val minBufferSize = AudioTrack.getMinBufferSize(OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        directAudioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) 
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(OUTPUT_SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_ACCESSIBILITY, OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2, AudioTrack.MODE_STREAM)
        }
    }

    fun stopDirectAudio() {
        isDirectStopped = true
        try {
            directAudioTrack?.pause()
            directAudioTrack?.flush()
            directAudioTrack?.stop()
            directAudioTrack?.release()
        } catch (_: Exception) {}
        directAudioTrack = null
    }

    fun playDirectAudio(context: Context, requestText: String, rate: Float, pitch: Float) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        
        stopDirectAudio()
        isDirectStopped = false
        initResources(context)
        
        val prefs = context.getSharedPreferences("mettavoice_tts_prefs", Context.MODE_PRIVATE)
        val readNumbers = prefs.getBoolean("pref_read_numbers", true)
        val voiceType = prefs.getString("pref_primary_voice", "legacy")
        val useNeural = voiceType != "legacy" && voiceType != null && Build.VERSION.SDK_INT >= 31
        
        val text = if (isExpired()) "စမ်းသပ်ကာလ ပြီးဆုံးသွားပါပြီ အချောသတ်ဗားရှင်းကို စောင့်မျှော်ပေးပါ" else fixTypo(requestText, readNumbers)
        if (text.isBlank()) return
        prepareDirectAudioTrackForAutoTTS()
        try {
            directAudioTrack?.play()
        } catch (_: Exception) {}
        
        val readPunctuation = prefs.getBoolean("pref_read_punctuation", false)
        val volume = prefs.getInt("pref_volume", 100)

        val chunks = TTSUtils.splitText(text)
        var myanmarBytesAccumulated = 0
        var myanmarStartTime = 0L
        var lastMyanmarText = ""

        for (chunk in chunks) {
            if (isDirectStopped) break
            if (chunk.lang == "MYANMAR") {
                if (myanmarBytesAccumulated == 0) {
                    myanmarStartTime = System.currentTimeMillis()
                }
                
                if (!useNeural) {
                    myanmarBytesAccumulated += synthesizeBurmese(chunk.text, rate.coerceIn(0.1f, 4.0f), pitch.coerceIn(0.5f, 2.0f), null, directAudioTrack, readPunctuation, volume)
                } else {
                    val neuralChunks = chunkTextForNeural(chunk.text)
                    for (nChunk in neuralChunks) {
                        if (isDirectStopped) break
                        
                        val nChunkTrimmed = nChunk.trim()
                        val textToSynthesize = if (neuralSingleCharMap.containsKey(nChunkTrimmed)) {
                            neuralSingleCharMap[nChunkTrimmed]!!
                        } else {
                            nChunk
                        }
                        
                        val neuralBytes = synthesizeNeural(context, textToSynthesize, voiceType, rate.coerceIn(0.1f, 4.0f), pitch.coerceIn(0.5f, 2.0f), null, directAudioTrack, volume)
                        if (neuralBytes > 0) {
                            myanmarBytesAccumulated += neuralBytes
                        }
                    }
                }
                lastMyanmarText = chunk.text
                
            } else if (chunk.lang == "ENGLISH" && isEnglishReady) {
                if (myanmarBytesAccumulated > 0) {
                    val expectedDurationMs = (myanmarBytesAccumulated * 1000L) / (OUTPUT_SAMPLE_RATE * OUTPUT_CHANNEL_COUNT * 2)
                    val elapsedTime = System.currentTimeMillis() - myanmarStartTime
                    
                    val lastMyTextTrimmed = lastMyanmarText.trim()
                    val hasDot = lastMyTextTrimmed.endsWith(".") || lastMyTextTrimmed.endsWith("။")
                    
                    val gap = if (useNeural) {
                        if (hasDot) 50L else 150L 
                    } else {
                        if (hasDot) 50L else 50L 
                    }
                    
                    val sleepTime = expectedDurationMs - elapsedTime + gap
                    if (sleepTime > 0) {
                        var waitTime = sleepTime
                        while (waitTime > 0 && !isDirectStopped) {
                            val step = Math.min(30L, waitTime)
                            try { Thread.sleep(step) } catch (e: Exception) {}
                            waitTime -= step
                        }
                    }
                    myanmarBytesAccumulated = 0
                }

                if (isDirectStopped) break

                val utteranceId = "utt_${System.nanoTime()}"
                val latch = CountDownLatch(1)
                utteranceLatches[utteranceId] = latch
                
                englishEngine?.setSpeechRate(1.0f)
                englishEngine?.setPitch(1.0f)
                
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                
                val result = englishEngine?.speak(chunk.text, TextToSpeech.QUEUE_ADD, params, utteranceId)
                if (result == TextToSpeech.SUCCESS) {
                    try {
                        val timeoutMs = Math.max(3000L, (chunk.text.length * 200).toLong())
                        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                        
                        val lastEnTextTrimmed = chunk.text.trim()
                        val hasDot = lastEnTextTrimmed.endsWith(".") || lastEnTextTrimmed.endsWith("။")
                        
                        val gap = if (useNeural) {
                            0L
                        } else {
                            if (hasDot) 50L else 50L 
                        }
                        
                        if (!isDirectStopped && gap > 0) Thread.sleep(gap)
                    } catch (e: InterruptedException) {
                        isDirectStopped = true
                    }
                } else {
                    utteranceLatches.remove(utteranceId)
                }
            }
        }
        
        try {
            directAudioTrack?.stop()
            directAudioTrack?.release()
        } catch (_: Exception) {}
        directAudioTrack = null
    }

    private fun isValidSyllableBoundary(text: String, j: Int): Boolean {
        if (j >= text.length) return true
        val c = text[j]
        
        if (c in '\u102B'..'\u103E' || 
            c in '\u1056'..'\u1059' ||
            c in '\u105E'..'\u1060' ||
            c in '\u1062'..'\u1064' ||
            c in '\u1067'..'\u106D' ||
            c in '\u1071'..'\u1074' ||
            c in '\u1082'..'\u108D' ||
            c == '\u108F' ||
            c in '\u109A'..'\u109D') {
            return false
        }
        
        if (j + 1 < text.length) {
            val nextC = text[j + 1]
            if (nextC == '\u103A' || nextC == '\u1039') {
                return false
            }
        }
        
        if (j + 2 < text.length) {
            val nextC = text[j + 1]
            val nextNextC = text[j + 2]
            if ((nextC == '\u1037' || nextC == '\u1038') && nextNextC == '\u103A') {
                return false
            }
        }

        return true
    }

    private fun getFallbackSyllable(sub: String): String? {
        val codas = arrayOf(
            "လ်", "စ်", "ဇ်", "ဖ်", "ဗ်", "ဒ်", "ဂ်", "သ်", "ဟ်", "ရ်", "က်စ်", "ပ်စ်"
        )
        for (coda in codas) {
            if (sub.endsWith(coda) && sub.length > coda.length) {
                return sub.substring(0, sub.length - coda.length)
            }
        }
        return null
    }

    private fun splitTextIntoPlayableUnits(text: String, phraseMap: Map<String, String>, map: Map<String, String>): List<String> {
        val res = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var best = ""
            var matchLength = 0
            
            for (j in minOf(text.length, i + 50) downTo i + 1) {
                val sub = text.substring(i, j)
                
                if (isValidSyllableBoundary(text, j)) {
                    if (phraseMap.containsKey(sub) || map.containsKey(sub)) {
                        best = sub
                        matchLength = sub.length
                        break
                    }
                    val fallback = getFallbackSyllable(sub)
                    if (fallback != null && (phraseMap.containsKey(fallback) || map.containsKey(fallback))) {
                        best = fallback
                        matchLength = sub.length
                        break
                    }
                }
            }
            
            if (best.isNotEmpty()) {
                res.add(best)
                i += matchLength
            } else {
                val c = text.substring(i, i + 1)
                if (c == "\n") {
                    res.add("[NEWLINE]")
                    while (i + 1 < text.length && text.substring(i + 1, i + 2) == "\n") i++
                } else if (c == " ") {
                    res.add("[SPACE]")
                    while (i + 1 < text.length && text.substring(i + 1, i + 2) == " ") i++
                } else if (c == "၊" || c == "။" || c == "." || c == "?" || c == ";") {
                    res.add(c)
                } else if (!c.matches("\\s+".toRegex())) {
                    res.add(c)
                }
                i++
            }
        }
        return res
    }

    private fun synthesizeBurmese(text: String, rate: Float, pitch: Float, callback: SynthesisCallback?, track: AudioTrack?, readPunctuation: Boolean, volume: Int): Int {
        val bypassSonic = (rate == 1.0f && pitch == 1.0f)
        val currentMap = charMap ?: return 0
        val currentSingleMap = singleCharMap ?: emptyMap()
        val currentPhraseMap = phraseMap ?: emptyMap()
        
        val isSingleChar = text.length == 1 && currentSingleMap.containsKey(text)
        val units = if (isSingleChar) listOf(text) else splitTextIntoPlayableUnits(text, currentPhraseMap, currentMap)
        if (units.isEmpty()) return 0

        val nonWhitespaceUnits = units.filter { it != "[SPACE]" && it != "[NEWLINE]" }
        val isIsolatedPunctuation = nonWhitespaceUnits.size == 1 && (nonWhitespaceUnits[0] == "၊" || nonWhitespaceUnits[0] == "။")

        var streamId: Long = -1
        val bufferSize = 4096
        var shortBuffer: ShortArray? = null
        var outputBuffer: ShortArray? = null

        if (!bypassSonic) {
            streamId = sonicCreateStream(OUTPUT_SAMPLE_RATE, OUTPUT_CHANNEL_COUNT)
            sonicSetSpeed(streamId, rate)
            sonicSetPitch(streamId, pitch)
            shortBuffer = ShortArray(bufferSize)
            outputBuffer = ShortArray(bufferSize)
        }

        var prevTail: ShortArray? = null
        var totalBytesGenerated = 0

        try {
            for (unit in units) {
                if (callback != null && isStopped) break
                if (track != null && isDirectStopped) break
                
                var pauseDuration = 0
                when (unit) {
                    "[NEWLINE]" -> pauseDuration = 400
                    "[SPACE]" -> pauseDuration = 150
                }
                
                if (!readPunctuation && !isIsolatedPunctuation) {
                    if (unit == "၊") {
                        pauseDuration = 200
                    } else if (unit == "။") {
                        pauseDuration = 400
                    }
                }

                if (pauseDuration > 0) {
                    if (prevTail != null) {
                        applyFadeOut(prevTail, prevTail.size)
                        if (bypassSonic) {
                            applyGain(prevTail, prevTail.size, 1.0f * (volume / 100f))
                            val outBytes = ByteArray(prevTail.size * 2)
                            for (i in prevTail.indices) {
                                outBytes[i * 2] = (prevTail[i].toInt() and 0xFF).toByte()
                                outBytes[i * 2 + 1] = ((prevTail[i].toInt() ushr 8) and 0xFF).toByte()
                            }
                            totalBytesGenerated += writeDirectlyToOutput(outBytes, callback, track)
                        } else {
                            totalBytesGenerated += feedToSonic(streamId, prevTail, shortBuffer!!, bufferSize, outputBuffer!!, callback, track, volume, 1.0f)
                        }
                        prevTail = null
                    }
                    if (bypassSonic) {
                        val numSamples = (OUTPUT_SAMPLE_RATE * pauseDuration) / 1000
                        if (numSamples > 0) {
                            val silenceBytes = ByteArray(numSamples * 2)
                            totalBytesGenerated += writeDirectlyToOutput(silenceBytes, callback, track)
                        }
                    } else {
                        writeSilenceToSonic(streamId, pauseDuration)
                        totalBytesGenerated += processSonicOutput(streamId, outputBuffer!!, callback, track, volume, 1.0f)
                    }
                    continue
                }

                val baseName = if (isSingleChar && currentSingleMap.containsKey(unit)) {
                    currentSingleMap[unit]
                } else {
                    currentPhraseMap[unit] ?: currentMap[unit] ?: currentSingleMap[unit]
                }

                if (baseName == null) continue
                val encodedBytes = readAudioFromBin(baseName)

                if (encodedBytes != null && encodedBytes.isNotEmpty()) {
                    val originalPcm = decodeOpus(encodedBytes, encodedBytes.size)
                    if (originalPcm != null && originalPcm.isNotEmpty()) {
                        
                        val pauseSamples = (OUTPUT_SAMPLE_RATE * 10) / 1000
                        val pcmShorts = ShortArray(originalPcm.size + pauseSamples)
                        System.arraycopy(originalPcm, 0, pcmShorts, 0, originalPcm.size)

                        if (prevTail != null && prevTail.isNotEmpty()) {
                            val crossfadeLen = min(CROSSFADE_SAMPLES, min(prevTail.size, pcmShorts.size))
                            if (crossfadeLen > 0) {
                                val crossfaded = ShortArray(crossfadeLen)
                                for (i in 0 until crossfadeLen) {
                                    val t = i.toFloat() / crossfadeLen
                                    val fadeOut = (0.5 * (1.0 + cos(PI * t))).toFloat()
                                    val fadeIn = 1.0f - fadeOut
                                    val mixed = (prevTail[prevTail.size - crossfadeLen + i] * fadeOut + pcmShorts[i] * fadeIn)
                                    crossfaded[i] = mixed.toInt().coerceIn(-32768, 32767).toShort()
                                }
                                val prevMainLen = prevTail.size - crossfadeLen
                                if (prevMainLen > 0) {
                                    val prevMain = prevTail.copyOfRange(0, prevMainLen)
                                    if (bypassSonic) {
                                        applyGain(prevMain, prevMain.size, 1.0f * (volume / 100f))
                                        val outBytes = ByteArray(prevMain.size * 2)
                                        for (i in prevMain.indices) {
                                            outBytes[i * 2] = (prevMain[i].toInt() and 0xFF).toByte()
                                            outBytes[i * 2 + 1] = ((prevMain[i].toInt() ushr 8) and 0xFF).toByte()
                                        }
                                        totalBytesGenerated += writeDirectlyToOutput(outBytes, callback, track)
                                    } else {
                                        totalBytesGenerated += feedToSonic(streamId, prevMain, shortBuffer!!, bufferSize, outputBuffer!!, callback, track, volume, 1.0f)
                                    }
                                }
                                
                                if (bypassSonic) {
                                    applyGain(crossfaded, crossfaded.size, 1.0f * (volume / 100f))
                                    val outBytes = ByteArray(crossfaded.size * 2)
                                    for (i in crossfaded.indices) {
                                        outBytes[i * 2] = (crossfaded[i].toInt() and 0xFF).toByte()
                                        outBytes[i * 2 + 1] = ((crossfaded[i].toInt() ushr 8) and 0xFF).toByte()
                                    }
                                    totalBytesGenerated += writeDirectlyToOutput(outBytes, callback, track)
                                } else {
                                    totalBytesGenerated += feedToSonic(streamId, crossfaded, shortBuffer!!, bufferSize, outputBuffer!!, callback, track, volume, 1.0f)
                                }

                                val currentRemaining = pcmShorts.copyOfRange(crossfadeLen, pcmShorts.size)
                                if (currentRemaining.size > CROSSFADE_SAMPLES) {
                                    val mainPart = currentRemaining.copyOfRange(0, currentRemaining.size - CROSSFADE_SAMPLES)
                                    if (bypassSonic) {
                                        applyGain(mainPart, mainPart.size, 1.0f * (volume / 100f))
                                        val outBytes = ByteArray(mainPart.size * 2)
                                        for (i in mainPart.indices) {
                                            outBytes[i * 2] = (mainPart[i].toInt() and 0xFF).toByte()
                                            outBytes[i * 2 + 1] = ((mainPart[i].toInt() ushr 8) and 0xFF).toByte()
                                        }
                                        totalBytesGenerated += writeDirectlyToOutput(outBytes, callback, track)
                                    } else {
                                        totalBytesGenerated += feedToSonic(streamId, mainPart, shortBuffer!!, bufferSize, outputBuffer!!, callback, track, volume, 1.0f)
                                    }
                                    prevTail = currentRemaining.copyOfRange(currentRemaining.size - CROSSFADE_SAMPLES, currentRemaining.size)
                                } else {
                                    prevTail = currentRemaining
                                }
                            } else {
                                if (bypassSonic) {
                                    applyGain(prevTail, prevTail.size, 1.0f * (volume / 100f))
                                    val outBytes = ByteArray(prevTail.size * 2)
                                    for (i in prevTail.indices) {
                                        outBytes[i * 2] = (prevTail[i].toInt() and 0xFF).toByte()
                                        outBytes[i * 2 + 1] = ((prevTail[i].toInt() ushr 8) and 0xFF).toByte()
                                    }
                                    totalBytesGenerated += writeDirectlyToOutput(outBytes, callback, track)
                                } else {
                                    totalBytesGenerated += feedToSonic(streamId, prevTail, shortBuffer!!, bufferSize, outputBuffer!!, callback, track, volume, 1.0f)
                                }
                                
                                if (pcmShorts.size > CROSSFADE_SAMPLES) {
                                    val mainPart = pcmShorts.copyOfRange(0, pcmShorts.size - CROSSFADE_SAMPLES)
                                    if (bypassSonic) {
                                        applyGain(mainPart, mainPart.size, 1.0f * (volume / 100f))
                                        val outBytes = ByteArray(mainPart.size * 2)
                                        for (i in mainPart.indices) {
                                            outBytes[i * 2] = (mainPart[i].toInt() and 0xFF).toByte()
                                            outBytes[i * 2 + 1] = ((mainPart[i].toInt() ushr 8) and 0xFF).toByte()
                                        }
                                        totalBytesGenerated += writeDirectlyToOutput(outBytes, callback, track)
                                    } else {
                                        totalBytesGenerated += feedToSonic(streamId, mainPart, shortBuffer!!, bufferSize, outputBuffer!!, callback, track, volume, 1.0f)
                                    }
                                    prevTail = pcmShorts.copyOfRange(pcmShorts.size - CROSSFADE_SAMPLES, pcmShorts.size)
                                } else {
                                    prevTail = pcmShorts
                                }
                            }
                        } else {
                            applyFadeIn(pcmShorts, FADE_SAMPLES)
                            if (pcmShorts.size > CROSSFADE_SAMPLES) {
                                val mainPart = pcmShorts.copyOfRange(0, pcmShorts.size - CROSSFADE_SAMPLES)
                                if (bypassSonic) {
                                    applyGain(mainPart, mainPart.size, 1.0f * (volume / 100f))
                                    val outBytes = ByteArray(mainPart.size * 2)
                                    for (i in mainPart.indices) {
                                        outBytes[i * 2] = (mainPart[i].toInt() and 0xFF).toByte()
                                        outBytes[i * 2 + 1] = ((mainPart[i].toInt() ushr 8) and 0xFF).toByte()
                                    }
                                    totalBytesGenerated += writeDirectlyToOutput(outBytes, callback, track)
                                } else {
                                    totalBytesGenerated += feedToSonic(streamId, mainPart, shortBuffer!!, bufferSize, outputBuffer!!, callback, track, volume, 1.0f)
                                }
                                prevTail = pcmShorts.copyOfRange(pcmShorts.size - CROSSFADE_SAMPLES, pcmShorts.size)
                            } else {
                                prevTail = pcmShorts
                            }
                        }
                    }
                }
            }
            if (prevTail != null && prevTail.isNotEmpty()) {
                if ((callback == null || !isStopped) && (track == null || !isDirectStopped)) {
                    applyFadeOut(prevTail, FADE_SAMPLES)
                    if (bypassSonic) {
                        applyGain(prevTail, prevTail.size, 1.0f * (volume / 100f))
                        val outBytes = ByteArray(prevTail.size * 2)
                        for (i in prevTail.indices) {
                            outBytes[i * 2] = (prevTail[i].toInt() and 0xFF).toByte()
                            outBytes[i * 2 + 1] = ((prevTail[i].toInt() ushr 8) and 0xFF).toByte()
                        }
                        totalBytesGenerated += writeDirectlyToOutput(outBytes, callback, track)
                    } else {
                        totalBytesGenerated += feedToSonic(streamId, prevTail, shortBuffer!!, bufferSize, outputBuffer!!, callback, track, volume, 1.0f)
                    }
                }
            }
            
            if (!bypassSonic) {
                sonicFlushStream(streamId)
                totalBytesGenerated += processSonicOutput(streamId, outputBuffer!!, callback, track, volume, 1.0f)
            }

        } finally {
            if (!bypassSonic) {
                sonicDestroyStream(streamId)
            }
        }
        return totalBytesGenerated
    }

    private fun feedToSonic(streamId: Long, data: ShortArray, shortBuffer: ShortArray, bufferSize: Int, outputBuffer: ShortArray, callback: SynthesisCallback?, track: AudioTrack?, volume: Int, gainMultiplier: Float = 1.0f): Int {
        var inputOffset = 0
        var totalWritten = 0
        while (inputOffset < data.size) {
            if (callback != null && isStopped) break
            if (track != null && isDirectStopped) break
            
            val inputLen = min(bufferSize, data.size - inputOffset)
            System.arraycopy(data, inputOffset, shortBuffer, 0, inputLen)
            sonicWriteShortToStream(streamId, shortBuffer, inputLen)
            totalWritten += processSonicOutput(streamId, outputBuffer, callback, track, volume, gainMultiplier)
            inputOffset += inputLen
        }
        return totalWritten
    }

    private fun processSonicOutput(streamId: Long, outputBuffer: ShortArray, callback: SynthesisCallback?, track: AudioTrack?, volume: Int, gainMultiplier: Float = 1.0f): Int {
        var totalWritten = 0
        while (sonicSamplesAvailable(streamId) > 0) {
            if (callback != null && isStopped) break
            if (track != null && isDirectStopped) break
            
            val readCount = sonicReadShortFromStream(streamId, outputBuffer, outputBuffer.size)
            if (readCount > 0) {
                applyGain(outputBuffer, readCount, gainMultiplier * (volume / 100f))
                
                val byteArray = ByteArray(readCount * 2)
                for (i in 0 until readCount) {
                    val shortVal = outputBuffer[i].toInt()
                    byteArray[i * 2] = (shortVal and 0xFF).toByte()
                    byteArray[i * 2 + 1] = ((shortVal ushr 8) and 0xFF).toByte()
                }
                
                totalWritten += writeDirectlyToOutput(byteArray, callback, track)
            } else {
                break
            }
        }
        return totalWritten
    }

    private fun applyFadeIn(audio: ShortArray, fadeSamples: Int) {
        val len = min(fadeSamples, audio.size)
        for (i in 0 until len) {
            val t = i.toFloat() / len
            val gain = (0.5 * (1.0 - cos(PI * t))).toFloat()
            audio[i] = (audio[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun applyFadeOut(audio: ShortArray, fadeSamples: Int) {
        val len = min(fadeSamples, audio.size)
        val valStartIdx = audio.size - len
        for (i in 0 until len) {
            val t = i.toFloat() / len
            val gain = (0.5 * (1.0 + cos(PI * t))).toFloat()
            audio[valStartIdx + i] = (audio[valStartIdx + i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun readAudioFromBin(name: String): ByteArray? {
        val info = indexMap[name] ?: return null
        val offset = info.first
        val length = info.second
        val raf = randomAccessFile ?: return null
        return try {
            val buffer = ByteArray(length)
            synchronized(raf) {
                raf.seek(offset)
                raf.readFully(buffer)
            }
            buffer
        } catch (e: Exception) {
            null
        }
    }

    private fun applyGain(audio: ShortArray, length: Int, gain: Float) {
        if (gain == 1.0f) return 
        for (i in 0 until length) {
            val amplified = (audio[i] * gain).toInt()
            audio[i] = amplified.coerceIn(-32768, 32767).toShort()
        }
    }

    private fun writeSilenceToSonic(streamId: Long, durationMs: Int) {
        val numSamples = (OUTPUT_SAMPLE_RATE * durationMs) / 1000
        if (numSamples > 0) {
            sonicWriteShortToStream(streamId, ShortArray(numSamples), numSamples)
        }
    }

    private fun generateSilentAudio(callback: SynthesisCallback) {
        val silence = ByteArray((OUTPUT_SAMPLE_RATE * 100 / 1000) * 2)
        callback.audioAvailable(silence, 0, silence.size)
    }

    private fun safeCallbackDone(callback: SynthesisCallback) { try { callback.done() } catch (_: Exception) {} }

    override fun onGetVoices(): MutableList<Voice> = mutableListOf(Voice("mya-MMR", Locale.Builder().setLanguage("my").setRegion("MM").build(), Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, hashSetOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)))
    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String = "mya-MMR"
    override fun onIsValidVoiceName(voiceName: String): Int = if (voiceName == "mya-MMR") TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onGetFeaturesForLanguage(lang: String?, country: String?, variant: String?): MutableSet<String> = hashSetOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)

    override fun onDestroy() {
        isStopped = true
        isDirectStopped = true
        stopDirectAudio()
        try {
            englishEngine?.stop()
            englishEngine?.shutdown()
        } catch (_: Exception) {}
        
        utteranceLatches.values.forEach { it.countDown() }
        utteranceLatches.clear()
        
        try {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
            }
        } catch (e: Exception) {}

        if (isOpusInit) {
            destroyOpusDecoder()
            isOpusInit = false
        }
        
        clearAllNeuralCaches()
        try { randomAccessFile?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
