package com.shan.tts.manager

object AudioProcessor {
    init {
        try {
            System.loadLibrary("cherry-audio")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    external fun initSonic(sampleRate: Int, channels: Int): Long
    external fun setConfig(handle: Long, speed: Float, pitch: Float)
    external fun processAudio(handle: Long, input: ByteArray, length: Int): ByteArray
    external fun flush(handle: Long)
    external fun release(handle: Long)
}

