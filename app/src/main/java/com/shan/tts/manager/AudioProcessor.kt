package com.shan.tts.manager

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

// Constructor accepts Hz now to fix the build error
class AudioProcessor(private val sampleRate: Int, private val channels: Int) {

    companion object {
        var isLibraryLoaded = false
        init {
            try {
                System.loadLibrary("native-lib")
                isLibraryLoaded = true
                AppLogger.log("AudioProcessor: Native Library Loaded.")
            } catch (e: Throwable) {
                isLibraryLoaded = false
                AppLogger.error("AudioProcessor: Load Failed", Exception(e))
            }
        }
    }

    private val handle = AtomicLong(0)

    // Call this explicitly to start Sonic
    fun init() {
        if (isLibraryLoaded) {
            AppLogger.log("AudioProcessor: Init Sonic with Hz=$sampleRate")
            val h = initSonic(sampleRate, channels)
            if (h != 0L) {
                handle.set(h)
                AppLogger.log("AudioProcessor: Init Success. Handle=$h")
            } else {
                AppLogger.error("AudioProcessor: Init Failed (Handle=0)")
            }
        }
    }

    fun process(
        inBuffer: ByteBuffer?,
        len: Int,
        outBuffer: ByteBuffer,
        maxOut: Int
    ): Int {
        if (!isLibraryLoaded) return 0
        val h = handle.get()
        if (h == 0L) {
            AppLogger.error("AudioProcessor: Process called on invalid handle")
            return 0
        }
        return processAudio(h, inBuffer, len, outBuffer, maxOut)
    }

    fun flushQueue() {
        if (!isLibraryLoaded) return
        val h = handle.get()
        if (h != 0L) {
            AppLogger.log("AudioProcessor: Flushing")
            flush(h)
        }
    }

    fun release() {
        if (!isLibraryLoaded) return
        val h = handle.getAndSet(0L)
        if (h != 0L) {
            AppLogger.log("AudioProcessor: Releasing Handle")
            stop(h)
        }
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
    private external fun processAudio(handle: Long, inBuffer: ByteBuffer?, len: Int, outBuffer: ByteBuffer, maxOut: Int): Int
    private external fun flush(handle: Long)
    private external fun stop(handle: Long)
    private external fun setSonicSpeed(handle: Long, speed: Float)
    private external fun setSonicPitch(handle: Long, pitch: Float)
}

