package com.shan.tts.manager

object TTSUtils {
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
        
        val rawParts = text.split(Regex("(?<=[\\s\\p{Punct}])|(?=[\\s\\p{Punct}])"))
        var currentBuffer = StringBuilder()
        var currentLang = ""
        
        for (part in rawParts) {
            if (part.isEmpty()) continue
            
            var detected = detectLanguage(part)
            
            if (detected == "ENGLISH" && !part.any { it.isLetter() }) {
                detected = if (currentLang.isNotEmpty()) currentLang else "ENGLISH"
            }

            if (currentLang.isEmpty()) {
                currentLang = detected
                currentBuffer.append(part)
            } else if (currentLang == detected) {
                currentBuffer.append(part)
            } else {
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

data class LangChunk(val text: String, val lang: String)

