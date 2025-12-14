package com.shan.tts.manager

object LanguageUtils {
     private val SHAN_PATTERN = Regex("[ႉႄႇႈၽၶၺႃၸၼဢၵႁဵႅၢႆႂႊ]")
     private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
     // Pipe Blocking မဖြစ်အောင် Chunk Size ကို 400 ထားသည်
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
    // *** SIMPLE INTEGER-BASED RESAMPLER (STABLE) ***
    // CPU သက်သာပြီး အသံကြောင်ခြင်းကင်းသော နည်းလမ်း
    fun simpleResample(input: ByteArray, length: Int, inRate: Int, outRate: Int): ByteArray {
        if (inRate == outRate) return input.copyOfRange(0, length)

        try {
            val shortArrayLength = length / 2
            val inputShorts = ShortArray(shortArrayLength)
            
            // 1. Byte to Short (Little Endian)
            for (i in 0 until shortArrayLength) {
                val low = input[i * 2].toInt() and 0xff
                val high = input[i * 2 + 1].toInt() shl 8
                inputShorts[i] = (low or high).toShort()
            }

            // 2. Calculation
            val outputLength = ((shortArrayLength.toLong() * outRate) / inRate).toInt()
            val outputShorts = ShortArray(outputLength)
            
            // Fixed-point math for speed and stability
            val step = (inRate.toLong() shl 16) / outRate
            var pos = 0L

            for (i in 0 until outputLength) {
                val index = (pos shr 16).toInt()
                
                if (index >= shortArrayLength - 1) {
                    outputShorts[i] = inputShorts[shortArrayLength - 1]
                } else {
                    // Linear Interpolation (Integer Math)
                    val val1 = inputShorts[index].toInt()
                    val val2 = inputShorts[index + 1].toInt()
                    val fraction = (pos and 0xFFFFL).toInt()
                    
                    val value = val1 + ((val2 - val1) * fraction shr 16)
                    outputShorts[i] = value.toShort()
                }
                pos += step
            }

            // 3. Short to Byte
            val outputBytes = ByteArray(outputLength * 2)
            for (i in 0 until outputLength) {
                val sVal = outputShorts[i].toInt()
                outputBytes[i * 2] = (sVal and 0xff).toByte()
                outputBytes[i * 2 + 1] = ((sVal shr 8) and 0xff).toByte()
            }

            return outputBytes
        } catch (e: Exception) {
            return ByteArray(0)
        }
    }
}

