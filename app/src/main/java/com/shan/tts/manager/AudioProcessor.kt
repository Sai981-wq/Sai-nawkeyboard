package com.shan.tts.manager

import java.nio.ByteBuffer

object AudioProcessor {
    init {
        System.loadLibrary("cherry-audio")
    }
    external fun initSonic(inputRate: Int, channels: Int)
    external fun processAudio(inBuffer: ByteBuffer, len: Int, outBuffer: ByteBuffer, maxOutLen: Int): Int
    external fun flush()
    external fun stop()
}

