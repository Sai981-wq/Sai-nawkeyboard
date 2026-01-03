package com.shan.tts.manager

import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class AudioProcessor(sourceHz: Int, channels: Int) {

    private var sonic: Sonic? = null
    private val TARGET_HZ = 24000
    
    // Increased buffer size for smoother streaming
    private val chunkReadBuffer = ByteArray(4096)
    private val chunkWriteBuffer = ByteArray(8192)

    init {
        sonic = Sonic(sourceHz, channels)
        val resampleRatio = sourceHz.toFloat() / TARGET_HZ.toFloat()
        
        sonic?.rate = resampleRatio
        sonic?.quality = 0 // Fastest quality
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
                // Direct feed to Sonic
                s.writeBytesToStream(chunkReadBuffer, bytesRead)

                // Drain immediately to keep latency low
                if (!drainToCallback(callback, scope)) break
            }
        }
        
        // Final flush
        s.flushStream()
        drainToCallback(callback, scope)
    }

    private fun drainToCallback(callback: SynthesisCallback, scope: CoroutineScope): Boolean {
        val s = sonic ?: return true
        
        while (scope.isActive) {
            val available = s.samplesAvailable() * 2
            if (available <= 0) break

            // Read directly from Sonic
            val bytesRead = s.readBytesFromStream(chunkWriteBuffer, chunkWriteBuffer.size)

            if (bytesRead > 0) {
                var offset = 0
                while (offset < bytesRead) {
                    if (!scope.isActive) return false
                    
                    val remaining = bytesRead - offset
                    val maxBuf = callback.maxBufferSize
                    val len = min(remaining, maxBuf)
                    
                    // Direct write to AudioTrack via Callback
                    val ret = callback.audioAvailable(chunkWriteBuffer, offset, len)
                    if (ret == TextToSpeech.ERROR) {
                        return false
                    }
                    offset += len
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

