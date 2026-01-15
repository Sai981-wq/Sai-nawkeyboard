package com.shan.tts

import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class ShanTtsService : TextToSpeechService() {

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
        private const val OUTPUT_SAMPLE_RATE = 22050
        private const val OUTPUT_CHANNEL_COUNT = 1
        private const val OUTPUT_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val PCM_AUDIO_FOLDER = "audio_pcm"
        private const val MP3_AUDIO_FOLDER = "audio"
    }

    private external fun sonicCreateStream(sampleRate: Int, numChannels: Int): Long
    private external fun sonicDestroyStream(streamId: Long)
    private external fun sonicSetSpeed(streamId: Long, speed: Float)
    private external fun sonicSetPitch(streamId: Long, pitch: Float)
    private external fun sonicWriteShortToStream(streamId: Long, audioData: ShortArray, len: Int): Int
    private external fun sonicReadShortFromStream(streamId: Long, audioData: ShortArray, len: Int): Int
    private external fun sonicFlushStream(streamId: Long)
    private external fun sonicSamplesAvailable(streamId: Long): Int

    private var charMap: Map<String, String>? = null
    private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        loadCharMap()
    }

    private fun loadCharMap() {
        try {
            val tempMap = mutableMapOf<String, String>()
            assets.open("mapping.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split("=", limit = 2)
                        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
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
        if (lang != null && lang.equals("shn", ignoreCase = true)) {
            return TextToSpeech.LANG_AVAILABLE
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        if (lang != null && lang.equals("shn", ignoreCase = true)) {
            return TextToSpeech.LANG_AVAILABLE
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("shn", "MMR", "")
    }

    override fun onStop() {
        isStopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString() ?: ""
        isStopped = false

        try {
            val startResult = callback.start(OUTPUT_SAMPLE_RATE, OUTPUT_ENCODING, OUTPUT_CHANNEL_COUNT)
            if (startResult != TextToSpeech.SUCCESS) {
                safeCallbackError(callback)
                return
            }

            if (text.isBlank()) {
                generateSilentAudio(callback)
                safeCallbackDone(callback)
                return
            }

            val systemRate = request.speechRate / 100.0f
            val systemPitch = request.pitch / 100.0f

            val prefs = getSharedPreferences("shan_tts_prefs", android.content.Context.MODE_PRIVATE)
            val userSpeedMulti = prefs.getFloat("pref_speed", 1.0f)
            val userPitchMulti = prefs.getFloat("pref_pitch", 1.0f)

            val finalRate = (systemRate * userSpeedMulti).coerceIn(0.5f, 4.0f)
            val finalPitch = (systemPitch * userPitchMulti).coerceIn(0.5f, 2.0f)

            if (charMap.isNullOrEmpty()) {
                generateTestAudio(callback)
            } else {
                synthesizeShanText(text, callback, finalRate, finalPitch)
            }

            if (!isStopped) {
                safeCallbackDone(callback)
            }

        } catch (e: Exception) {
            safeCallbackError(callback)
        }
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

        try {
            for (unit in units) {
                if (isStopped) break

                if (unit == "[NEWLINE]") {
                    writeSilenceToSonic(streamId, 400) 
                    processSonicOutput(streamId, outputBuffer, callback)
                    continue
                }
                
                if (unit == "[SPACE]") {
                    writeSilenceToSonic(streamId, 150)
                    processSonicOutput(streamId, outputBuffer, callback)
                    continue
                }

                val fileName = currentMap[unit] ?: continue
                val resolved = resolveAsset(fileName) ?: continue
                val (path, type) = resolved

                val rawBytes = when (type) {
                    "pcm", "wav" -> loadPcmFile(path)
                    "mp3" -> loadAndDecodeMp3(path)
                    else -> ByteArray(0)
                }

                if (rawBytes.isEmpty()) continue

                val rawShorts = bytesToShorts(rawBytes)
                
                var inputOffset = 0
                while (inputOffset < rawShorts.size && !isStopped) {
                    val inputLen = minOf(bufferSize, rawShorts.size - inputOffset)
                    System.arraycopy(rawShorts, inputOffset, shortBuffer, 0, inputLen)
                    
                    sonicWriteShortToStream(streamId, shortBuffer, inputLen)
                    processSonicOutput(streamId, outputBuffer, callback)
                    
                    inputOffset += inputLen
                }
            }
            
            sonicFlushStream(streamId)
            processSonicOutput(streamId, outputBuffer, callback)

        } finally {
            sonicDestroyStream(streamId)
        }
    }

    private fun processSonicOutput(streamId: Long, outputBuffer: ShortArray, callback: SynthesisCallback) {
        var available = sonicSamplesAvailable(streamId)
        while (available > 0 && !isStopped) {
            val readCount = sonicReadShortFromStream(streamId, outputBuffer, outputBuffer.size)
            if (readCount > 0) {
                val byteData = shortsToBytes(outputBuffer, readCount)
                callback.audioAvailable(byteData, 0, byteData.size)
            }
            available = sonicSamplesAvailable(streamId)
        }
    }

    private fun writeSilenceToSonic(streamId: Long, durationMs: Int) {
        val numSamples = (OUTPUT_SAMPLE_RATE * durationMs) / 1000
        val silenceBuffer = ShortArray(numSamples) 
        sonicWriteShortToStream(streamId, silenceBuffer, numSamples)
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun shortsToBytes(shorts: ShortArray, readCount: Int): ByteArray {
        val bytes = ByteArray(readCount * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts, 0, readCount)
        return bytes
    }

    private fun splitTextIntoPlayableUnits(text: String, map: Map<String, String>): List<String> {
        val res = mutableListOf<String>()
        var i = 0
        val len = text.length
        val maxKeyLength = 20

        while (i < len) {
            var best = ""
            for (j in minOf(len, i + maxKeyLength) downTo i + 1) {
                val s = text.substring(i, j)
                if (map.containsKey(s)) {
                    val fileName = map[s]
                    if (fileName != null && resolveAsset(fileName) != null) {
                        best = s
                        break
                    }
                }
            }

            if (best.isNotEmpty()) {
                res.add(best)
                i += best.length
            } else {
                val char = text.substring(i, i + 1)
                
                if (char == "\n") {
                    res.add("[NEWLINE]") 
                    i++
                } else if (char == " ") {
                    res.add("[SPACE]")   
                    i++
                } else if (char.matches("\\s+".toRegex())) {
                    i++
                } else {
                    res.add(char)
                    i++
                }
            }
        }
        return res
    }

    private fun generateSilentAudio(callback: SynthesisCallback) {
        try {
            val ms = 100
            val samples = OUTPUT_SAMPLE_RATE * ms / 1000
            val silence = ByteArray(samples * 2)
            callback.audioAvailable(silence, 0, silence.size)
        } catch (_: Exception) { }
    }

    private fun generateTestAudio(callback: SynthesisCallback) {
        try {
            val sampleRate = OUTPUT_SAMPLE_RATE
            val durationMs = 500
            val samples = sampleRate * durationMs / 1000
            val audioData = ByteArray(samples * 2)

            for (i in 0 until samples) {
                val sample = (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 32767).toInt()
                audioData[i * 2] = (sample and 0xFF).toByte()
                audioData[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }

            var offset = 0
            val chunkSize = 4096
            while (offset < audioData.size && !isStopped) {
                val bytesToWrite = minOf(chunkSize, audioData.size - offset)
                callback.audioAvailable(audioData, offset, bytesToWrite)
                offset += bytesToWrite
            }
        } catch (e: Exception) {
        }
    }

    private fun safeCallbackError(callback: SynthesisCallback) {
        try { callback.error() } catch (_: Exception) { }
    }

    private fun safeCallbackDone(callback: SynthesisCallback) {
        try { callback.done() } catch (_: Exception) { }
    }

    @Throws(IOException::class)
    private fun readString(stream: DataInputStream, length: Int): String {
        val bytes = ByteArray(length)
        stream.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    @Throws(IOException::class)
    private fun readIntLE(stream: DataInputStream): Int {
        val bytes = ByteArray(4)
        stream.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun loadPcmFile(path: String): ByteArray = try {
        assets.open(path).use { assetStream ->
            if (!path.endsWith(".wav")) {
                assetStream.readBytes()
            } else {
                val stream = DataInputStream(assetStream)
                val out = ByteArrayOutputStream()
                val riffId = readString(stream, 4)
                if (riffId != "RIFF") ByteArray(0) else {
                    readIntLE(stream)
                    val waveId = readString(stream, 4)
                    if (waveId != "WAVE") ByteArray(0) else {
                        var foundDataChunk = false
                        while (!foundDataChunk) {
                            val chunkId = readString(stream, 4)
                            val chunkSize = readIntLE(stream)
                            when (chunkId) {
                                "fmt " -> {
                                    val bytesToSkip = chunkSize.toLong()
                                    if (stream.skip(bytesToSkip) != bytesToSkip) break
                                }
                                "data" -> {
                                    val pcmData = ByteArray(chunkSize)
                                    stream.readFully(pcmData)
                                    out.write(pcmData)
                                    foundDataChunk = true
                                }
                                else -> {
                                    val bytesToSkip = chunkSize.toLong()
                                    if (stream.skip(bytesToSkip) != bytesToSkip) break
                                }
                            }
                        }
                        out.toByteArray()
                    }
                }
            }
        }
    } catch (_: Exception) {
        ByteArray(0)
    }

    private fun loadAndDecodeMp3(path: String): ByteArray = try {
        assets.openFd(path).use { decodeMp3ToPcm(it) }
    } catch (_: Exception) { ByteArray(0) }

    private fun decodeMp3ToPcm(fd: AssetFileDescriptor): ByteArray {
        val out = ByteArrayOutputStream()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return ByteArray(0)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var eosIn = false
            var eosOut = false

            while (!eosOut && !isStopped) {
                if (!eosIn) {
                    val inIndex = codec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        codec.getInputBuffer(inIndex)?.let { buf ->
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosIn = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10000)
                when {
                    outIndex >= 0 -> {
                        codec.getOutputBuffer(outIndex)?.let { b ->
                            val arr = ByteArray(info.size)
                            b.position(info.offset)
                            b.limit(info.offset + info.size)
                            b.get(arr)
                            out.write(arr)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOut = true
                    }
                }
            }

            codec.stop()
            codec.release()
        } catch (_: Exception) {
        } finally {
            extractor.release()
        }
        return out.toByteArray()
    }

    private fun resolveAsset(name: String): Pair<String, String>? {
        val cand = listOf(
            "$PCM_AUDIO_FOLDER/$name.pcm",
            "$PCM_AUDIO_FOLDER/$name.wav",
            "$MP3_AUDIO_FOLDER/$name.mp3"
        )
        for (p in cand) {
            try {
                assets.open(p).close()
                val type = when {
                    p.endsWith(".mp3") -> "mp3"
                    p.endsWith(".wav") -> "wav"
                    else -> "pcm"
                }
                return Pair(p, type)
            } catch (_: Exception) { }
        }
        return null
    }

    override fun onGetVoices(): MutableList<Voice> {
        return try {
            val shanLocale = Locale.Builder().setLanguage("shn").build()
            val features = HashSet<String>()
            features.add(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)
            val voices = mutableListOf<Voice>()
            voices.add(Voice("shn", shanLocale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, features))
            voices
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String {
        return "shn"
    }

    override fun onIsValidVoiceName(voiceName: String): Int {
        return if (voiceName == "shn") TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onGetFeaturesForLanguage(lang: String?, country: String?, variant: String?): MutableSet<String> {
        val features = HashSet<String>()
        features.add(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)
        return features
    }

    override fun onDestroy() {
        isStopped = true
        super.onDestroy()
    }
}

