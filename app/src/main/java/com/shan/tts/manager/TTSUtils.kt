package com.shan.tts.manager

object TTSUtils {

    data class Chunk(val text: String, val lang: String)

    fun splitHelper(text: String): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        if (text.isEmpty()) return chunks

        var currentSb = StringBuilder()
        // ပထမ စာလုံးရဲ့ ဘာသာစကားကို စစ်မယ်
        var currentLang = getLang(text[0])

        for (i in text.indices) {
            val c = text[i]
            val lang = getLang(c)

            // ဘာသာစကား တူနေသမျှ စာကြောင်းဆက်မယ်
            if (lang == currentLang) {
                currentSb.append(c)
            } else {
                // Space (ကွက်လပ်) ဖြစ်နေရင် ချက်ချင်းမဖြတ်ဘဲ ရှေ့ကောင်နဲ့ တွဲပေးမယ်
                if (c == ' ' && currentSb.isNotEmpty()) {
                    currentSb.append(c)
                } else {
                    // ဘာသာစကား ကွဲသွားပြီ -> အဟောင်းကိုသိမ်း၊ အသစ်ပြန်စ
                    chunks.add(Chunk(currentSb.toString(), currentLang))
                    
                    currentSb = StringBuilder()
                    currentSb.append(c)
                    currentLang = lang
                }
            }
        }
        // ကျန်နေတဲ့ စာတွေကို သိမ်းမယ်
        if (currentSb.isNotEmpty()) {
            chunks.add(Chunk(currentSb.toString(), currentLang))
        }
        return chunks
    }

    private fun getLang(c: Char): String {
        // ၁။ ဂဏန်းများ (0-9) ကို English ဟု သတ်မှတ်မည်
        if (c in '0'..'9') return "ENGLISH"
        
        // ၂။ သင်္ကေတများကို English ဟု သတ်မှတ်မည်
        // % + $ : ) ( ! # . , ; " ' - ? / စသည်တို့ ပါဝင်သည်
        if ("%$+)(!:#.,;\"'-_=[]{}<>/?@^&*|\\~`".contains(c)) {
            return "ENGLISH"
        }

        // ၃။ မြန်မာစာလုံး range ဖြစ်မဖြစ် စစ်မည် (Shan ပါဝင်သည်)
        val code = c.code
        if ((code in 0x1000..0x109F) || (code in 0xAA60..0xAA7F) || (code in 0xA9E0..0xA9FF)) {
            return "MYANMAR" 
        }

        // ကျန်တာမှန်သမျှ (A-Z, a-z, etc.) ကို English လို့ သတ်မှတ်မည်
        return "ENGLISH"
    }
}

