package com.mettavoice.tts

import java.util.regex.Pattern

object TTSUtils {
    private val MYANMAR_PATTERN = Pattern.compile("[\\u1000-\\u109F]+")
    private val TOKEN_PATTERN = Pattern.compile("([\\u1000-\\u109F]+)|([^\\u1000-\\u109F\\s]+)|(\\s+)")

    data class Chunk(val text: String, val lang: String)

    fun splitText(text: String?): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        if (text.isNullOrBlank()) return chunks

        val matcher = TOKEN_PATTERN.matcher(text)
        val currentBuffer = java.lang.StringBuilder()
        var currentLang: String? = null

        while (matcher.find()) {
            val token = matcher.group()
            if (token.isEmpty()) continue

            if (matcher.group(3) != null) {
                if (currentBuffer.isNotEmpty()) currentBuffer.append(token)
                continue
            }

            val detectedLang = if (matcher.group(1) != null) "MYANMAR" else "ENGLISH"

            if (currentLang == null) {
                currentLang = detectedLang
                currentBuffer.append(token)
            } else if (currentLang == detectedLang) {
                currentBuffer.append(token)
            } else {
                chunks.add(Chunk(currentBuffer.toString(), currentLang))
                currentBuffer.clear()
                currentBuffer.append(token)
                currentLang = detectedLang
            }
        }
        if (currentBuffer.isNotEmpty() && currentLang != null) {
            chunks.add(Chunk(currentBuffer.toString(), currentLang))
        }
        return chunks
    }
}

