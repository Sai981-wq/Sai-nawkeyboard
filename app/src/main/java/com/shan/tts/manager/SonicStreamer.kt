package com.shan.tts.manager

import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.*
import java.io.InputStream

object SonicStreamer {

    suspend fun stream(
        inputStream: InputStream,
        callback: SynthesisCallback,
        inputHz: Int,
        outputHz: Int,
        reqId: String
    ) = withContext(Dispatchers.IO) {

        val sonic = Sonic(inputHz, 1)
        val ratio = inputHz.toFloat() / outputHz.toFloat()

        sonic.rate = ratio
        sonic.speed = 1.0f
        sonic.pitch = 1.0f
        sonic.quality = 0

        // Initial capacity, but will grow as needed
        var currentBufferSize = 64 * 1024 
        val inputBuffer = ByteArray(currentBufferSize)
        var outputBuffer = ByteArray(currentBufferSize)
        
        var totalRead = 0
        var totalWrote = 0

        try {
            while (isActive) {
                val bytesRead = try {
                    inputStream.read(inputBuffer)
                } catch (e: Exception) { -1 }

                if (bytesRead == -1) break

                if (bytesRead > 0) {
                    totalRead += bytesRead
                    sonic.writeBytesToStream(inputBuffer, bytesRead)

                    while (isActive) {
                        val availableSamples = sonic.samplesAvailable()
                        if (availableSamples <= 0) break

                        val requiredBytes = availableSamples * 2

                        if (outputBuffer.size < requiredBytes) {
                            outputBuffer = ByteArray(requiredBytes)
                            AppLogger.log("[$reqId] Buffer Expanded: ${outputBuffer.size} bytes")
                        }

                        val processedBytes = sonic.readBytesFromStream(outputBuffer, requiredBytes)

                        if (processedBytes > 0) {
                            val ret = callback.audioAvailable(outputBuffer, 0, processedBytes)
                            if (ret == TextToSpeech.ERROR) {
                                AppLogger.error("[$reqId] Speaker Write Failed")
                                return@withContext
                            }
                            totalWrote += processedBytes
                        } else {
                            break
                        }
                    }
                }
            }

            sonic.flushStream()
            while (isActive) {
                val available = sonic.samplesAvailable()
                if (available <= 0) break

                val requiredBytes = available * 2
                
                if (outputBuffer.size < requiredBytes) {
                    outputBuffer = ByteArray(requiredBytes)
                }

                val processedBytes = sonic.readBytesFromStream(outputBuffer, requiredBytes)
                if (processedBytes > 0) {
                    callback.audioAvailable(outputBuffer, 0, processedBytes)
                    totalWrote += processedBytes
                } else {
                    break
                }
            }
            AppLogger.log("[$reqId] Stream Stats: Read=$totalRead -> Wrote=$totalWrote")

        } catch (e: Exception) {
            AppLogger.error("[$reqId] Stream Exception", e)
        }
    }
}
