package com.shan.tts.manager

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class AudioProcessor(sampleRate: Int, channels: Int) {

    companion object {
        var isLibraryLoaded = false
        init {
            try {
                System.loadLibrary("native-lib")
                isLibraryLoaded = true
                AppLogger.log("Native Library Loaded Successfully.")
            } catch (e: Throwable) {
                isLibraryLoaded = false
                AppLogger.error("Failed to load native-lib", Exception(e))
            }
        }
    }

    private val handle = AtomicLong(0)

    init {
        if (isLibraryLoaded) {
            AppLogger.log("Initializing Sonic: Rate=$sampleRate, Channels=$channels")
            val h = initSonic(sampleRate, channels)
            if (h != 0L) {
                handle.set(h)
                AppLogger.log("Sonic Initialized. Handle: $h")
            } else {
                AppLogger.error("Sonic Init Failed: Returned 0 handle")
            }
        } else {
            AppLogger.error("Cannot Init Sonic: Library not loaded")
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
            AppLogger.error("Process called with Invalid Handle")
            return 0
        }
        
        // Verbose logging (Can be commented out if too spammy)
        // AppLogger.log("Processing Audio: InputLen=$len, MaxOut=$maxOut")
        
        return processAudio(h, inBuffer, len, outBuffer, maxOut)
    }

    fun flushQueue() {
        if (!isLibraryLoaded) return
        val h = handle.get()
        if (h != 0L) {
            AppLogger.log("Flushing Sonic Stream...")
            flush(h)
        }
    }

    fun release() {
        if (!isLibraryLoaded) return
        val h = handle.getAndSet(0L)
        if (h != 0L) {
            AppLogger.log("Releasing Sonic Handle: $h")
            stop(h)
        }
    }

    fun setSpeed(speed: Float) {
        if (!isLibraryLoaded) return
        val h = handle.get()
        if (h != 0L) {
            AppLogger.log("Setting Sonic Speed: $speed")
            setSonicSpeed(h, speed)
        }
    }

    fun setPitch(pitch: Float) {
        if (!isLibraryLoaded) return
        val h = handle.get()
        if (h != 0L) {
            AppLogger.log("Setting Sonic Pitch: $pitch")
            setSonicPitch(h, pitch)
        }
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

