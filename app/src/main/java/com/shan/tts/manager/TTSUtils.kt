package com.shan.tts.manager

object TTSUtils {

    data class Chunk(val text: String, val lang: String)

    private const val MAX_LENGTH = 500

    fun splitHelper(text: String): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        if (text.isEmpty()) return chunks

        var currentSb = StringBuilder()
        var currentLang = getLang(text[0])

        for (i in text.indices) {
            val c = text[i]
            val lang = getLang(c)

            if (lang != currentLang) {
                addChunk(chunks, currentSb, currentLang)
                currentSb = StringBuilder()
                currentSb.append(c)
                currentLang = lang
            } else {
                currentSb.append(c)
                if (currentSb.length > MAX_LENGTH) {
                    if (isSentenceEnd(c) || c == ' ') {
                        addChunk(chunks, currentSb, currentLang)
                        currentSb = StringBuilder()
                    }
                } else if (isSentenceEnd(c)) {
                    addChunk(chunks, currentSb, currentLang)
                    currentSb = StringBuilder()
                }
            }
        }
        
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

    private fun isSentenceEnd(c: Char): Boolean {
        return c == '။' || c == '?' || c == '!' || c == '\n' || c == '.'
    }

    private fun getLang(c: Char): String {
        if (c in '0'..'9') return "ENGLISH"
        
        if ("%$+)(!:#.,;\"'-_=[]{}<>/?@^&*|\\~`".contains(c)) {
            return "ENGLISH"
        }

        val code = c.code

        if ((code in 0x1075..0x108F) || (code in 0x1090..0x109F) || (code in 0xAA60..0xAA7F)) {
            return "SHAN"
        }

        if ((code in 0x1000..0x109F) || (code in 0xA9E0..0xA9FF)) {
            return "MYANMAR"
        }

        return "ENGLISH"
    }
}

