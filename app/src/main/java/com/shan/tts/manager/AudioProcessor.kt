package com.shan.tts.manager

import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class AudioProcessor(private val sourceHz: Int, channels: Int) {

    private var sonic: Sonic? = null
    private val TARGET_HZ = 24000
    
    private val inputBuffer = ByteBuffer.allocateDirect(8192).order(ByteOrder.LITTLE_ENDIAN)
    private val outputBuffer = ByteBuffer.allocateDirect(8192).order(ByteOrder.LITTLE_ENDIAN)
    private val chunkReadBuffer = ByteArray(4096)
    private val chunkWriteBuffer = ByteArray(8192)

    init {
        sonic = Sonic(sourceHz, channels)
        val resampleRatio = sourceHz.toFloat() / TARGET_HZ.toFloat()
        
        sonic?.rate = resampleRatio
        sonic?.speed = 1.0f
        sonic?.pitch = 1.0f
        sonic?.volume = 1.0f
        sonic?.quality = 1 
        
        AppLogger.log("Processor Init: In=$sourceHz, Out=$TARGET_HZ")
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
                inputBuffer.clear()
                inputBuffer.put(chunkReadBuffer, 0, bytesRead)
                inputBuffer.flip()
                
                val bytes = ByteArray(bytesRead)
                inputBuffer.get(bytes)
                s.writeBytesToStream(bytes, bytesRead)

                drainToCallback(callback, scope)
            }
        }
        
        s.flushStream()
        drainToCallback(callback, scope)
    }

    private fun drainToCallback(callback: SynthesisCallback, scope: CoroutineScope) {
        val s = sonic ?: return
        
        while (scope.isActive) {
            val available = s.samplesAvailable() * 2
            if (available <= 0) break

            outputBuffer.clear()
            val spaceInOut = outputBuffer.capacity()
            val toRead = min(available, spaceInOut)
            
            val tempRead = ByteArray(toRead)
            val bytesRead = s.readBytesFromStream(tempRead, toRead)

            if (bytesRead > 0) {
                outputBuffer.put(tempRead, 0, bytesRead)
                outputBuffer.flip()
                outputBuffer.get(chunkWriteBuffer, 0, bytesRead)
                
                var offset = 0
                while (offset < bytesRead) {
                    if (!scope.isActive) break
                    val remaining = bytesRead - offset
                    val maxBuf = callback.maxBufferSize
                    val len = min(remaining, maxBuf)
                    
                    val ret = callback.audioAvailable(chunkWriteBuffer, offset, len)
                    if (ret == TextToSpeech.ERROR) {
                        AppLogger.error("AudioTrack Write Error")
                        return 
                    }
                    offset += len
                }
            } else {
                break
            }
        }
    }

    fun release() {
        sonic = null
    }
}

