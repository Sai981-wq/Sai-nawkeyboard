package com.shan.tts.manager

object LanguageUtils {
     private val SHAN_PATTERN = Regex("[ႉႄႇႈၽၶၺႃၸၼဢၵႁဵႅၢႆႂႊ]")
     private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
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

object AudioResampler {
    // ဝင်လာတဲ့ Rate ကို 24000Hz ဖြစ်အောင် ပြောင်းပေးမည့် Function
    fun resample(input: ByteArray, length: Int, inRate: Int, outRate: Int): ByteArray {
        if (inRate == outRate) return input.copyOfRange(0, length)
        try {
            val shortLen = length / 2
            val inputShorts = ShortArray(shortLen)
            for (i in 0 until shortLen) {
                val low = input[i * 2].toInt() and 0xff
                val high = input[i * 2 + 1].toInt() shl 8
                inputShorts[i] = (low or high).toShort()
            }
            
            val outputLen = ((shortLen.toLong() * outRate) / inRate).toInt()
            val outputShorts = ShortArray(outputLen)
            val step = (inRate.toLong() shl 16) / outRate
            var pos = 0L

            for (i in 0 until outputLen) {
                val idx = (pos shr 16).toInt()
                if (idx >= shortLen - 1) {
                    outputShorts[i] = inputShorts[shortLen - 1]
                } else {
                    val v1 = inputShorts[idx].toInt()
                    val v2 = inputShorts[idx + 1].toInt()
                    val frac = (pos and 0xFFFFL).toInt()
                    val res = v1 + ((v2 - v1) * frac shr 16)
                    outputShorts[i] = res.toShort()
                }
                pos += step
            }

            val outputBytes = ByteArray(outputLen * 2)
            for (i in 0 until outputLen) {
                val sVal = outputShorts[i].toInt()
                outputBytes[i * 2] = (sVal and 0xff).toByte()
                outputBytes[i * 2 + 1] = ((sVal shr 8) and 0xff).toByte()
            }
            return outputBytes
        } catch (e: Exception) { return ByteArray(0) }
    }
}

