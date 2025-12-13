package com.shan.tts.manager

import java.nio.ByteBuffer
import java.nio.ByteOrder

// ================= LANGUAGE UTILS =================

object LanguageUtils {
     // Unicode Ranges for Shan and Myanmar
     private val SHAN_PATTERN = Regex("[ႉႄႇႈၽၶၺႃၸၼဢၵႁဵႅၢႆႂႊ]")
     private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
     
     fun detectLanguage(text: CharSequence?): String {
        if (text.isNullOrBlank()) return "ENGLISH"
        val input = text.toString()
        // Check Shan specific characters first
        if (SHAN_PATTERN.containsMatchIn(input)) return "SHAN"
        // Then check general Myanmar range
        if (MYANMAR_PATTERN.containsMatchIn(input)) return "MYANMAR"
        return "ENGLISH"
    }

    // *** OPTIMIZED SPLITTER ***
    // စာကြောင်းတွေကို ဘာသာစကားအလိုက် အစုလိုက် (Chunk) ခွဲထုတ်ပေးတဲ့ Function
    fun splitHelper(text: String): List<LangChunk> {
        val list = ArrayList<LangChunk>()
        if (text.isBlank()) return list

        // Split by whitespace but keep the delimiter to preserve spacing structure if needed
        // Regex lookbehind (?<=\s) splits after whitespace
        val words = text.split(Regex("(?<=\\s)")) 
        
        var currentBuffer = StringBuilder()
        var currentLang = ""
        
        for (word in words) {
            val trimmed = word.trim()
            if (trimmed.isEmpty()) {
                currentBuffer.append(word) // keep spaces/newlines
                continue
            }

            val detected = detectLanguage(trimmed)
            
            if (currentLang.isEmpty()) {
                currentLang = detected
                currentBuffer.append(word)
            } else if (currentLang == detected) {
                currentBuffer.append(word)
            } else {
                // Language Changed: Save old buffer
                list.add(LangChunk(currentBuffer.toString(), currentLang))
                // Start new buffer
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

// ================= AUDIO RESAMPLER =================

object AudioResampler {
    // TalkBack အတွက် Sample Rate မတူရင် ညှိပေးတဲ့ Function (Linear Interpolation)
    fun resampleChunk(input: ByteArray, inputLength: Int, inRate: Int, outRate: Int): ByteArray {
        if (inRate == outRate) return input.copyOfRange(0, inputLength)
        
        try {
            val shortBuffer = ByteBuffer.wrap(input, 0, inputLength).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val inputSamples = ShortArray(shortBuffer.remaining())
            shortBuffer.get(inputSamples)
            
            if (inputSamples.isEmpty()) return ByteArray(0)
            
            val ratio = inRate.toDouble() / outRate.toDouble()
            val outputLength = (inputSamples.size / ratio).toInt()
            
            if(outputLength <= 0) return ByteArray(0) 
            
            val outputSamples = ShortArray(outputLength)
            
             for (i in 0 until outputLength) {
                val position = i * ratio
                val index = position.toInt()
                val fraction = position - index
                
                if (index >= inputSamples.size - 1) {
                    outputSamples[i] = inputSamples[inputSamples.size - 1]
                } else {
                    val s1 = inputSamples[index]
                    val s2 = inputSamples[index + 1]
                    val value = s1 + (fraction * (s2 - s1)).toInt()
                    outputSamples[i] = value.toShort()
                }
            }
            
            val outputBytes = ByteArray(outputSamples.size * 2)
            ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outputSamples)
            return outputBytes
        } catch (e: Exception) { return ByteArray(0) }
    }
}
