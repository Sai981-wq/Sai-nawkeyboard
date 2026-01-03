package com.shan.tts.manager

import java.nio.ByteBuffer

class AudioProcessor(private val sourceHz: Int, channels: Int) {

    private var sonic: Sonic? = null
    private val TARGET_HZ = 24000
    private val tempBuffer = ByteArray(8192)

    init {
        sonic = Sonic(sourceHz, channels)
        val resampleRatio = sourceHz.toFloat() / TARGET_HZ.toFloat()
        
        sonic?.rate = resampleRatio
        sonic?.speed = 1.0f
        sonic?.pitch = 1.0f
        sonic?.volume = 1.0f
        sonic?.quality = 1 
    }

    fun setSpeed(speed: Float) {
        sonic?.speed = speed
    }

    fun setPitch(pitch: Float) {
        sonic?.pitch = pitch
    }

    fun writeInput(inBuffer: ByteBuffer, len: Int) {
        val s = sonic ?: return
        if (len > 0) {
            val bytes = ByteArray(len)
            inBuffer.get(bytes)
            s.writeBytesToStream(bytes, len)
        }
    }

    fun availableBytes(): Int {
        return (sonic?.samplesAvailable() ?: 0) * 2
    }

    fun readOutput(outBuffer: ByteBuffer): Int {
        val s = sonic ?: return 0
        var totalProcessed = 0
        
        while (true) {
            val available = s.samplesAvailable() * 2 
            if (available <= 0) break
            if (outBuffer.remaining() <= 0) break

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
    
    fun process(inBuffer: ByteBuffer?, len: Int, outBuffer: ByteBuffer): Int {
        if (inBuffer != null) writeInput(inBuffer, len)
        return readOutput(outBuffer)
    }
}

