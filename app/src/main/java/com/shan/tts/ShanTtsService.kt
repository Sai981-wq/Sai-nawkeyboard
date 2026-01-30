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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.zip.ZipFile

class ShanTtsService : TextToSpeechService() {

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
        private const val OUTPUT_SAMPLE_RATE = 22050
        private const val OUTPUT_CHANNEL_COUNT = 1
        private const val OUTPUT_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val ZIP_NAME = "audio_pcm.zip"
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
    private var zipFile: ZipFile? = null
    private var localZipFile: File? = null

    override fun onCreate() {
        super.onCreate()
        prepareZipFile()
        loadCharMap()
    }

    private fun prepareZipFile() {
        try {
            localZipFile = File(cacheDir, ZIP_NAME)
            if (!localZipFile!!.exists()) {
                assets.open(ZIP_NAME).use { input ->
                    FileOutputStream(localZipFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            zipFile = ZipFile(localZipFile)
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

        try {
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

            if (charMap.isNullOrEmpty()) {
                generateTestAudio(callback)
            } else {
                synthesizeShanText(text, callback, finalRate, finalPitch)
            }

            if (!isStopped) safeCallbackDone(callback)
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

                var pauseDuration = 0
                when (unit) {
                    "[NEWLINE]" -> pauseDuration = 600
                    "။", ".", "?" -> pauseDuration = 500
                    "၊", ",", ";" -> pauseDuration = 200
                    "[SPACE]" -> pauseDuration = 100
                }

                if (pauseDuration > 0) {
                    writeSilenceToSonic(streamId, pauseDuration)
                    processSonicOutput(streamId, outputBuffer, callback)
                    continue
                }

                val baseName = currentMap[unit] ?: continue
                val audioData = loadAudioFromZip(baseName) ?: continue
                val (rawBytes, type) = audioData

                val pcmBytes = when (type) {
                    "mp3" -> decodeMp3BytesToPcm(rawBytes)
                    "wav" -> stripWavHeader(rawBytes)
                    else -> rawBytes
                }

                if (pcmBytes.isEmpty()) continue

                val rawShorts = bytesToShorts(pcmBytes)
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
                } else if (c == " ") {
                    res.add("[SPACE]")
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

    private fun loadAudioFromZip(baseName: String): Pair<ByteArray, String>? {
        val zf = zipFile ?: return null
        val extensions = listOf(".pcm", ".wav", ".mp3")
        for (ext in extensions) {
            val entry = zf.getEntry(baseName + ext) ?: zf.getEntry("audio_pcm/$baseName$ext") ?: zf.getEntry("audio/$baseName$ext")
            if (entry != null) {
                val type = if (ext == ".mp3") "mp3" else if (ext == ".wav") "wav" else "pcm"
                return zf.getInputStream(entry).use { it.readBytes() } to type
            }
        }
        return null
    }

    private fun decodeMp3BytesToPcm(mp3Bytes: ByteArray): ByteArray {
        val tempFile = File(cacheDir, "temp_dec.mp3")
        FileOutputStream(tempFile).use { it.write(mp3Bytes) }
        val out = ByteArrayOutputStream()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(tempFile.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return ByteArray(0)
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)!!)
            codec.configure(extractor.getTrackFormat(track), null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var eos = false
            while (!eos && !isStopped) {
                val inIdx = codec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    val size = extractor.readSampleData(buf!!, 0)
                    if (size < 0) codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else { codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10000)
                if (outIdx >= 0) {
                    val b = codec.getOutputBuffer(outIdx)
                    val chunk = ByteArray(info.size)
                    b?.get(chunk)
                    out.write(chunk)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }
            codec.stop(); codec.release()
        } catch (e: Exception) { } finally { extractor.release(); tempFile.delete() }
        return out.toByteArray()
    }

    private fun stripWavHeader(wavBytes: ByteArray): ByteArray {
        if (wavBytes.size < 44) return wavBytes
        val buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0) != 0x46464952) return wavBytes
        var offset = 12
        while (offset + 8 <= wavBytes.size) {
            val chunkId = buffer.getInt(offset)
            val chunkSize = buffer.getInt(offset + 4)
            if (chunkId == 0x61746164) {
                val data = ByteArray(chunkSize)
                System.arraycopy(wavBytes, offset + 8, data, 0, minOf(chunkSize, wavBytes.size - (offset + 8)))
                return data
            }
            offset += 8 + chunkSize
        }
        return wavBytes
    }

    private fun processSonicOutput(streamId: Long, outputBuffer: ShortArray, callback: SynthesisCallback) {
        while (sonicSamplesAvailable(streamId) > 0 && !isStopped) {
            val readCount = sonicReadShortFromStream(streamId, outputBuffer, outputBuffer.size)
            if (readCount > 0) {
                val byteData = shortsToBytes(outputBuffer, readCount)
                callback.audioAvailable(byteData, 0, byteData.size)
            }
        }
    }

    private fun writeSilenceToSonic(streamId: Long, durationMs: Int) {
        val numSamples = (OUTPUT_SAMPLE_RATE * durationMs) / 1000
        sonicWriteShortToStream(streamId, ShortArray(numSamples), numSamples)
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

    private fun generateSilentAudio(callback: SynthesisCallback) {
        val silence = ByteArray((OUTPUT_SAMPLE_RATE * 100 / 1000) * 2)
        callback.audioAvailable(silence, 0, silence.size)
    }

    private fun generateTestAudio(callback: SynthesisCallback) {
        val samples = OUTPUT_SAMPLE_RATE * 500 / 1000
        val audioData = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val s = (Math.sin(2.0 * Math.PI * 440.0 * i / OUTPUT_SAMPLE_RATE) * 32767).toInt()
            audioData[i * 2] = (s and 0xFF).toByte()
            audioData[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        callback.audioAvailable(audioData, 0, audioData.size)
    }

    private fun safeCallbackError(callback: SynthesisCallback) { try { callback.error() } catch (_: Exception) {} }
    private fun safeCallbackDone(callback: SynthesisCallback) { try { callback.done() } catch (_: Exception) {} }
    override fun onGetVoices(): MutableList<Voice> = mutableListOf(Voice("shn", Locale.Builder().setLanguage("shn").build(), Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, hashSetOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)))
    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String = "shn"
    override fun onIsValidVoiceName(voiceName: String): Int = if (voiceName == "shn") TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onGetFeaturesForLanguage(lang: String?, country: String?, variant: String?): MutableSet<String> = hashSetOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)

    override fun onDestroy() {
        isStopped = true
        try { zipFile?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}

