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
        AppLogger.log("PROC: Initialized. In=$sampleRate, Out=$TARGET_HZ, Ratio=$resamplingRate")
    }

    fun reset() {
        val currentRate = sonic?.getRate() ?: 1.0f
        sonic = Sonic(TARGET_HZ, channels).apply {
            this.setRate(currentRate)
            this.setSpeed(1.0f)
            this.setPitch(1.0f)
        }
        AppLogger.log("PROC: Sonic Reset performed for clean chunk transition.")
    }

    fun process(inBuffer: ByteBuffer?, len: Int, outBuffer: ByteBuffer, maxOut: Int): Int {
        val s = sonic ?: return 0
        
        if (len > 0 && inBuffer != null && inBuffer.hasRemaining()) {
            val bytes = ByteArray(inBuffer.remaining())
            inBuffer.get(bytes)
            s.writeBytesToStream(bytes, bytes.size)
        }

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
        AppLogger.log("PROC: Flushing stream...")
        sonic?.flushStream()
    }

    fun release() {
        AppLogger.log("PROC: Processor Released.")
        sonic = null
    }

    fun setSpeed(speed: Float) { sonic?.setSpeed(speed) }
    fun setPitch(pitch: Float) { sonic?.setPitch(pitch) }
}

