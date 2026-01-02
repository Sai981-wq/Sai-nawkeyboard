package com.shan.tts.manager

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

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

    // ★ NEW: Cubic Interpolation for Sharper Audio (Fixes Muffled Sound) ★
    fun resample(input: ByteArray, length: Int, inHz: Int, outHz: Int): ByteArray {
        if (inHz == outHz || length == 0) return input.copyOfRange(0, length)

        val shortsCount = length / 2
        val inputShorts = ShortArray(shortsCount)
        ByteBuffer.wrap(input, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inputShorts)

        val ratio = inHz.toDouble() / outHz.toDouble()
        val outLength = (shortsCount / ratio).toInt()
        val outShorts = ShortArray(outLength)

        // Cubic Interpolation Logic
        for (i in 0 until outLength) {
            val position = i * ratio
            val index1 = position.toInt()
            val index0 = if (index1 > 0) index1 - 1 else index1
            val index2 = if (index1 + 1 < shortsCount) index1 + 1 else index1
            val index3 = if (index1 + 2 < shortsCount) index1 + 2 else index2

            val y0 = inputShorts[index0].toDouble()
            val y1 = inputShorts[index1].toDouble()
            val y2 = inputShorts[index2].toDouble()
            val y3 = inputShorts[index3].toDouble()

            val mu = position - index1
            val mu2 = mu * mu
            val a0 = y3 - y2 - y0 + y1
            val a1 = y0 - y1 - a0
            val a2 = y2 - y0
            val a3 = y1

            var newValue = (a0 * mu * mu2 + a1 * mu2 + a2 * mu + a3).toInt()
            
            // Clamp value to 16-bit range
            if (newValue > 32767) newValue = 32767
            if (newValue < -32768) newValue = -32768
            
            outShorts[i] = newValue.toShort()
        }

        val outputBytes = ByteArray(outShorts.size * 2)
        ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outShorts)
        return outputBytes
    }
}

data class LangChunk(val text: String, val lang: String)

