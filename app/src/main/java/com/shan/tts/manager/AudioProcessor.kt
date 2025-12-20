package com.shan.tts.manager

import java.nio.ByteBuffer

class AudioProcessor {

    private var sonicHandle: Long = 0

    companion object {
        init {
            System.loadLibrary("cherry-audio")
        }
    }

    fun init(sampleRate: Int, channels: Int) {
        if (sonicHandle != 0L) {
            destroy()
        }
        sonicHandle = nativeCreate(sampleRate, channels)
    }

    fun setConfig(speed: Float, pitch: Float, rate: Float = 1.0f) {
        if (sonicHandle != 0L) {
            nativeSetConfig(sonicHandle, speed, pitch, rate)
        }
    }

    fun process(inBuffer: ByteBuffer, len: Int, outBuffer: ByteArray): Int {
        return if (sonicHandle != 0L) {
            nativeProcess(sonicHandle, inBuffer, len, outBuffer)
        } else 0
    }

    fun flush() {
        if (sonicHandle != 0L) nativeFlush(sonicHandle)
    }

    fun destroy() {
        if (sonicHandle != 0L) {
            nativeDestroy(sonicHandle)
            sonicHandle = 0
        }
    }

    private external fun nativeCreate(rate: Int, channels: Int): Long
    private external fun nativeSetConfig(handle: Long, speed: Float, pitch: Float, rate: Float)
    private external fun nativeProcess(handle: Long, inBuffer: ByteBuffer, len: Int, outArray: ByteArray): Int
    private external fun nativeFlush(handle: Long)
    private external fun nativeDestroy(handle: Long)
}

