package com.shan.tts.manager

object LanguageUtils {

    // ရှမ်းသီးသန့်စာလုံးများကို Regex ဖြင့် ပိုတိကျအောင် ပြင်ဆင်ထားပါသည်
    private val SHAN_PATTERN = Regex("[ႉႄႇႈၽၶၺႃၸၼဢၵႁဵႅၢႆႂႊ]")
    private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
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

