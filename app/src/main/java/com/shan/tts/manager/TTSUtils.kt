package com.shan.tts.manager

import android.content.Context
import android.speech.tts.TextToSpeech
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

object LanguageUtils {
     private val SHAN_PATTERN = Regex("[ႉႄႇႈၽၶၺႃၸၼဢၵႁဵႅၢႆႂႊ]")
     private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
     
     fun detectLanguage(text: CharSequence?): String {
        if (text.isNullOrBlank()) return "ENGLISH"
        val input = text.toString()
        if (SHAN_PATTERN.containsMatchIn(input)) return "SHAN"
        if (MYANMAR_PATTERN.containsMatchIn(input)) return "MYANMAR"
        return "ENGLISH"
    }

    fun splitHelper(text: String): List<LangChunk> {
        val list = ArrayList<LangChunk>()
        if (text.isBlank()) return list
        
        // Context-aware splitting to keep numbers/punctuations with correct language
        val rawParts = text.split(Regex("(?<=[\\s\\p{Punct}])|(?=[\\s\\p{Punct}])"))
        var currentBuffer = StringBuilder()
        var currentLang = ""
        
        for (part in rawParts) {
            if (part.isBlank()) {
                currentBuffer.append(part)
                continue
            }
            
            var detected = detectLanguage(part)
            
            // Numbers and Punctuation stick to the current language context
            if (detected == "ENGLISH" && !part.any { it.isLetter() }) {
                detected = if (currentLang.isNotEmpty()) currentLang else "ENGLISH"
            }

            if (currentLang.isEmpty()) {
                currentLang = detected
                currentBuffer.append(part)
            } else if (currentLang == detected) {
                currentBuffer.append(part)
            } else {
                // Prevent jitter for very short English words mixed in
                if (detected == "ENGLISH" && part.length < 3 && currentLang != "ENGLISH") {
                     currentBuffer.append(part)
                } else {
                    list.add(LangChunk(currentBuffer.toString(), currentLang))
                    currentBuffer = StringBuilder(part)
                    currentLang = detected
                }
            }
        }
        
        if (currentBuffer.isNotEmpty()) {
            val finalLang = if(currentLang.isEmpty()) "ENGLISH" else currentLang
            list.add(LangChunk(currentBuffer.toString(), finalLang))
        }
        return list
    }
}

object TTSUtils {
    
    fun getFallbackRate(pkg: String): Int {
        val lower = pkg.lowercase(Locale.ROOT)
        if (lower.contains("google")) return 24000
        if (lower.contains("espeak") || lower.contains("shan")) return 22050
        return 24000
    }

    fun detectEngineSampleRate(tts: TextToSpeech, context: Context, pkgName: String): Int {
        val tempFile = File(context.cacheDir, "probe_${pkgName.hashCode()}.wav")
        val params = android.os.Bundle()
        val uuid = "probe_${System.currentTimeMillis()}"
        
        try { if(tempFile.exists()) tempFile.delete() } catch(e: Exception){}

        val result = tts.synthesizeToFile("a", params, tempFile, uuid)
        
        if (result == TextToSpeech.SUCCESS) {
            var waitCount = 0
            while (!tempFile.exists() || tempFile.length() < 44) {
                try { Thread.sleep(20) } catch (e: Exception) {}
                waitCount++
                if (waitCount > 25) return getFallbackRate(pkgName)
            }
            return readWavSampleRate(tempFile)
        }
        return getFallbackRate(pkgName)
    }

    private fun readWavSampleRate(file: File): Int {
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(24)
                val byte1 = raf.read()
                val byte2 = raf.read()
                val byte3 = raf.read()
                val byte4 = raf.read()
                return (byte1 and 0xFF) or ((byte2 and 0xFF) shl 8) or 
                       ((byte3 and 0xFF) shl 16) or ((byte4 and 0xFF) shl 24)
            }
        } catch (e: Exception) { return 24000 }
    }

    // High Quality Linear Resampler (Safe for 16k -> 24k)
    fun resample(input: ByteArray, inputLength: Int, inRate: Int, outRate: Int): ByteArray {
        if (inRate == outRate) return input.copyOfRange(0, inputLength)
        
        // Convert Bytes to Shorts (16-bit audio)
        val shortCount = inputLength / 2
        val inputShorts = ShortArray(shortCount)
        for (i in 0 until shortCount) {
            val b1 = input[i * 2].toInt() and 0xFF
            val b2 = input[i * 2 + 1].toInt() shl 8
            inputShorts[i] = (b1 or b2).toShort()
        }

        // Calculate Output Size
        // Using Long to prevent overflow during calculation
        val outputLen = (shortCount.toLong() * outRate / inRate).toInt()
        val outputShorts = ShortArray(outputLen)
        
        // Linear Interpolation Loop
        val ratio = inRate.toDouble() / outRate.toDouble()
        
        for (i in 0 until outputLen) {
            val exactPos = i * ratio
            val index1 = exactPos.toInt()
            val index2 = index1 + 1
            val fraction = exactPos - index1

            if (index2 < shortCount) {
                // Smoothly blend between two samples
                val val1 = inputShorts[index1]
                val val2 = inputShorts[index2]
                val mixed = val1 + fraction * (val2 - val1)
                outputShorts[i] = mixed.toInt().toShort()
            } else if (index1 < shortCount) {
                // End of buffer case
                outputShorts[i] = inputShorts[index1]
            }
        }

        // Convert Shorts back to Bytes
        val outputBytes = ByteArray(outputLen * 2)
        for (i in 0 until outputLen) {
            val s = outputShorts[i].toInt()
            outputBytes[i * 2] = (s and 0xFF).toByte()
            outputBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return outputBytes
    }
}

