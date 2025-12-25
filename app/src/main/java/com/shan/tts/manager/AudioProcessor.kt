package com.shan.tts.manager

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

object AudioProcessor {

    init {
        try {
            System.loadLibrary("native-lib")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("AudioProcessor", "JNI load failed", e)
        }
    }

    private val handle = AtomicLong(0)

    fun isInitialized(): Boolean = handle.get() != 0L

    @Synchronized
    fun initialize(sampleRate: Int, channels: Int) {
        if (handle.get() != 0L) return
        val h = initSonic(sampleRate, channels)
        if (h == 0L) throw IllegalStateException("Sonic init failed")
        handle.set(h)
    }

    fun process(
        inBuffer: ByteBuffer?,
        len: Int,
        outBuffer: ByteBuffer,
        maxOut: Int
    ): Int {
        val h = handle.get()
        if (h == 0L) return 0
        return processAudio(h, inBuffer, len, outBuffer, maxOut)
    }

    @Synchronized
    fun flushQueue() {
        val h = handle.get()
        if (h != 0L) flush(h)
    }

    @Synchronized
    fun stop() {
        val h = handle.getAndSet(0L)
        if (h != 0L) stop(h)
    }

    fun setSpeed(speed: Float) {
        val h = handle.get()
        if (h != 0L) setSonicSpeed(h, speed)
    }

    fun setPitch(pitch: Float) {
        val h = handle.get()
        if (h != 0L) setSonicPitch(h, pitch)
    }

    private external fun initSonic(sampleRate: Int, channels: Int): Long
    private external fun processAudio(
        handle: Long,
        inBuffer: ByteBuffer?,
        len: Int,
        outBuffer: ByteBuffer,
        maxOut: Int
    ): Int

    private external fun flush(handle: Long)
    private external fun stop(handle: Long)
    private external fun setSonicSpeed(handle: Long, speed: Float)
    private external fun setSonicPitch(handle: Long, pitch: Float)
}
