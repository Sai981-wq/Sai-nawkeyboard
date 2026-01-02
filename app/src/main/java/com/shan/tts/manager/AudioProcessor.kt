package com.shan.tts.manager

import java.nio.ByteBuffer

class AudioProcessor(private val sourceHz: Int, channels: Int) {

    private var sonic: Sonic? = null
    private val TARGET_HZ = 24000
    private val tempBuffer = ByteArray(8192) 

    init {
        sonic = Sonic(sourceHz, channels)
        
        // ★ KEY FIX: Use Sonic to handle Hz conversion naturally ★
        // Calculate ratio: e.g. 22050 / 24000 = ~0.91
        // Sonic will generate more samples to match the target timeline.
        val resampleRatio = sourceHz.toFloat() / TARGET_HZ.toFloat()
        
        sonic?.rate = resampleRatio
        sonic?.speed = 1.0f
        sonic?.pitch = 1.0f
        sonic?.volume = 1.0f
        sonic?.quality = 1 // 0 = Fast, 1 = High Quality (Best for voice)
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

        // 2. Read directly from Sonic
        // Sonic automatically handles the resizing/resampling via 'rate'
        var totalProcessed = 0
        
        while (true) {
            // Check if we have data or if output is full
            val available = s.samplesAvailable() * 2 
            if (available <= 0) break
            if (outBuffer.remaining() <= 0) break

            // Read what we can fit
            val spaceInOut = outBuffer.remaining()
            val toRead = kotlin.math.min(available, spaceInOut)
            val safeRead = kotlin.math.min(toRead, tempBuffer.size)

            val bytesRead = s.readBytesFromStream(tempBuffer, safeRead)
            
            if (bytesRead > 0) {
                outBuffer.put(tempBuffer, 0, bytesRead)
                totalProcessed += bytesRead
            } else {
                break
            }
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

