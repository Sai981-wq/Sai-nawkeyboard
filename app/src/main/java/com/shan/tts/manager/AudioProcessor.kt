package com.shan.tts.manager

import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import java.io.InputStream
import kotlin.math.min

class AudioProcessor(inputHz: Int, outputHz: Int, private val reqId: String) {

    private var sonic: Sonic? = null
    private val chunkReadBuffer = ByteArray(8192)
    private val chunkWriteBuffer = ByteArray(8192)

    init {
        sonic = Sonic(inputHz, 1)
        val resampleRatio = inputHz.toFloat() / outputHz.toFloat()
        
        sonic?.rate = resampleRatio
        sonic?.quality = 0 
    }

    fun setSpeed(speed: Float) { sonic?.speed = speed }
    fun setPitch(pitch: Float) { sonic?.pitch = pitch }

    fun processStream(inputStream: InputStream, callback: SynthesisCallback, scope: CoroutineScope) {
        val s = sonic ?: return

        while (scope.isActive) {
            val bytesRead = try { inputStream.read(chunkReadBuffer) } catch (e: Exception) { -1 }
            if (bytesRead == -1) break

            if (bytesRead > 0) {
                s.writeBytesToStream(chunkReadBuffer, bytesRead)
                if (!drainToCallback(callback, scope)) break
            }
        }
        
        s.flushStream()
        drainToCallback(callback, scope)
    }

    private fun drainToCallback(callback: SynthesisCallback, scope: CoroutineScope): Boolean {
        val s = sonic ?: return true
        
        while (scope.isActive) {
            val available = s.samplesAvailable() * 2
            if (available <= 0) break

            val toRead = min(available, chunkWriteBuffer.size)
            val bytesRead = s.readBytesFromStream(chunkWriteBuffer, toRead)

            if (bytesRead > 0) {
                val ret = callback.audioAvailable(chunkWriteBuffer, 0, bytesRead)
                if (ret == TextToSpeech.ERROR) {
                    AppLogger.error("[$reqId] Write FAILED")
                    return false
                }
            } else {
                break
            }
        }
        return true
    }

    fun release() {
        sonic = null
    }
}

