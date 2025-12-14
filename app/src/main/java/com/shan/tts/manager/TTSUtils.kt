package com.shan.tts.manager

object LanguageUtils {
     // ရှမ်းစာလုံးများကို စစ်ဆေးရန် Pattern
     private val SHAN_PATTERN = Regex("[ႉႄႇႈၽၶၺႃၸၼဢၵႁဵႅၢႆႂႊ]")
     // မြန်မာစာလုံးများကို စစ်ဆေးရန် Pattern
     private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
     // စာကြောင်းရှည်ဖြတ်ရန် ပုဒ်ဖြတ်များ (Chunk Size 400)
     private val PUNCTUATION = Regex("[။،,!?\\n]") 

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
        
        // စကားလုံးအလိုက် ခွဲထုတ်ခြင်း
        val words = text.split(Regex("(?<=\\s)")) 
        var currentBuffer = StringBuilder()
        var currentLang = ""
        
        for (word in words) {
            val trimmed = word.trim()
            if (trimmed.isEmpty()) {
                currentBuffer.append(word)
                continue
            }
            val detected = detectLanguage(trimmed)
            val langChanged = currentLang.isNotEmpty() && currentLang != detected
            // စာလုံးရေ 400 ကျော်လျှင် ပုဒ်ဖြတ်နေရာတွင် ဖြတ်မည်
            val isLongBuffer = currentBuffer.length > 400 && PUNCTUATION.containsMatchIn(word)
            
            if (currentLang.isEmpty()) {
                currentLang = detected
                currentBuffer.append(word)
            } else if (!langChanged && !isLongBuffer) {
                currentBuffer.append(word)
            } else {
                list.add(LangChunk(currentBuffer.toString(), currentLang))
                currentBuffer = StringBuilder(word)
                currentLang = detected
            }
        }
        
        if (currentBuffer.isNotEmpty()) {
            val finalLang = if(currentLang.isEmpty()) "ENGLISH" else currentLang
            list.add(LangChunk(currentBuffer.toString(), finalLang))
        }
        return list
    }
}

