package com.shan.tts.manager

object TTSUtils {

    data class Chunk(val text: String, val lang: String)

    // စာလုံးရေ ၂၀၀ ကျော်ရင် အတင်းဖြတ်မယ် (Latency လျှော့ချရန်)
    private const val MAX_LENGTH = 200

    fun splitHelper(text: String): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        if (text.isEmpty()) return chunks

        var currentSb = StringBuilder()
        var currentLang = getLang(text[0])

        for (i in text.indices) {
            val c = text[i]
            val lang = getLang(c)

            // ၁။ ဘာသာစကား မတူရင် ဖြတ်မယ်
            if (lang != currentLang) {
                addChunk(chunks, currentSb, currentLang)
                currentSb = StringBuilder()
                currentSb.append(c)
                currentLang = lang
            } 
            // ၂။ ဘာသာစကား တူပေမယ့်...
            else {
                currentSb.append(c)
                
                // စာကြောင်းရှည်နေပြီလား စစ်မယ်
                if (currentSb.length > MAX_LENGTH) {
                    // ပုဒ်မ သို့မဟုတ် Space ဖြစ်နေရင် ဒီနေရာမှာပဲ ဖြတ်လိုက်မယ်
                    if (isSentenceEnd(c) || c == ' ') {
                        addChunk(chunks, currentSb, currentLang)
                        currentSb = StringBuilder()
                    }
                }
                // ပုဒ်မ (။) တွေ့ရင်လည်း ဖြတ်မယ် (ဒါမှ အသံတိုတိုနဲ့ မြန်မြန်ထွက်မယ်)
                else if (isSentenceEnd(c)) {
                    addChunk(chunks, currentSb, currentLang)
                    currentSb = StringBuilder()
                }
            }
        }
        
        // ကျန်တာတွေ သိမ်းမယ်
        if (currentSb.isNotEmpty()) {
            addChunk(chunks, currentSb, currentLang)
        }
        return chunks
    }

    private fun addChunk(chunks: ArrayList<Chunk>, sb: StringBuilder, lang: String) {
        val str = sb.toString().trim()
        if (str.isNotEmpty()) {
            chunks.add(Chunk(str, lang))
        }
    }

    // ပုဒ်မ ဟုတ်မဟုတ် စစ်ဆေးခြင်း
    private fun isSentenceEnd(c: Char): Boolean {
        return c == '။' || c == '.' || c == '?' || c == '!' || c == '\n'
    }

    private fun getLang(c: Char): String {
        if (c in '0'..'9') return "ENGLISH"
        if ("%$+)(!:#.,;\"'-_=[]{}<>/?@^&*|\\~`".contains(c)) return "ENGLISH"
        
        val code = c.code
        if ((code in 0x1000..0x109F) || (code in 0xAA60..0xAA7F) || (code in 0xA9E0..0xA9FF)) {
            return "MYANMAR" 
        }
        return "ENGLISH"
    }
}

