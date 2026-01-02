package com.shan.tts.manager

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Wrapper for Pure Java Sonic
class AudioProcessor(private val sampleRate: Int, private val channels: Int) {

    private var sonic: Sonic? = null
    // We target 24000Hz output for stability
    private val TARGET_HZ = 24000 

    fun init() {
        // Initialize Sonic with the TARGET rate
        sonic = Sonic(TARGET_HZ, channels)
        
        // Calculate Resampling Rate
        // If Input=16000, Output=24000 -> Rate = 0.666
        // Sonic will use Sinc Interpolation to smooth this out
        val resamplingRate = sampleRate.toFloat() / TARGET_HZ.toFloat()
        
        sonic?.rate = resamplingRate
        sonic?.speed = 1.0f
        sonic?.pitch = 1.0f
        sonic?.volume = 1.0f
        
        AppLogger.log("AudioProcessor (Java): Input=$sampleRate, Target=$TARGET_HZ, Rate=$resamplingRate")
    }

    fun process(inBuffer: ByteBuffer?, len: Int, outBuffer: ByteBuffer, maxOut: Int): Int {
        val s = sonic ?: return 0
        
        // 1. Write Input (Bytes)
        if (len > 0 && inBuffer != null) {
            val bytes = ByteArray(len)
            inBuffer.get(bytes)
            s.writeBytesToStream(bytes, len)
        }

        // 2. Read Output (Bytes)
        // Check available samples (convert to bytes: samples * channels * 2)
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

