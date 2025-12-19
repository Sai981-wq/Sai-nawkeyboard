package com.shan.tts.manager

import java.nio.ByteBuffer

object AudioProcessor {
    init {
        System.loadLibrary("cherry-audio")
    }

    external fun initSonic(sampleRate: Int, channels: Int)
    external fun setConfig(speed: Float, pitch: Float)
    external fun processAudio(inBuffer: ByteBuffer, len: Int, outBuffer: ByteArray): Int
    external fun flush()
}

