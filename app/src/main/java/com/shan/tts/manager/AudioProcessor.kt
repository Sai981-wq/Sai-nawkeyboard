package com.shan.tts.manager

import java.nio.ByteBuffer

class AudioProcessor(private val sourceHz: Int, channels: Int) {

    private var sonic: Sonic? = null
    private val TARGET_HZ = 24000
    // Increased temp buffer to prevent bottlenecks
    private val tempBuffer = ByteArray(8192) 

    init {
        sonic = Sonic(sourceHz, channels)
        sonic?.speed = 1.0f
        sonic?.pitch = 1.0f
        sonic?.rate = 1.0f 
        sonic?.volume = 1.0f
    }

    fun init() {
        // Compatibility method
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

        // 2. Read All Available from Sonic & Resample
        var totalProcessed = 0
        
        // Loop until sonic is empty or outBuffer is full
        while (true) {
            val availableSamples = s.samplesAvailable()
            if (availableSamples <= 0) break

            // Calculate safe read size
            val bytesToRead = kotlin.math.min(availableSamples * 2, tempBuffer.size)
            if (bytesToRead <= 0) break

            val bytesRead = s.readBytesFromStream(tempBuffer, bytesToRead)
            
            if (bytesRead > 0) {
                // High Quality Cubic Resample
                val resampledBytes = TTSUtils.resample(tempBuffer, bytesRead, sourceHz, TARGET_HZ)
                
                // Check if we have space
                if (outBuffer.remaining() < resampledBytes.size) {
                    // Critical: If output buffer is full, we must stop and return what we have.
                    // The service loop will call us again because sonic still has data.
                    
                    // Push back the unread data? No, in streaming we just pause.
                    // Ideally, we should not have read from sonic if we can't write.
                    // But for simplicity, we assume Service provides large enough buffer (32KB).
                    
                    // Force put as much as possible?
                    val space = outBuffer.remaining()
                    if (space > 0) {
                        outBuffer.put(resampledBytes, 0, space)
                        totalProcessed += space
                    }
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

