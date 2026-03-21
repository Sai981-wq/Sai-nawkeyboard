package com.shan.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

class ShanTtsService : TextToSpeechService() {

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
        private const val OUTPUT_SAMPLE_RATE = 16000
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
    private val indexMap = HashMap<String, Pair<Long, Int>>()
    private var randomAccessFile: RandomAccessFile? = null
    private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        copyAssetToFile(BIN_FILENAME)
        copyAssetToFile(INDEX_FILENAME)
        loadCharMap()
        loadIndexMap()
        
        val binFile = File(filesDir, BIN_FILENAME)
        if (binFile.exists()) {
            randomAccessFile = RandomAccessFile(binFile, "r")
        }

        initOpusDecoder(OUTPUT_SAMPLE_RATE)
    }

    private fun copyAssetToFile(filename: String) {
        val file = File(filesDir, filename)
        if (!file.exists() || file.length() == 0L) { 
            try {
                assets.open(filename).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadIndexMap() {
        val indexFile = File(filesDir, INDEX_FILENAME)
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

    private fun loadCharMap() {
        try {
            val tempMap = mutableMapOf<String, String>()
            assets.open("mapping.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
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
            charMap = tempMap
        } catch (e: Exception) {
            charMap = emptyMap()
        }
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (lang.equals("shn", ignoreCase = true)) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetLanguage(): Array<String> = arrayOf("shn", "MMR", "")

    override fun onStop() {
        isStopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString() ?: ""
        isStopped = false

        if (callback.start(OUTPUT_SAMPLE_RATE, OUTPUT_ENCODING, OUTPUT_CHANNEL_COUNT) != TextToSpeech.SUCCESS) return

        if (text.isBlank()) {
            generateSilentAudio(callback)
            safeCallbackDone(callback)
            return
        }

        val systemRate = request.speechRate / 100.0f
        val systemPitch = request.pitch / 100.0f
        val prefs = getSharedPreferences("shan_tts_prefs", MODE_PRIVATE)
        val finalRate = (systemRate * prefs.getFloat("pref_speed", 0.8f)).coerceIn(0.1f, 4.0f)
        val finalPitch = (systemPitch * prefs.getFloat("pref_pitch", 1.0f)).coerceIn(0.5f, 2.0f)

        synthesizeShanText(text, callback, finalRate, finalPitch)
        
        if (!isStopped) safeCallbackDone(callback)
    }

    private fun synthesizeShanText(text: String, callback: SynthesisCallback, rate: Float, pitch: Float) {
        val currentMap = charMap ?: return
        val units = splitTextIntoPlayableUnits(text, currentMap)
        
        if (units.isEmpty()) {
            generateSilentAudio(callback)
            return
        }

        val streamId = sonicCreateStream(OUTPUT_SAMPLE_RATE, OUTPUT_CHANNEL_COUNT)
        sonicSetSpeed(streamId, rate)
        sonicSetPitch(streamId, pitch)

        val bufferSize = 4096
        val shortBuffer = ShortArray(bufferSize)
        val outputBuffer = ShortArray(bufferSize)

        var prevTail: ShortArray? = null

        try {
            for (unit in units) {
                if (isStopped) break

                var pauseDuration = 0
                when (unit) {
                    "[NEWLINE]" -> pauseDuration = 800
                    "။", "." -> pauseDuration = 600
                    "?" -> pauseDuration = 600
                    "၊", ",", ";" -> pauseDuration = 300
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

                val baseName = currentMap[unit] ?: continue
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
                                    val mixed = (prevTail[prevTail.size - crossfadeLen + i] * fadeOut +
                                                 pcmShorts[i] * fadeIn)
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

            if (prevTail != null && prevTail.isNotEmpty()) {
                applyFadeOut(prevTail, FADE_SAMPLES)
                feedToSonic(streamId, prevTail, shortBuffer, bufferSize, outputBuffer, callback)
            }

            sonicFlushStream(streamId)
            processSonicOutput(streamId, outputBuffer, callback)
        } finally {
            sonicDestroyStream(streamId)
        }
    }

    private fun feedToSonic(
        streamId: Long, data: ShortArray,
        shortBuffer: ShortArray, bufferSize: Int,
        outputBuffer: ShortArray, callback: SynthesisCallback
    ) {
        var inputOffset = 0
        while (inputOffset < data.size && !isStopped) {
            val inputLen = min(bufferSize, data.size - inputOffset)
            System.arraycopy(data, inputOffset, shortBuffer, 0, inputLen)
            sonicWriteShortToStream(streamId, shortBuffer, inputLen)
            processSonicOutput(streamId, outputBuffer, callback)
            inputOffset += inputLen
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

    private fun splitTextIntoPlayableUnits(text: String, map: Map<String, String>): List<String> {
        val res = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var best = ""
            for (j in minOf(text.length, i + 20) downTo i + 1) {
                if (map.containsKey(text.substring(i, j))) { best = text.substring(i, j); break }
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
                } else if (c == "၊" || c == "," || c == "။" || c == "." || c == "?" || c == ";") {
                    res.add(c)
                } else if (!c.matches("\\s+".toRegex())) {
                    res.add(c)
                }
                i++
            }
        }
        return res
    }
    
    private fun processSonicOutput(streamId: Long, outputBuffer: ShortArray, callback: SynthesisCallback) {
        while (sonicSamplesAvailable(streamId) > 0 && !isStopped) {
            val readCount = sonicReadShortFromStream(streamId, outputBuffer, outputBuffer.size)
            if (readCount > 0) {
                applyGain(outputBuffer, readCount, 1.0f)
                val byteData = shortsToBytes(outputBuffer, readCount)
                callback.audioAvailable(byteData, 0, byteData.size)
            }
        }
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

    private fun shortsToBytes(shorts: ShortArray, readCount: Int): ByteArray {
        val bytes = ByteArray(readCount * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts, 0, readCount)
        return bytes
    }

    private fun generateSilentAudio(callback: SynthesisCallback) {
        val silence = ByteArray((OUTPUT_SAMPLE_RATE * 100 / 1000) * 2)
        callback.audioAvailable(silence, 0, silence.size)
    }

    private fun safeCallbackDone(callback: SynthesisCallback) { try { callback.done() } catch (_: Exception) {} }
    
    override fun onGetVoices(): MutableList<Voice> = mutableListOf(Voice("shn", Locale.Builder().setLanguage("shn").build(), Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, hashSetOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)))
    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String = "shn"
    override fun onIsValidVoiceName(voiceName: String): Int = if (voiceName == "shn") TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onGetFeaturesForLanguage(lang: String?, country: String?, variant: String?): MutableSet<String> = hashSetOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)

    override fun onDestroy() {
        isStopped = true
        destroyOpusDecoder()
        try { randomAccessFile?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}

