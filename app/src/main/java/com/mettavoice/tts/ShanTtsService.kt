package com.mettavoice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
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
        private const val OUTPUT_SAMPLE_RATE = 16000
        private const val OUTPUT_CHANNEL_COUNT = 1
        private const val OUTPUT_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BIN_FILENAME = "audio.bin"
        private const val INDEX_FILENAME = "index.txt"
        private const val CROSSFADE_SAMPLES = 80
        private const val FADE_SAMPLES = 48

        // ရှမ်းစာ Block (\uAA60-\uAA7F) များကို ဖယ်ရှားပြီး မြန်မာစာ (\u1000-\u109F) သီးသန့်သာ ထားရှိထားပါသည်
        private val TOKEN_PATTERN = Pattern.compile("([\\u1000-\\u109F]+)|([^\\u1000-\\u109F\\s]+)|(\\s+)")
    }

    data class Chunk(val text: String, val lang: String)

    private val wordMapping = ConcurrentHashMap<String, String>()

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
    
    private val stopRequested = AtomicBoolean(false)
    private val isDestroyed = AtomicBoolean(false)
    private val isDirectStopped = AtomicBoolean(false)
    
    private var directAudioTrack: AudioTrack? = null
    private var isOpusInit = false
    
    private var englishEngine: TextToSpeech? = null
    private val isEnglishReady = AtomicBoolean(false)
    private val utteranceLatches = ConcurrentHashMap<String, CountDownLatch>()
    
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    private val isKeepAliveRunning = AtomicBoolean(false)
    @Volatile private var lastSpeechFinishedTime: Long = 0
    private var keepAliveThread: Thread? = null
    private val KEEP_ALIVE_TIMEOUT_MS = 4000L
    private val keepAliveLock = ReentrantLock()

    override fun onCreate() {
        super.onCreate()
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager?
        powerManager?.let {
            cpuWakeLock = it.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MettaVoice::CpuWakeLock")
            cpuWakeLock?.setReferenceCounted(false)
            
            @Suppress("DEPRECATION")
            screenWakeLock = it.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "MettaVoice::ScreenWakeLock")
            screenWakeLock?.setReferenceCounted(false)
        }
        
        initResources(this)
        loadWordMapping(this)
        initEnglishEngine()
    }

    private fun loadWordMapping(context: Context) {
        Thread {
            try {
                wordMapping.clear()
                val reader = BufferedReader(InputStreamReader(context.assets.open("mapping.txt")))
                reader.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        val parts = trimmed.split("=")
                        if (parts.size == 2) {
                            wordMapping[parts[0].trim()] = parts[1].trim()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun splitHelper(text: String?): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        if (text.isNullOrEmpty()) return chunks

        val matcher = TOKEN_PATTERN.matcher(text)
        val currentBuffer = StringBuilder()
        var currentLang: String? = null

        while (matcher.find()) {
            val token = matcher.group()
            if (token.isEmpty()) continue

            if (matcher.group(3) != null) {
                if (currentBuffer.isNotEmpty()) {
                    currentBuffer.append(token)
                }
                continue
            }

            val trimmedToken = token.trim()
            var detectedLang = "ENGLISH"

            if (wordMapping.containsKey(trimmedToken)) {
                detectedLang = wordMapping[trimmedToken] ?: "ENGLISH"
            } else if (matcher.group(1) != null) {
                detectedLang = "MYANMAR"
            }

            if (currentLang == null) {
                currentLang = detectedLang
                currentBuffer.append(token)
            } else if (currentLang == detectedLang) {
                currentBuffer.append(token)
            } else {
                chunks.add(Chunk(currentBuffer.toString(), currentLang))
                currentBuffer.setLength(0)
                currentBuffer.append(token)
                currentLang = detectedLang
            }
        }

        if (currentBuffer.isNotEmpty()) {
            chunks.add(Chunk(currentBuffer.toString(), currentLang ?: "ENGLISH"))
        }

        return chunks
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
                isEnglishReady.set(true)
            }
        }, enginePkg)

        englishEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { releaseLatch(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { releaseLatch(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) { releaseLatch(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { releaseLatch(utteranceId) }
            private fun releaseLatch(utteranceId: String?) {
                utteranceId?.let { utteranceLatches.remove(it)?.countDown() }
            }
        })
    }

    fun initResources(context: Context) {
        copyAssetToFile(context, BIN_FILENAME)
        copyAssetToFile(context, INDEX_FILENAME)
        
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

    private fun copyAssetToFile(context: Context, filename: String) {
        val file = File(context.filesDir, filename)
        if (!file.exists() || file.length() == 0L) {
            try {
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

    private fun triggerKeepAlive() {
        keepAliveLock.lock()
        try {
            lastSpeechFinishedTime = System.currentTimeMillis()
            
            if (!isKeepAliveRunning.get() && !isDestroyed.get()) {
                isKeepAliveRunning.set(true)
                keepAliveThread = Thread {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    var minBufferSize = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minBufferSize <= 0) minBufferSize = 32000

                    val silenceBuffer = ByteArray(minBufferSize)
                    for (i in silenceBuffer.indices step 2) {
                        silenceBuffer[i] = 1
                        silenceBuffer[i + 1] = 0
                    }

                    var keepAliveTrack: AudioTrack? = null
                    try {
                        keepAliveTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            AudioTrack.Builder()
                                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                                .setBufferSizeInBytes(minBufferSize)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build()
                        } else {
                            @Suppress("DEPRECATION")
                            AudioTrack(AudioManager.STREAM_ACCESSIBILITY, 16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize, AudioTrack.MODE_STREAM)
                        }
                        
                        if (keepAliveTrack.state == AudioTrack.STATE_UNINITIALIZED) return@Thread

                        keepAliveTrack.setVolume(AudioTrack.getMaxVolume())
                        keepAliveTrack.play()

                        while (isKeepAliveRunning.get() && !isDestroyed.get()) {
                            keepAliveTrack.write(silenceBuffer, 0, silenceBuffer.size)
                            if (System.currentTimeMillis() - lastSpeechFinishedTime > KEEP_ALIVE_TIMEOUT_MS) break
                        }
                    } catch (e: Exception) {
                    } finally {
                        isKeepAliveRunning.set(false)
                        try {
                            if (keepAliveTrack != null && keepAliveTrack.state != AudioTrack.STATE_UNINITIALIZED) {
                                if (keepAliveTrack.playState == AudioTrack.PLAYSTATE_PLAYING) keepAliveTrack.stop()
                                keepAliveTrack.release()
                            }
                        } catch (e: Exception) {}
                    }
                }
                keepAliveThread?.start()
            }
        } finally {
            keepAliveLock.unlock()
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

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = onIsLanguageAvailable(lang, country, variant)
    override fun onGetLanguage(): Array<String> = arrayOf("mya", "MMR", "")

    override fun onStop() {
        stopRequested.set(true)
        isDirectStopped.set(true)
        utteranceLatches.values.forEach { it.countDown() }
        utteranceLatches.clear()
        try { englishEngine?.stop() } catch (_: Exception) {}
        stopDirectAudio()
        releaseWakeLocks()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        if (isDestroyed.get()) {
            safeCallbackDone(callback)
            return
        }

        val rawText = request.charSequenceText?.toString() ?: ""
        stopRequested.set(false)

        if (rawText.isBlank()) {
            callback.start(OUTPUT_SAMPLE_RATE, OUTPUT_ENCODING, OUTPUT_CHANNEL_COUNT)
            generateSilentAudio(callback)
            safeCallbackDone(callback)
            releaseWakeLocks()
            return
        }

        try {
            val cpuTimeout = max(120000L, (rawText.length * 300).toLong())
            cpuWakeLock?.acquire(cpuTimeout)
            val screenTimeout = max(60000L, (rawText.length * 300).toLong())
            screenWakeLock?.acquire(screenTimeout)
        } catch (e: Exception) {}

        triggerKeepAlive()

        val chunks = splitHelper(rawText)
        
        val systemRate = request.speechRate / 100.0f
        val systemPitch = request.pitch / 100.0f
        val prefs = getSharedPreferences("mettavoice_tts_prefs", Context.MODE_PRIVATE)
        val finalRate = (systemRate * prefs.getFloat("pref_speed", 0.8f)).coerceIn(0.1f, 4.0f)
        val finalPitch = (systemPitch * prefs.getFloat("pref_pitch", 1.0f)).coerceIn(0.5f, 2.0f)

        callback.start(OUTPUT_SAMPLE_RATE, OUTPUT_ENCODING, OUTPUT_CHANNEL_COUNT)

        for (chunk in chunks) {
            if (stopRequested.get() || isDestroyed.get()) break
            
            lastSpeechFinishedTime = System.currentTimeMillis()
            
            if (chunk.lang == "MYANMAR") {
                synthesizeBurmeseText(chunk.text, callback, finalRate, finalPitch)
            } else if (chunk.lang == "ENGLISH" && isEnglishReady.get()) {
                var startIndex = 0
                val textLen = chunk.text.length
                val maxLen = 3500

                while (startIndex < textLen) {
                    if (stopRequested.get() || isDestroyed.get()) break
                    
                    var endIndex = min(startIndex + maxLen, textLen)
                    if (endIndex < textLen) {
                        var breakPoint = -1
                        for (j in endIndex - 1 downTo max(startIndex, endIndex - 500)) {
                            val c = chunk.text[j]
                            if (c == ' ' || c == '\n' || c == '။' || c == '၊' || c == '.' || c == ',') {
                                breakPoint = j + 1
                                break
                            }
                        }
                        if (breakPoint != -1) endIndex = breakPoint
                    }

                    val subText = chunk.text.substring(startIndex, endIndex)
                    startIndex = endIndex

                    val utteranceId = "utt_${System.nanoTime()}"
                    val latch = CountDownLatch(1)
                    utteranceLatches[utteranceId] = latch
                    
                    englishEngine?.setSpeechRate(finalRate)
                    englishEngine?.setPitch(finalPitch)
                    
                    val params = Bundle()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ACCESSIBILITY)
                    
                    val result = englishEngine?.speak(subText, TextToSpeech.QUEUE_ADD, params, utteranceId)
                    if (result == TextToSpeech.SUCCESS) {
                        try {
                            val timeoutMs = max(30000L, (subText.length * 300).toLong())
                            val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                            if (!done && !stopRequested.get() && !isDestroyed.get()) {
                                utteranceLatches.remove(utteranceId)
                                try { englishEngine?.stop() } catch (e: Exception) {}
                            }
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            stopRequested.set(true)
                        }
                    } else {
                        utteranceLatches.remove(utteranceId)?.countDown()
                    }
                }
            }
        }
        
        safeCallbackDone(callback)
        lastSpeechFinishedTime = System.currentTimeMillis()
        releaseWakeLocks()
    }

    private fun synthesizeBurmeseText(text: String, callback: SynthesisCallback, rate: Float, pitch: Float) {
        val currentMap = charMap ?: return
        val currentSingleMap = singleCharMap ?: emptyMap()
        val currentPhraseMap = phraseMap ?: emptyMap()
        val isSingleChar = text.length == 1 && currentSingleMap.containsKey(text)
        val units = if (isSingleChar) listOf(text) else splitTextIntoPlayableUnits(text, currentPhraseMap, currentMap)
        if (units.isEmpty()) return

        val streamId = sonicCreateStream(OUTPUT_SAMPLE_RATE, OUTPUT_CHANNEL_COUNT)
        sonicSetSpeed(streamId, rate)
        sonicSetPitch(streamId, pitch)

        val bufferSize = 4096
        val shortBuffer = ShortArray(bufferSize)
        val outputBuffer = ShortArray(bufferSize)
        var prevTail: ShortArray? = null

        try {
            for (unit in units) {
                if (stopRequested.get()) break
                var pauseDuration = 0
                when (unit) {
                    "[NEWLINE]" -> pauseDuration = 800
                    "[SPACE]" -> pauseDuration = 200
                }
                if (pauseDuration > 0) {
                    if (prevTail != null) {
                        applyFadeOut(prevTail, prevTail.size)
                        feedToSonic(streamId, prevTail, shortBuffer, bufferSize, outputBuffer, callback)
                        prevTail = null
                    }
                    writeSilenceToSonic(streamId, pauseDuration)
                    processSonicOutput(streamId, outputBuffer, callback)
                    continue
                }

                val baseName = if (isSingleChar) currentSingleMap[unit] else (currentPhraseMap[unit] ?: currentMap[unit])
                if (baseName == null) continue
                val encodedBytes = readAudioFromBin(baseName)

                if (encodedBytes != null && encodedBytes.isNotEmpty()) {
                    val originalPcm = decodeOpus(encodedBytes, encodedBytes.size)
                    if (originalPcm != null && originalPcm.isNotEmpty()) {
                        val pauseSamples = (OUTPUT_SAMPLE_RATE * 25) / 1000
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
                                    feedToSonic(streamId, prevMain, shortBuffer, bufferSize, outputBuffer, callback)
                                }
                                feedToSonic(streamId, crossfaded, shortBuffer, bufferSize, outputBuffer, callback)

                                val currentRemaining = pcmShorts.copyOfRange(crossfadeLen, pcmShorts.size)
                                if (currentRemaining.size > CROSSFADE_SAMPLES) {
                                    val mainPart = currentRemaining.copyOfRange(0, currentRemaining.size - CROSSFADE_SAMPLES)
                                    feedToSonic(streamId, mainPart, shortBuffer, bufferSize, outputBuffer, callback)
                                    prevTail = currentRemaining.copyOfRange(currentRemaining.size - CROSSFADE_SAMPLES, currentRemaining.size)
                                } else {
                                    prevTail = currentRemaining
                                }
                            } else {
                                feedToSonic(streamId, prevTail, shortBuffer, bufferSize, outputBuffer, callback)
                                if (pcmShorts.size > CROSSFADE_SAMPLES) {
                                    val mainPart = pcmShorts.copyOfRange(0, pcmShorts.size - CROSSFADE_SAMPLES)
                                    feedToSonic(streamId, mainPart, shortBuffer, bufferSize, outputBuffer, callback)
                                    prevTail = pcmShorts.copyOfRange(pcmShorts.size - CROSSFADE_SAMPLES, pcmShorts.size)
                                } else {
                                    prevTail = pcmShorts
                                }
                            }
                        } else {
                            applyFadeIn(pcmShorts, FADE_SAMPLES)
                            if (pcmShorts.size > CROSSFADE_SAMPLES) {
                                val mainPart = pcmShorts.copyOfRange(0, pcmShorts.size - CROSSFADE_SAMPLES)
                                feedToSonic(streamId, mainPart, shortBuffer, bufferSize, outputBuffer, callback)
                                prevTail = pcmShorts.copyOfRange(pcmShorts.size - CROSSFADE_SAMPLES, pcmShorts.size)
                            } else {
                                prevTail = pcmShorts
                            }
                        }
                    }
                }
            }
            if (prevTail != null && prevTail.isNotEmpty() && !stopRequested.get()) {
                applyFadeOut(prevTail, FADE_SAMPLES)
                feedToSonic(streamId, prevTail, shortBuffer, bufferSize, outputBuffer, callback)
            }
            sonicFlushStream(streamId)
            processSonicOutput(streamId, outputBuffer, callback)
        } finally {
            sonicDestroyStream(streamId)
        }
    }

    private fun feedToSonic(streamId: Long, data: ShortArray, shortBuffer: ShortArray, bufferSize: Int, outputBuffer: ShortArray, callback: SynthesisCallback) {
        var inputOffset = 0
        while (inputOffset < data.size && !stopRequested.get()) {
            val inputLen = min(bufferSize, data.size - inputOffset)
            System.arraycopy(data, inputOffset, shortBuffer, 0, inputLen)
            sonicWriteShortToStream(streamId, shortBuffer, inputLen)
            processSonicOutput(streamId, outputBuffer, callback)
            inputOffset += inputLen
        }
    }

    private fun processSonicOutput(streamId: Long, outputBuffer: ShortArray, callback: SynthesisCallback) {
        while (sonicSamplesAvailable(streamId) > 0 && !stopRequested.get()) {
            val readCount = sonicReadShortFromStream(streamId, outputBuffer, outputBuffer.size)
            if (readCount > 0) {
                applyGain(outputBuffer, readCount, 1.8f)
                val byteData = shortsToBytes(outputBuffer, readCount)
                callback.audioAvailable(byteData, 0, byteData.size)
            }
        }
    }

    private fun shortsToBytes(shorts: ShortArray, readCount: Int): ByteArray {
        val bytes = ByteArray(readCount * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts, 0, readCount)
        return bytes
    }

    fun stopDirectAudio() {
        isDirectStopped.set(true)
        try {
            directAudioTrack?.pause()
            directAudioTrack?.flush()
            directAudioTrack?.stop()
            directAudioTrack?.release()
        } catch (_: Exception) {}
        directAudioTrack = null
    }

    fun playDirectAudio(context: Context, requestText: String, rate: Float, pitch: Float) {
        stopDirectAudio()
        isDirectStopped.set(false)
        initResources(context)
        val text = requestText
        if (text.isBlank()) return
        
        val minBufferSize = AudioTrack.getMinBufferSize(OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        directAudioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) 
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(OUTPUT_SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBufferSize * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_ACCESSIBILITY, OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2, AudioTrack.MODE_STREAM)
        }
        
        try {
            directAudioTrack?.play()
        } catch (_: Exception) {}
        synthesizeBurmeseDirect(text, rate.coerceIn(0.1f, 4.0f), pitch.coerceIn(0.5f, 2.0f))
        try {
            directAudioTrack?.stop()
            directAudioTrack?.release()
        } catch (_: Exception) {}
        directAudioTrack = null
    }

    private fun synthesizeBurmeseDirect(text: String, rate: Float, pitch: Float) {
        val currentMap = charMap ?: return
        val currentSingleMap = singleCharMap ?: emptyMap()
        val currentPhraseMap = phraseMap ?: emptyMap()
        val isSingleChar = text.length == 1 && currentSingleMap.containsKey(text)
        val units = if (isSingleChar) listOf(text) else splitTextIntoPlayableUnits(text, currentPhraseMap, currentMap)
        if (units.isEmpty()) return

        val streamId = sonicCreateStream(OUTPUT_SAMPLE_RATE, OUTPUT_CHANNEL_COUNT)
        sonicSetSpeed(streamId, rate)
        sonicSetPitch(streamId, pitch)

        val bufferSize = 4096
        val shortBuffer = ShortArray(bufferSize)
        val outputBuffer = ShortArray(bufferSize)
        var prevTail: ShortArray? = null

        try {
            for (unit in units) {
                if (isDirectStopped.get()) break
                var pauseDuration = 0
                when (unit) {
                    "[NEWLINE]" -> pauseDuration = 800
                    "[SPACE]" -> pauseDuration = 200
                }
                if (pauseDuration > 0) {
                    if (prevTail != null) {
                        applyFadeOut(prevTail, prevTail.size)
                        feedToSonicDirect(streamId, prevTail, shortBuffer, bufferSize, outputBuffer)
                        prevTail = null
                    }
                    writeSilenceToSonic(streamId, pauseDuration)
                    processSonicOutputDirect(streamId, outputBuffer)
                    continue
                }

                val baseName = if (isSingleChar) currentSingleMap[unit] else (currentPhraseMap[unit] ?: currentMap[unit])
                if (baseName == null) continue
                val encodedBytes = readAudioFromBin(baseName)

                if (encodedBytes != null && encodedBytes.isNotEmpty()) {
                    val originalPcm = decodeOpus(encodedBytes, encodedBytes.size)
                    if (originalPcm != null && originalPcm.isNotEmpty()) {
                        val pauseSamples = (OUTPUT_SAMPLE_RATE * 25) / 1000
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
                                    feedToSonicDirect(streamId, prevMain, shortBuffer, bufferSize, outputBuffer)
                                }
                                feedToSonicDirect(streamId, crossfaded, shortBuffer, bufferSize, outputBuffer)

                                val currentRemaining = pcmShorts.copyOfRange(crossfadeLen, pcmShorts.size)
                                if (currentRemaining.size > CROSSFADE_SAMPLES) {
                                    val mainPart = currentRemaining.copyOfRange(0, currentRemaining.size - CROSSFADE_SAMPLES)
                                    feedToSonicDirect(streamId, mainPart, shortBuffer, bufferSize, outputBuffer)
                                    prevTail = currentRemaining.copyOfRange(currentRemaining.size - CROSSFADE_SAMPLES, currentRemaining.size)
                                } else {
                                    prevTail = currentRemaining
                                }
                            } else {
                                feedToSonicDirect(streamId, prevTail, shortBuffer, bufferSize, outputBuffer)
                                if (pcmShorts.size > CROSSFADE_SAMPLES) {
                                    val mainPart = pcmShorts.copyOfRange(0, pcmShorts.size - CROSSFADE_SAMPLES)
                                    feedToSonicDirect(streamId, mainPart, shortBuffer, bufferSize, outputBuffer)
                                    prevTail = pcmShorts.copyOfRange(pcmShorts.size - CROSSFADE_SAMPLES, pcmShorts.size)
                                } else {
                                    prevTail = pcmShorts
                                }
                            }
                        } else {
                            applyFadeIn(pcmShorts, FADE_SAMPLES)
                            if (pcmShorts.size > CROSSFADE_SAMPLES) {
                                val mainPart = pcmShorts.copyOfRange(0, pcmShorts.size - CROSSFADE_SAMPLES)
                                feedToSonicDirect(streamId, mainPart, shortBuffer, bufferSize, outputBuffer)
                                prevTail = pcmShorts.copyOfRange(pcmShorts.size - CROSSFADE_SAMPLES, pcmShorts.size)
                            } else {
                                prevTail = pcmShorts
                            }
                        }
                    }
                }
            }
            if (prevTail != null && prevTail.isNotEmpty() && !isDirectStopped.get()) {
                applyFadeOut(prevTail, FADE_SAMPLES)
                feedToSonicDirect(streamId, prevTail, shortBuffer, bufferSize, outputBuffer)
            }
            sonicFlushStream(streamId)
            processSonicOutputDirect(streamId, outputBuffer)
        } finally {
            sonicDestroyStream(streamId)
        }
    }

    private fun feedToSonicDirect(streamId: Long, data: ShortArray, shortBuffer: ShortArray, bufferSize: Int, outputBuffer: ShortArray) {
        var inputOffset = 0
        while (inputOffset < data.size && !isDirectStopped.get()) {
            val inputLen = min(bufferSize, data.size - inputOffset)
            System.arraycopy(data, inputOffset, shortBuffer, 0, inputLen)
            sonicWriteShortToStream(streamId, shortBuffer, inputLen)
            processSonicOutputDirect(streamId, outputBuffer)
            inputOffset += inputLen
        }
    }

    private fun processSonicOutputDirect(streamId: Long, outputBuffer: ShortArray) {
        while (sonicSamplesAvailable(streamId) > 0 && !isDirectStopped.get()) {
            val readCount = sonicReadShortFromStream(streamId, outputBuffer, outputBuffer.size)
            if (readCount > 0) {
                applyGain(outputBuffer, readCount, 1.8f)
                try {
                    directAudioTrack?.write(outputBuffer, 0, readCount)
                } catch (_: Exception) {}
            }
        }
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
        val startIdx = audio.size - len
        for (i in 0 until len) {
            val t = i.toFloat() / len
            val gain = (0.5 * (1.0 + cos(PI * t))).toFloat()
            audio[startIdx + i] = (audio[startIdx + i] * gain).toInt().coerceIn(-32768, 32767).toShort()
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

    private fun splitTextIntoPlayableUnits(text: String, phraseMap: Map<String, String>, map: Map<String, String>): List<String> {
        val res = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var best = ""
            for (j in minOf(text.length, i + 50) downTo i + 1) {
                val sub = text.substring(i, j)
                if (phraseMap.containsKey(sub) || map.containsKey(sub)) {
                    best = sub
                    break
                }
            }
            if (best.isNotEmpty()) {
                res.add(best)
                i += best.length
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

    private fun applyGain(audio: ShortArray, length: Int, gain: Float) {
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

    private fun releaseWakeLocks() {
        try { if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release() } catch (e: Exception) {}
        try { if (screenWakeLock?.isHeld == true) screenWakeLock?.release() } catch (e: Exception) {}
    }

    override fun onGetVoices(): MutableList<Voice> = mutableListOf(Voice("mya-MMR", Locale.Builder().setLanguage("my").setRegion("MM").build(), Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, hashSetOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)))
    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String = "mya-MMR"
    override fun onIsValidVoiceName(voiceName: String): Int = if (voiceName == "mya-MMR") TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onGetFeaturesForLanguage(lang: String?, country: String?, variant: String?): MutableSet<String> = hashSetOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)

    override fun onDestroy() {
        isDestroyed.set(true)
        stopRequested.set(true)
        isDirectStopped.set(true)
        isKeepAliveRunning.set(false)
        
        try { keepAliveThread?.interrupt(); keepAliveThread?.join(1000) } catch (e: Exception) {}

        try {
            englishEngine?.stop()
            englishEngine?.shutdown()
        } catch (_: Exception) {}
        
        utteranceLatches.values.forEach { it.countDown() }
        utteranceLatches.clear()
        
        stopDirectAudio()
        releaseWakeLocks()

        if (isOpusInit) {
            destroyOpusDecoder()
            isOpusInit = false
        }
        try { randomAccessFile?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}

