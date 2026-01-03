package com.shan.tts.manager

import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import java.io.InputStream
import kotlin.math.min

// Added reqId to identify logs
class AudioProcessor(inputHz: Int, outputHz: Int, private val reqId: String) {

    private var sonic: Sonic? = null
    private val chunkReadBuffer = ByteArray(4096)
    private val chunkWriteBuffer = ByteArray(4096)
    
    // Stats for logging
    private var totalBytesRead = 0
    private var totalBytesWritten = 0

    init {
        sonic = Sonic(inputHz, 1)
        
        // Calculate Resample Ratio
        val resampleRatio = inputHz.toFloat() / outputHz.toFloat()
        
        sonic?.rate = resampleRatio
        sonic?.quality = 0 
        
        // Optional: Log init params to check logic
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
            // Read raw PCM (Service already skipped the header)
            val bytesRead = try { inputStream.read(chunkReadBuffer) } catch (e: Exception) { -1 }
            if (bytesRead == -1) break

            if (bytesRead > 0) {
                totalBytesRead += bytesRead
                s.writeBytesToStream(chunkReadBuffer, bytesRead)

                if (!drainToCallback(callback, scope)) break
            }
        }
        
        s.flushStream()
        drainToCallback(callback, scope)
        
        // Final Log Summary: Useful to detect "Speeding Up" (if Wrote is > 3x Read)
        AppLogger.log("[$reqId] Stream End: Read=$totalBytesRead, Wrote=$totalBytesWritten")
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
                    AppLogger.error("[$reqId] AudioTrack Write Failed")
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

