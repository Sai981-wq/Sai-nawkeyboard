package com.shan.tts.manager

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtils {

    fun isValidWav(header: ByteArray): Boolean {
        if (header.size < 44) return false
        val riff = String(header, 0, 4)
        val wave = String(header, 8, 4)
        return riff == "RIFF" && wave == "WAVE"
    }

    fun getSampleRate(header: ByteArray): Int {
        if (header.size < 44) return 24000
        return ByteBuffer.wrap(header, 24, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
    }
}