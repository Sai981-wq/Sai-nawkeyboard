package com.shan.tts.manager

object TTSUtils {
    private val SHAN_MARKERS = Regex("[\\u1075-\\u108F\\u1090-\\u1099\\uAA60-\\uAA7F]")
    private val MYANMAR_BLOCK = Regex("[\\u1000-\\u109F]")
    private val ENG_TO_MM = Regex("([a-zA-Z0-9])([\\u1000-\\u109F\\uAA60-\\uAA7F])")
    private val MM_TO_ENG = Regex("([\\u1000-\\u109F\\uAA60-\\uAA7F])([a-zA-Z0-9])")

    fun splitHelper(text: String): List<LangChunk> {
        val list = ArrayList<LangChunk>()
        if (text.isBlank()) return list

        if (text.contains("<speak", ignoreCase = true)) {
            list.add(LangChunk(text, detectWordLanguage(text)))
            return list
        }

        var processedText = text.replace(ENG_TO_MM, "$1 $2")
        processedText = processedText.replace(MM_TO_ENG, "$1 $2")

        val parts = processedText.split(Regex("(?<=\\s)|(?=\\s)"))
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

