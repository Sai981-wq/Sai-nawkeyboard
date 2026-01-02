package com.shan.tts.manager

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioProcessor(private val sourceHz: Int, channels: Int) {

    private var sonic: Sonic? = null
    private val TARGET_HZ = 24000
    private val tempBuffer = ByteArray(4096 * 4) 

    init {
        sonic = Sonic(sourceHz, channels)
        sonic?.speed = 1.0f
        sonic?.pitch = 1.0f
        sonic?.rate = 1.0f 
        sonic?.volume = 1.0f
    }

    fun init() {
        // Already initialized in constructor, but keeping method for compatibility
    }

    fun setSpeed(speed: Float) {
        sonic?.speed = speed
    }

    fun setPitch(pitch: Float) {
        sonic?.pitch = pitch
    }

    fun process(inBuffer: ByteBuffer?, len: Int, outBuffer: ByteBuffer, maxOut: Int): Int {
        val s = sonic ?: return 0

        // 1. Write Input to Sonic
        if (len > 0 && inBuffer != null) {
            val bytes = ByteArray(len)
            inBuffer.get(bytes)
            s.writeBytesToStream(bytes, len)
        }

        // 2. Read from Sonic (At Source Rate)
        var totalProcessed = 0
        
        while (true) {
            val available = s.samplesAvailable() * 2 // 1 channel * 2 bytes
            if (available <= 0) break

            // Read a chunk from Sonic
            val readLen = kotlin.math.min(available, tempBuffer.size)
            val bytesRead = s.readBytesFromStream(tempBuffer, readLen)
            
            if (bytesRead > 0) {
                // 3. Resample to Target Rate (24000Hz)
                val resampledBytes = TTSUtils.resample(tempBuffer, bytesRead, sourceHz, TARGET_HZ)
                
                if (outBuffer.remaining() < resampledBytes.size) {
                    // Output buffer full, stop processing for now
                    break
                }
                
                outBuffer.put(resampledBytes)
                totalProcessed += resampledBytes.size
            } else {
                break
            }
            
            if (totalProcessed >= maxOut) break
        }

        return totalProcessed
    }

    fun flushQueue() {
        sonic?.flushStream()
    }

    fun release() {
        sonic = null
    }
}

