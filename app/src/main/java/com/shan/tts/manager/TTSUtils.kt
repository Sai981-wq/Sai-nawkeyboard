package com.shan.tts.manager

object LanguageUtils {
     private val SHAN_PATTERN = Regex("[ႉႄႇႈၽၶၺႃၸၼဢၵႁဵႅၢႆႂႊ]")
     private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
     // စာအရှည်ကြီးမဖြစ်အောင် ပုဒ်ဖြတ်တွေမှာ ဖြတ်မယ်
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
            // Chunk Size ကြီးရင် Latency ဖြစ်လို့ ဖြတ်မယ်
            val isLongBuffer = currentBuffer.length > 80 && PUNCTUATION.containsMatchIn(word)
            
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
    /**
     * High-Precision Linear Interpolation Resampler
     * 16kHz -> 24kHz ပြောင်းရာတွင် အသံမကြောင်စေရန် အထူးပြုလုပ်ထားသည်။
     */
    fun resampleChunk(input: ByteArray, length: Int, inRate: Int, outRate: Int): ByteArray {
        if (inRate == outRate) return input.copyOfRange(0, length)

        val inputSampleCount = length / 2
        // Output Size တွက်ချက်ခြင်း
        val outputSampleCount = ((inputSampleCount.toLong() * outRate) / inRate).toInt()
        
        if (outputSampleCount <= 0) return ByteArray(0)

        val outputBytes = ByteArray(outputSampleCount * 2)
        
        // Step size (ရွေ့လျားနှုန်း)
        val step = inRate.toDouble() / outRate.toDouble()
        var phase = 0.0

        for (outIndex in 0 until outputSampleCount) {
            val index = phase.toInt()
            val frac = phase - index

            // Input Sample နှစ်ခုကို ယူမယ် (Current & Next)
            val idx1 = index * 2
            
            // Check Bounds
            if (idx1 + 1 >= length) break

            // Byte -> Short (Manual Little Endian Conversion)
            val s1Low = input[idx1].toInt() and 0xFF
            val s1High = input[idx1 + 1].toInt() shl 8
            val sample1 = (s1Low or s1High).toShort()

            var finalSample: Short

            // Next Sample ရှိရင် Interpolate လုပ်မယ်
            if (idx1 + 3 < length) {
                val s2Low = input[idx1 + 2].toInt() and 0xFF
                val s2High = input[idx1 + 3].toInt() shl 8
                val sample2 = (s2Low or s2High).toShort()

                // FORMULA: y = y1 + (y2 - y1) * fraction
                // ဒီ Formula က အသံလှိုင်းကို ချောမွေ့စေတယ်
                val interpolated = (sample1 + (sample2 - sample1) * frac).toInt()
                finalSample = interpolated.toShort()
            } else {
                finalSample = sample1
            }

            // Write back to Bytes
            val outIdx = outIndex * 2
            outputBytes[outIdx] = (finalSample.toInt() and 0xFF).toByte()
            outputBytes[outIdx + 1] = ((finalSample.toInt() shr 8) and 0xFF).toByte()

            // Move phase
            phase += step
        }

        return outputBytes
    }
}

