package com.shan.tts.manager

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.ceil

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

    fun resample(input: ByteArray, length: Int, inHz: Int, outHz: Int): ByteArray {
        if (inHz == outHz || length == 0) return input.copyOfRange(0, length)

        val shortsCount = length / 2
        val inputShorts = ShortArray(shortsCount)
        ByteBuffer.wrap(input, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inputShorts)

        val ratio = inHz.toDouble() / outHz.toDouble()
        val outLength = (shortsCount / ratio).toInt()
        val outShorts = ShortArray(outLength)

        for (i in 0 until outLength) {
            val position = i * ratio
            val index0 = position.toInt()
            val index1 = if (index0 + 1 < shortsCount) index0 + 1 else index0
            val fraction = position - index0

            val val0 = inputShorts[index0]
            val val1 = inputShorts[index1]
            val newValue = (val0 + (val1 - val0) * fraction).toInt().toShort()
            outShorts[i] = newValue
        }

        val outputBytes = ByteArray(outShorts.size * 2)
        ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outShorts)
        return outputBytes
    }
}

data class LangChunk(val text: String, val lang: String)

