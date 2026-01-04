package com.shan.tts.manager

import java.nio.ByteBuffer

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
        
        // Input logic
        if (len > 0 && inBuffer != null && inBuffer.hasRemaining()) {
            val bytes = ByteArray(inBuffer.remaining())
            inBuffer.get(bytes)
            s.writeBytesToStream(bytes, bytes.size)
        }

        // Output logic
        var actualRead = 0
        val availableBytes = s.samplesAvailable() * channels * 2
        
        if (availableBytes > 0) {
            val readLen = kotlin.math.min(availableBytes, maxOut)
            val outBytes = ByteArray(readLen)
            actualRead = s.readBytesFromStream(outBytes, readLen)
            
            if (actualRead > 0) {
                outBuffer.clear()
                outBuffer.put(outBytes, 0, actualRead)
                outBuffer.flip() 
            }
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
        sonic?.setSpeed(speed)
    }

    fun setPitch(pitch: Float) {
        sonic?.setPitch(pitch)
    }
}

