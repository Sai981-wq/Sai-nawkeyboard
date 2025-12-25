package com.shan.tts.manager

object TTSUtils {
    private val SHAN_PATTERN = Regex("[\\u1075-\\u108F\\u1090-\\u1099\\uAA60-\\uAA7F]+")
    private val MYANMAR_PATTERN = Regex("[\\u1000-\\u102A]+")

    fun splitHelper(text: String): List<LangChunk> {
        val list = ArrayList<LangChunk>()
        if (text.isBlank()) return list

        if (text.contains("<") && text.contains(">")) {
            list.add(LangChunk(text, detectLanguage(text)))
            return list
        }

        val rawParts = text.split(Regex("(?<=[\\s\\p{Punct}])|(?=[\\s\\p{Punct}])"))

        var currentBuffer = StringBuilder()
        var currentLang = ""

        for (part in rawParts) {
            if (part.isBlank()) {
                currentBuffer.append(part)
                continue
            }

            val detected = detectLanguage(part)

            if (currentBuffer.isEmpty()) {
                currentLang = detected
                currentBuffer.append(part)
                continue
            }

            if (detected == "NEUTRAL" || currentLang == "NEUTRAL" || detected == currentLang) {
                currentBuffer.append(part)
                if (currentLang == "NEUTRAL" && detected != "NEUTRAL") {
                    currentLang = detected
                }
            } else {
                list.add(LangChunk(currentBuffer.toString(), currentLang))
                currentBuffer = StringBuilder(part)
                currentLang = detected
            }
        }

        if (currentBuffer.isNotBlank()) {
            list.add(LangChunk(currentBuffer.toString(), currentLang))
        }
        return list
    }

    fun detectLanguage(text: CharSequence?): String {
        if (text.isNullOrBlank()) return "NEUTRAL"
        val input = text.toString()
        if (SHAN_PATTERN.containsMatchIn(input)) return "SHAN"
        if (MYANMAR_PATTERN.containsMatchIn(input)) return "MYANMAR"
        return "NEUTRAL" 
    }
}

data class LangChunk(val text: String, val lang: String)

