package com.shan.tts

import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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
        private const val ZIP_FILE_NAME = "Audio_pcm.zip"
        private const val PCM_FOLDER = "audio_pcm"
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

    override fun onCreate() {
        super.onCreate()
        prepareZipFile()
        loadCharMap()
    }

    private fun prepareZipFile() {
        try {
            val destFile = File(filesDir, ZIP_FILE_NAME)
            if (!destFile.exists()) {
                assets.open(ZIP_FILE_NAME).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            zipFile = ZipFile(destFile)
        } catch (e: Exception) {
            zipFile = null
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
                        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                            tempMap[parts[0].trim()] = parts[1].trim()
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
        return if (lang != null && lang.equals("shn", ignoreCase = true)) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return if (lang != null && lang.equals("shn", ignoreCase = true)) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
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
            if (callback.start(OUTPUT_SAMPLE_RATE, OUTPUT_ENCODING, OUTPUT_CHANNEL_COUNT) != TextToSpeech.SUCCESS) return
            if (text.isBlank()) {
                generateSilentAudio(callback)
                callback.done()
                return
            }
            val prefs = getSharedPreferences("shan_tts_prefs", android.content.Context.MODE_PRIVATE)
            val finalRate = ((request.speechRate / 100.0f) * prefs.getFloat("pref_speed", 1.0f)).coerceIn(0.5f, 4.0f)
            val finalPitch = ((request.pitch / 100.0f) * prefs.getFloat("pref_pitch", 1.0f)).coerceIn(0.5f, 2.0f)
            synthesizeShanText(text, callback, finalRate, finalPitch)
            if (!isStopped) callback.done()
        } catch (e: Exception) {
            callback.error()
        }
    }

    private fun synthesizeShanText(text: String, callback: SynthesisCallback, rate: Float, pitch: Float) {
        val currentMap = charMap ?: return
        val units = splitTextIntoPlayableUnits(text, currentMap)
        val streamId = sonicCreateStream(OUTPUT_SAMPLE_RATE, OUTPUT_CHANNEL_COUNT)
        sonicSetSpeed(streamId, rate)
        sonicSetPitch(streamId, pitch)
        val buffer = ShortArray(4096)
        try {
            for (unit in units) {
                if (isStopped) break
                when (unit) {
                    "[NEWLINE]" -> writeSilenceToSonic(streamId, 400)
                    "[SPACE]" -> writeSilenceToSonic(streamId, 150)
                    else -> {
                        val fileName = currentMap[unit] ?: continue
                        val audioPath = resolveAudioPathInZip(fileName) ?: continue
                        val rawBytes = loadBytesFromZip(audioPath)
                        if (rawBytes.isEmpty()) continue
                        val rawShorts = bytesToShorts(rawBytes)
                        var offset = 0
                        while (offset < rawShorts.size && !isStopped) {
                            val len = minOf(buffer.size, rawShorts.size - offset)
                            System.arraycopy(rawShorts, offset, buffer, 0, len)
                            sonicWriteShortToStream(streamId, buffer, len)
                            processSonicOutput(streamId, buffer, callback)
                            offset += len
                        }
                    }
                }
                processSonicOutput(streamId, buffer, callback)
            }
            sonicFlushStream(streamId)
            processSonicOutput(streamId, buffer, callback)
        } finally {
            sonicDestroyStream(streamId)
        }
    }

    private fun processSonicOutput(streamId: Long, buffer: ShortArray, callback: SynthesisCallback) {
        while (sonicSamplesAvailable(streamId) > 0 && !isStopped) {
            val read = sonicReadShortFromStream(streamId, buffer, buffer.size)
            if (read > 0) {
                val bytes = shortsToBytes(buffer, read)
                callback.audioAvailable(bytes, 0, bytes.size)
            }
        }
    }

    private fun writeSilenceToSonic(streamId: Long, ms: Int) {
        val samples = (OUTPUT_SAMPLE_RATE * ms) / 1000
        sonicWriteShortToStream(streamId, ShortArray(samples), samples)
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun shortsToBytes(shorts: ShortArray, read: Int): ByteArray {
        val bytes = ByteArray(read * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts, 0, read)
        return bytes
    }

    private fun splitTextIntoPlayableUnits(text: String, map: Map<String, String>): List<String> {
        val res = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var best = ""
            for (j in minOf(text.length, i + 20) downTo i + 1) {
                val s = text.substring(i, j)
                if (map.containsKey(s)) {
                    best = s
                    break
                }
            }
            if (best.isNotEmpty()) {
                res.add(best)
                i += best.length
            } else {
                val c = text[i].toString()
                if (c == "\n") res.add("[NEWLINE]") else if (c == " ") res.add("[SPACE]") else if (!c.trim().isEmpty()) res.add(c)
                i++
            }
        }
        return res
    }

    private fun generateSilentAudio(callback: SynthesisCallback) {
        val bytes = ByteArray(OUTPUT_SAMPLE_RATE * 100 / 1000 * 2)
        callback.audioAvailable(bytes, 0, bytes.size)
    }

    private fun resolveAudioPathInZip(name: String): String? {
        val currentZip = zipFile ?: return null
        val pathPcm = "$PCM_FOLDER/$name.pcm"
        val pathWav = "$PCM_FOLDER/$name.wav"
        return when {
            currentZip.getEntry(pathPcm) != null -> pathPcm
            currentZip.getEntry(pathWav) != null -> pathWav
            else -> null
        }
    }

    private fun loadBytesFromZip(path: String): ByteArray = try {
        zipFile?.let { zf ->
            zf.getInputStream(zf.getEntry(path)).use { it.readBytes() }
        } ?: ByteArray(0)
    } catch (_: Exception) { ByteArray(0) }

    override fun onGetVoices(): MutableList<Voice> {
        return mutableListOf(Voice("shn", Locale.Builder().setLanguage("shn").build(), Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, hashSetOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)))
    }

    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String) = "shn"
    override fun onIsValidVoiceName(voiceName: String) = if (voiceName == "shn") TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onGetFeaturesForLanguage(l: String?, c: String?, v: String?) = hashSetOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)
    override fun onDestroy() { 
        isStopped = true
        try { zipFile?.close() } catch (_: Exception) {}
        super.onDestroy() 
    }
}

