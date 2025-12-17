package com.shan.tts.manager

object AudioProcessor {
    init {
        try {
            System.loadLibrary("cherry-audio")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    external fun initSonic(rate: Int, ch: Int)
    external fun setConfig(speed: Float, pitch: Float)
    external fun processAudio(inData: ByteArray, len: Int): ByteArray
    external fun flush()
}

