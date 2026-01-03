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
    
    private var totalBytesRead = 0
    private var totalBytesWritten = 0

    init {
        sonic = Sonic(inputHz, 1)
        val resampleRatio = inputHz.toFloat() / outputHz.toFloat()
        sonic?.rate = resampleRatio
        sonic?.quality = 0
        // AppLogger.log("[$reqId] Proc Init: Ratio=$resampleRatio")
    }

    fun setSpeed(speed: Float) { sonic?.speed = speed }
    fun setPitch(pitch: Float) { sonic?.pitch = pitch }

    fun processStream(inputStream: InputStream, callback: SynthesisCallback, scope: CoroutineScope) {
        val s = sonic ?: return

        while (scope.isActive) {
            val bytesRead = try { inputStream.read(chunkReadBuffer) } catch (e: Exception) { 
                AppLogger.error("[$reqId] Stream Read Error", e)
                -1 
            }
            if (bytesRead == -1) break

            if (bytesRead > 0) {
                totalBytesRead += bytesRead
                s.writeBytesToStream(chunkReadBuffer, bytesRead)
                if (!drainToCallback(callback, scope)) break
            }
        }
        
        s.flushStream()
        drainToCallback(callback, scope)
        
        AppLogger.log("[$reqId] Stream End: In=$totalBytesRead, Out=$totalBytesWritten")
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
                    AppLogger.error("[$reqId] AudioTrack Write FAILED")
                    return false
                }
                totalBytesWritten += bytesRead
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

