package com.shan.tts.manager

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioProcessor(private val sampleRate: Int, private val channels: Int) {

    private var sonic: Sonic? = null
    private val TARGET_HZ = 24000 

    fun init() {
        sonic = Sonic(TARGET_HZ, channels)
        val resamplingRate = sampleRate.toFloat() / TARGET_HZ.toFloat()
        
        sonic?.rate = resamplingRate
        sonic?.speed = 1.0f
        sonic?.pitch = 1.0f
        sonic?.volume = 1.0f
        
        AppLogger.log("AudioProcessor (Java): Input=$sampleRate, Target=$TARGET_HZ, Rate=$resamplingRate")
    }

    fun process(inBuffer: ByteBuffer?, len: Int, outBuffer: ByteBuffer, maxOut: Int): Int {
        val s = sonic ?: return 0
        
        // ★ SAFE INPUT HANDLING ★
        if (len > 0 && inBuffer != null) {
            val bytesToRead = kotlin.math.min(len, inBuffer.remaining())
            if (bytesToRead > 0) {
                val bytes = ByteArray(bytesToRead)
                inBuffer.get(bytes)
                s.writeBytesToStream(bytes, bytesToRead)
            }
        }

        // Output Reading
        val availableBytes = s.samplesAvailable() * channels * 2
        if (availableBytes > 0) {
            val readLen = kotlin.math.min(availableBytes, maxOut)
            val outBytes = ByteArray(readLen)
            
            val actualRead = s.readBytesFromStream(outBytes, readLen)
            
            if (actualRead > 0) {
                outBuffer.clear()
                outBuffer.put(outBytes, 0, actualRead)
                return actualRead
            }
        }
        return 0
    }

    fun flushQueue() {
        sonic?.flushStream()
    }

    fun release() {
        sonic = null
    }

    fun setSpeed(speed: Float) {
        sonic?.speed = speed
    }

    fun setPitch(pitch: Float) {
        sonic?.pitch = pitch
    }
}

