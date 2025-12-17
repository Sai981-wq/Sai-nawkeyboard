package com.shan.tts.manager

object AudioProcessor {
    init {
        System.loadLibrary("cherry-audio")
    }

    external fun initSonic(sampleRate: Int, channels: Int)
    external fun setConfig(speed: Float, pitch: Float)
    external fun processAudio(buffer: ByteArray, len: Int): ByteArray
    external fun drain(): ByteArray
    external fun flush()
}

