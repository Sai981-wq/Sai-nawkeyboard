package com.shan.tts.manager

object LanguageUtils {

    // Shan Range
    private val SHAN_PATTERN = Regex("[\\u1075-\\u108D\\u1090-\\u1099]")
    // Myanmar Range
    private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
    // English Range
    private val ENGLISH_PATTERN = Regex("[a-zA-Z]")

    fun detectLanguage(text: CharSequence?): String {
        if (text.isNullOrBlank()) return "ENGLISH"
        val input = text.toString()

        if (SHAN_PATTERN.containsMatchIn(input)) return "SHAN"
        if (MYANMAR_PATTERN.containsMatchIn(input)) return "MYANMAR"
        if (ENGLISH_PATTERN.containsMatchIn(input)) return "ENGLISH"

        return "ENGLISH"
    }
}
