package com.shan.tts.manager

import java.util.Locale

data class LangChunk(val text: String, val lang: String)

object LanguageUtils {
    
    fun splitHelper(text: String): List<LangChunk> {
        val chunks = mutableListOf<LangChunk>()
        var currentLang = ""
        val buffer = StringBuilder()

        for (char in text) {
            val lang = detectLang(char)
            
            if (lang != currentLang && buffer.isNotEmpty()) {
                chunks.add(LangChunk(buffer.toString(), currentLang))
                buffer.setLength(0)
            }
            
            currentLang = lang
            buffer.append(char)
        }
        
        if (buffer.isNotEmpty()) {
            chunks.add(LangChunk(buffer.toString(), currentLang))
        }
        
        return chunks
    }

    private fun detectLang(c: Char): String {
        val code = c.code
        return when {
            // Myanmar & Shan (1000-109F) + Extended A/B
            code in 0x1000..0x109F || code in 0xAA60..0xAA7F || code in 0xA9E0..0xA9FF -> "MYANMAR" // Will be refined by AutoTTSManager
            // Thai (0E00-0E7F)
            code in 0x0E00..0x0E7F -> "SHAN" // Treat Thai as Shan/Espeak
            // English/Latin
            else -> "ENGLISH"
        }
    }
}

