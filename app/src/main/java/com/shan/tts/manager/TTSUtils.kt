package com.shan.tts.manager

object LanguageUtils {
     private val SHAN_PATTERN = Regex("[ႉႄႇႈၽၶၺႃၸၼဢၵႁဵႅၢႆႂႊ]")
     private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
     
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
        
        val rawParts = text.split(Regex("(?<=[\\s\\p{Punct}])|(?=[\\s\\p{Punct}])"))
        var currentBuffer = StringBuilder()
        var currentLang = ""
        
        for (part in rawParts) {
            if (part.isEmpty()) continue
            
            var detected = detectLanguage(part)
            
            if (detected == "ENGLISH" && !part.any { it.isLetter() }) {
                detected = if (currentLang.isNotEmpty()) currentLang else "ENGLISH"
            }

            if (currentLang.isEmpty()) {
                currentLang = detected
                currentBuffer.append(part)
            } else if (currentLang == detected) {
                currentBuffer.append(part)
            } else {
                if (detected == "ENGLISH" && part.length < 3 && currentLang != "ENGLISH") {
                     currentBuffer.append(part)
                } else {
                    list.add(LangChunk(currentBuffer.toString(), currentLang))
                    currentBuffer = StringBuilder(part)
                    currentLang = detected
                }
            }
        }
        
        if (currentBuffer.isNotEmpty()) {
            val finalLang = if(currentLang.isEmpty()) "ENGLISH" else currentLang
            list.add(LangChunk(currentBuffer.toString(), finalLang))
        }
        return list
    }
}

object TTSUtils {
    fun resample(input: ByteArray, inputLength: Int, inRate: Int, outRate: Int): ByteArray {
        if (inRate == outRate) return input.copyOfRange(0, inputLength)
        
        val shortCount = inputLength / 2
        val inputShorts = ShortArray(shortCount)
        for (i in 0 until shortCount) {
            val b1 = input[i * 2].toInt() and 0xFF
            val b2 = input[i * 2 + 1].toInt() shl 8
            inputShorts[i] = (b1 or b2).toShort()
        }

        val outputLen = (shortCount.toLong() * outRate / inRate).toInt()
        val outputShorts = ShortArray(outputLen)
        val ratio = inRate.toDouble() / outRate.toDouble()
        
        for (i in 0 until outputLen) {
            val exactPos = i * ratio
            val index1 = exactPos.toInt()
            val index2 = index1 + 1
            val fraction = exactPos - index1

            if (index2 < shortCount) {
                val val1 = inputShorts[index1]
                val val2 = inputShorts[index2]
                val mixed = val1 + fraction * (val2 - val1)
                outputShorts[i] = mixed.toInt().toShort()
            } else if (index1 < shortCount) {
                outputShorts[i] = inputShorts[index1]
            }
        }

        val outputBytes = ByteArray(outputLen * 2)
        for (i in 0 until outputLen) {
            val s = outputShorts[i].toInt()
            outputBytes[i * 2] = (s and 0xFF).toByte()
            outputBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return outputBytes
    }
}

