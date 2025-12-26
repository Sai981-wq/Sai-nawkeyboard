package com.shan.tts.manager

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
}

data class LangChunk(val text: String, val lang: String)

