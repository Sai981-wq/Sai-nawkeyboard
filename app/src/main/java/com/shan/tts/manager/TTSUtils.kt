package com.shan.tts.manager

import java.nio.ByteBuffer
import java.nio.ByteOrder

object TTSUtils {
    private val SHAN_MARKERS = Regex("[\\u1075-\\u108F\\u1090-\\u1099\\uAA60-\\uAA7F]")
    private val MYANMAR_BLOCK = Regex("[\\u1000-\\u109F]")

    fun splitHelper(text: String): List<LangChunk> {
        val list = ArrayList<LangChunk>()
        if (text.isBlank()) return list

        if (text.contains("<") && text.contains(">")) {
            list.add(LangChunk(text, detectWordLanguage(text)))
            return list
        }

        val parts = text.split(Regex("(?<=\\s)|(?=\\s)"))

        var currentBuffer = StringBuilder()
        var currentLang = ""

        for (part in parts) {
            if (part.isBlank()) {
                currentBuffer.append(part)
                continue
            }

            val detected = detectWordLanguage(part)

            if (currentBuffer.isEmpty()) {
                currentLang = detected
                currentBuffer.append(part)
                continue
            }

            if (detected == currentLang) {
                currentBuffer.append(part)
            } else {
                list.add(LangChunk(currentBuffer.toString(), currentLang))
                currentBuffer = StringBuilder(part)
                currentLang = detected
            }
        }

        if (currentBuffer.isNotEmpty()) {
            list.add(LangChunk(currentBuffer.toString(), currentLang))
        }
        return list
    }

    private fun detectWordLanguage(word: String): String {
        if (SHAN_MARKERS.containsMatchIn(word)) return "SHAN"
        if (MYANMAR_BLOCK.containsMatchIn(word)) return "MYANMAR"
        return "ENGLISH"
    }

    // ★ NEW: This is the missing function causing the error ★
    // Audio Resampler (Hz Converter) - Linear Interpolation
    fun resample(input: ByteArray, length: Int, inHz: Int, outHz: Int): ByteArray {
        if (inHz == outHz) return input.copyOfRange(0, length)
        
        // Convert Bytes to Shorts (16-bit PCM)
        val shortsCount = length / 2
        val shorts = ShortArray(shortsCount)
        ByteBuffer.wrap(input, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        // Calculate new size
        val ratio = inHz.toDouble() / outHz.toDouble()
        val outLength = (shortsCount / ratio).toInt()
        val outShorts = ShortArray(outLength)

        // Linear Interpolation
        for (i in 0 until outLength) {
            val srcIndex = i * ratio
            val index0 = srcIndex.toInt()
            val index1 = minOf(index0 + 1, shortsCount - 1)
            val fraction = srcIndex - index0

            val val0 = shorts[index0]
            val val1 = shorts[index1]
            
            // Calculate weighted average
            val newValue = (val0 + (val1 - val0) * fraction).toInt().toShort()
            outShorts[i] = newValue
        }

        // Convert back to Bytes
        val output = ByteArray(outShorts.size * 2)
        ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outShorts)
        return output
    }
}

data class LangChunk(val text: String, val lang: String)

