package com.shan.tts.manager

object LanguageUtils {
     // Shan Unicode Range (Specifically targeting Shan characters)
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
        
        // Split by whitespace or punctuation but keep delimiters
        val rawParts = text.split(Regex("(?<=[\\s\\p{Punct}])|(?=[\\s\\p{Punct}])"))
        var currentBuffer = StringBuilder()
        var currentLang = ""
        
        for (part in rawParts) {
            if (part.isEmpty()) continue
            
            var detected = detectLanguage(part)
            
            // Treat numbers/punctuation as part of the previous language context
            if (detected == "ENGLISH" && !part.any { it.isLetter() }) {
                detected = if (currentLang.isNotEmpty()) currentLang else "ENGLISH"
            }

            if (currentLang.isEmpty()) {
                currentLang = detected
                currentBuffer.append(part)
            } else if (currentLang == detected) {
                currentBuffer.append(part)
            } else {
                // Merge very short English segments (like single letters) to prevent choppy audio
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

