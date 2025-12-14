package com.shan.tts.manager

object AudioProcessor {
    init {
        try {
            System.loadLibrary("cherry-audio")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    external fun initSonic(sampleRate: Int, channels: Int)
    external fun setConfig(speed: Float, pitch: Float, rate: Float)
    external fun processAudio(input: ByteArray, length: Int): ByteArray
    external fun flush()
}

