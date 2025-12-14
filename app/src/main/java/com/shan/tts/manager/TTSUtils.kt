package com.shan.tts.manager

import android.content.Context
import android.speech.tts.TextToSpeech
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

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

object TTSUtils {
    
    fun getFallbackRate(pkg: String): Int {
        val lower = pkg.lowercase(Locale.ROOT)
        if (lower.contains("google")) return 24000
        if (lower.contains("espeak") || lower.contains("shan") || lower.contains("myanmar")) return 22050
        if (lower.contains("vocalizer")) return 22050
        return 16000
    }

    fun detectEngineSampleRate(tts: TextToSpeech, context: Context, pkgName: String): Int {
        val tempFile = File(context.cacheDir, "probe_${pkgName.hashCode()}.wav")
        val params = android.os.Bundle()
        val uuid = "probe_${System.currentTimeMillis()}"
        
        try { if(tempFile.exists()) tempFile.delete() } catch(e: Exception){}

        val result = tts.synthesizeToFile("a", params, tempFile, uuid)
        
        if (result == TextToSpeech.SUCCESS) {
            var waitCount = 0
            while (!tempFile.exists() || tempFile.length() < 44) {
                try { Thread.sleep(50) } catch (e: Exception) {}
                waitCount++
                if (waitCount > 40) return getFallbackRate(pkgName)
            }
            return readWavSampleRate(tempFile)
        }
        return getFallbackRate(pkgName)
    }

    private fun readWavSampleRate(file: File): Int {
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(24)
                val byte1 = raf.read()
                val byte2 = raf.read()
                val byte3 = raf.read()
                val byte4 = raf.read()
                
                return (byte1 and 0xFF) or 
                       ((byte2 and 0xFF) shl 8) or 
                       ((byte3 and 0xFF) shl 16) or 
                       ((byte4 and 0xFF) shl 24)
            }
        } catch (e: Exception) {
            return 16000
        }
    }
}

