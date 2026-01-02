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
        
        AppLogger.log("Processor Init: In=$sampleRate, Out=$TARGET_HZ, Ratio=$resamplingRate")
    }

    fun process(inBuffer: ByteBuffer?, len: Int, outBuffer: ByteBuffer, maxOut: Int): Int {
        val s = sonic ?: return 0
        val t1 = System.nanoTime()
        
        // 1. Write Input
        if (len > 0 && inBuffer != null) {
            val remaining = inBuffer.remaining()
            val toRead = kotlin.math.min(len, remaining)
            
            if (toRead > 0) {
                val bytes = ByteArray(toRead)
                inBuffer.get(bytes)
                s.writeBytesToStream(bytes, toRead)
            } else {
                AppLogger.log("WARN: Buffer empty but len=$len")
            }
        }

        // 2. Read Output
        var actualRead = 0
        val availableBytes = s.samplesAvailable() * channels * 2
        
        if (availableBytes > 0) {
            val readLen = kotlin.math.min(availableBytes, maxOut)
            val outBytes = ByteArray(readLen)
            actualRead = s.readBytesFromStream(outBytes, readLen)
            
            if (actualRead > 0) {
                outBuffer.clear()
                outBuffer.put(outBytes, 0, actualRead)
                outBuffer.flip() // Critical Flip
            }
        }
        
        val t2 = System.nanoTime()
        // Log if processing takes longer than 5ms (Usually cause of stutter)
        val duration = (t2 - t1) / 1000000.0
        if (duration > 5.0) {
            AppLogger.log("SLOW PROCESS: ${duration}ms | In=$len, Out=$actualRead")
        }

        return actualRead
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

