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
    private val chunkWriteBuffer = ByteArray(4096)
    
    // Stats for logging
    private var totalBytesRead = 0
    private var totalBytesWritten = 0

    init {
        sonic = Sonic(inputHz, 1)
        
        val resampleRatio = inputHz.toFloat() / outputHz.toFloat()
        
        sonic?.rate = resampleRatio
        sonic?.quality = 0
        
        // Log the Ratio calculation
        // If Ratio is 1.0, Input = Output (No Distortion)
        // If Ratio < 1.0 (e.g., 0.66), Input < Output (Upsampling)
        // If Ratio > 1.0, Input > Output (Downsampling)
        // AppLogger.log("[$reqId] Proc Init: In=$inputHz Out=$outputHz Ratio=$resampleRatio")
    }

    fun setSpeed(speed: Float) {
        sonic?.speed = speed
    }

    fun setPitch(pitch: Float) {
        sonic?.pitch = pitch
    }

    fun processStream(inputStream: InputStream, callback: SynthesisCallback, scope: CoroutineScope) {
        val s = sonic ?: return

        while (scope.isActive) {
            val bytesRead = try { inputStream.read(chunkReadBuffer) } catch (e: Exception) { -1 }
            if (bytesRead == -1) break

            if (bytesRead > 0) {
                totalBytesRead += bytesRead
                // Feed Raw Data
                s.writeBytesToStream(chunkReadBuffer, bytesRead)

                if (!drainToCallback(callback, scope)) break
            }
        }
        
        s.flushStream()
        drainToCallback(callback, scope)
        
        // Log Summary
        AppLogger.log("[$reqId] Stream End: Read=$totalBytesRead bytes, Wrote=$totalBytesWritten bytes")
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
                    AppLogger.error("[$reqId] AudioTrack Write Error!")
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

