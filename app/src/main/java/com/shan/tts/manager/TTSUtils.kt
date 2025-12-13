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
            val isLongBuffer = currentBuffer.length > 50 && PUNCTUATION.containsMatchIn(word)
            
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
    // ETI Compatible Manual Bit-Shifting Resampler
    fun resampleChunk(input: ByteArray, length: Int, inRate: Int, outRate: Int): ByteArray {
        if (inRate == outRate) return input.copyOfRange(0, length)

        try {
            val shortArrayLength = length / 2
            val inputShorts = ShortArray(shortArrayLength)
            
            // Byte to Short (Manual Little Endian)
            for (i in 0 until shortArrayLength) {
                val low = input[i * 2].toInt() and 0xff
                val high = input[i * 2 + 1].toInt() shl 8
                inputShorts[i] = (low or high).toShort()
            }

            val ratio = inRate.toDouble() / outRate.toDouble()
            val outputLength = (shortArrayLength / ratio).toInt()
            
            if (outputLength <= 0) return ByteArray(0)
            
            val outputShorts = ShortArray(outputLength)

            for (outIndex in 0 until outputLength) {
                val inIndexDouble = outIndex * ratio
                val inIndex = inIndexDouble.toInt()
                
                if (inIndex < shortArrayLength - 1) {
                    val frac = inIndexDouble - inIndex
                    val val1 = inputShorts[inIndex].toInt()
                    val val2 = inputShorts[inIndex + 1].toInt()
                    // Linear Interpolation
                    outputShorts[outIndex] = (val1 + frac * (val2 - val1)).toInt().toShort()
                } else {
                    outputShorts[outIndex] = inputShorts[shortArrayLength - 1]
                }
            }

            // Short to Byte
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

