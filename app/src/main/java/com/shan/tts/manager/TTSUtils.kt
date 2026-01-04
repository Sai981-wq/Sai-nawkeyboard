package com.shan.tts.manager

object TTSUtils {
    private val SHAN_MARKERS = Regex("[\\u1075-\\u108F\\u1090-\\u1099\\uAA60-\\uAA7F]")
    private val MYANMAR_BLOCK = Regex("[\\u1000-\\u109F]")
    
    // English နှင့် မြန်မာ/ရှမ်း စာလုံးများ ကပ်နေလျှင် ခြားရန်
    private val ENG_TO_MM = Regex("([a-zA-Z0-9])([\\u1000-\\u109F\\uAA60-\\uAA7F])")
    private val MM_TO_ENG = Regex("([\\u1000-\\u109F\\uAA60-\\uAA7F])([a-zA-Z0-9])")

    fun splitHelper(text: String): List<LangChunk> {
        // AppLogger.log("TTSUtils: Split Request -> '${text.take(20)}...'")
        
        val list = ArrayList<LangChunk>()
        if (text.isBlank()) return list

        // 1. Spacing Fix (Log the change)
        var processedText = text.replace(ENG_TO_MM, "$1 $2")
        processedText = processedText.replace(MM_TO_ENG, "$1 $2")
        
        if (text != processedText) {
            AppLogger.log("TTSUtils: Spacing Fix Applied -> '$processedText'")
        }

        // 2. Split by whitespace
        val parts = processedText.split(Regex("(?<=\\s)|(?=\\s)"))
        var currentBuffer = StringBuilder()
        var currentLang = ""

        for (part in parts) {
            if (part.isBlank()) {
                currentBuffer.append(part)
                continue
            }

            val detected = detectWordLanguage(part)
            // AppLogger.log("TTSUtils: Word '$part' -> $detected")

            if (currentBuffer.isEmpty()) {
                currentLang = detected
                currentBuffer.append(part)
                continue
            }

            if (detected == currentLang) {
                currentBuffer.append(part)
            } else {
                // Log when switching language
                AppLogger.log("TTSUtils: Switch $currentLang -> $detected")
                
                list.add(LangChunk(currentBuffer.toString(), currentLang))
                currentBuffer = StringBuilder(part)
                currentLang = detected
            }
        }

        if (currentBuffer.isNotEmpty()) {
            list.add(LangChunk(currentBuffer.toString(), currentLang))
        }

        AppLogger.log("TTSUtils: Result -> ${list.size} chunks")
        return list
    }

    private fun detectWordLanguage(word: String): String {
        if (SHAN_MARKERS.containsMatchIn(word)) return "SHAN"
        if (MYANMAR_BLOCK.containsMatchIn(word)) return "MYANMAR"
        return "ENGLISH"
    }
}

data class LangChunk(val text: String, val lang: String)

