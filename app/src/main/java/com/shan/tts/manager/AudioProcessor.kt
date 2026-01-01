package com.shan.tts.manager

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class AudioProcessor {

    companion object {
        var isLibraryLoaded = false
        init {
            try {
                System.loadLibrary("native-lib")
                isLibraryLoaded = true
                AppLogger.log("Native Lib Loaded.")
            } catch (e: Throwable) {
                isLibraryLoaded = false
                AppLogger.error("Failed to load native-lib", Exception(e))
            }
        }
    }

    private val handle = AtomicLong(0)

    fun init() {
        if (isLibraryLoaded) {
            // Always init at 24000, inputHz is handled in process()
            val h = initSonic(24000, 1)
            if (h != 0L) handle.set(h)
        }
    }

    // Updated: Takes inputHz to perform resampling in C++
    fun process(inBuffer: ByteBuffer?, len: Int, outBuffer: ByteBuffer, maxOut: Int, inputHz: Int): Int {
        if (!isLibraryLoaded) return 0
        val h = handle.get()
        if (h == 0L) return 0
        return processAudio(h, inBuffer, len, outBuffer, maxOut, inputHz)
    }

    fun flushQueue() {
        if (!isLibraryLoaded) return
        val h = handle.get()
        if (h != 0L) flush(h)
    }

    fun release() {
        if (!isLibraryLoaded) return
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
    // Added inputHz parameter
    private external fun processAudio(handle: Long, inBuffer: ByteBuffer?, len: Int, outBuffer: ByteBuffer, maxOut: Int, inputHz: Int): Int
    private external fun flush(handle: Long)
    private external fun stop(handle: Long)
    private external fun setSonicSpeed(handle: Long, speed: Float)
    private external fun setSonicPitch(handle: Long, pitch: Float)
}

