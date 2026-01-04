package com.shan.tts.manager

import java.nio.ByteBuffer

class AudioProcessor(private val sampleRate: Int, private val channels: Int) {
    private var sonic: Sonic? = null
    private val TARGET_HZ = 24000 

    fun init() {
        // Eloquence ကဲ့သို့ 11000 Hz ပြနေလျှင် Standard 11025 Hz သို့ ညှိပေးခြင်း
        val adjustedHz = when {
            sampleRate in 10900..11100 -> 11025
            sampleRate in 21900..22100 -> 22050
            else -> sampleRate
        }
        
        sonic = Sonic(TARGET_HZ, channels)
        val resamplingRate = adjustedHz.toFloat() / TARGET_HZ.toFloat()
        sonic?.rate = resamplingRate
        sonic?.speed = 1.0f
        sonic?.pitch = 1.0f
        sonic?.volume = 1.0f
        AppLogger.log("PROC: Init In=$adjustedHz, Out=$TARGET_HZ, Ratio=$resamplingRate")
    }

    fun reset() {
        sonic?.let {
            val r = it.rate
            val s = it.speed
            val p = it.pitch
            sonic = Sonic(TARGET_HZ, channels).apply {
                rate = r
                speed = s
                pitch = p
            }
        }
        AppLogger.log("PROC: Reset performed for clean transition.")
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
        AppLogger.log("PROC: Flushing queue.")
        sonic?.flushStream() 
    }
    fun release() { 
        AppLogger.log("PROC: Processor released.")
        sonic = null 
    }
    fun setSpeed(speed: Float) { sonic?.speed = speed }
    fun setPitch(pitch: Float) { sonic?.pitch = pitch }
}

