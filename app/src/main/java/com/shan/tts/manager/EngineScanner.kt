package com.shan.tts.manager

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile

object EngineScanner {

    fun scanAllEngines(context: Context, onProgress: (String) -> Unit, onComplete: () -> Unit) {
        AppLogger.log("Starting Engine Scan...")
        
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos = context.packageManager.queryIntentServices(intent, 0)
        
        // Filter out our own package to prevent self-binding loop
        val engines = resolveInfos.map { it.serviceInfo.packageName }
            .filter { it != context.packageName }
        
        AppLogger.log("Found ${engines.size} external engines: $engines")

        if (engines.isEmpty()) {
            onComplete()
            return
        }

        scanRecursive(context, engines, 0, onProgress, onComplete)
    }

    private fun scanRecursive(context: Context, engines: List<String>, index: Int, onProgress: (String) -> Unit, onComplete: () -> Unit) {
        if (index >= engines.size) {
            AppLogger.log("Scan Finished.")
            onComplete()
            return
        }

        val pkgName = engines[index]
        val msg = "Scanning ($index/${engines.size}): $pkgName"
        onProgress(msg)
        AppLogger.log(msg)

        var tts: TextToSpeech? = null
        
        val onNext = {
            try { tts?.shutdown() } catch(e: Exception){}
            scanRecursive(context, engines, index + 1, onProgress, onComplete)
        }

        try {
            tts = TextToSpeech(context, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    detectAndSaveRate(context, tts!!, pkgName)
                    onNext()
                } else {
                    AppLogger.error("Failed to bind to $pkgName")
                    saveFallback(context, pkgName)
                    onNext()
                }
            }, pkgName)
        } catch (e: Exception) {
            AppLogger.error("Exception binding to $pkgName", e)
            saveFallback(context, pkgName)
            onNext()
        }
    }

    private fun detectAndSaveRate(context: Context, tts: TextToSpeech, pkgName: String) {
        val tempFile = File(context.cacheDir, "scan_${pkgName.hashCode()}.wav")
        val uuid = "scan_${System.currentTimeMillis()}"
        
        try {
            if (tempFile.exists()) tempFile.delete()
            
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {}
            })

            // Synthesize a slightly longer text to ensure valid header
            val result = tts.synthesizeToFile("Hello", null, tempFile, uuid)
            
            if (result == TextToSpeech.SUCCESS) {
                var waits = 0
                while (!tempFile.exists() || tempFile.length() < 44) {
                    try { Thread.sleep(50) } catch (e: Exception) {}
                    waits++
                    if (waits > 40) { 
                        AppLogger.log("Timeout waiting for audio from $pkgName")
                        saveFallback(context, pkgName)
                        return
                    }
                }
                
                val rate = readWavSampleRate(tempFile)
                if (rate > 0) {
                    AppLogger.log("Detected Rate for $pkgName: $rate Hz")
                    saveRate(context, pkgName, rate)
                } else {
                    AppLogger.log("Invalid Rate (0Hz) for $pkgName")
                    saveFallback(context, pkgName)
                }
            } else {
                saveFallback(context, pkgName)
            }
        } catch (e: Exception) {
            saveFallback(context, pkgName)
        } finally {
            try { tempFile.delete() } catch (e: Exception) {}
        }
    }

    private fun readWavSampleRate(file: File): Int {
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(24)
                val byte1 = raf.read()
                val byte2 = raf.read()
                val byte3 = raf.read()
                val byte4 = raf.read()
                return (byte1 and 0xFF) or ((byte2 and 0xFF) shl 8) or 
                       ((byte3 and 0xFF) shl 16) or ((byte4 and 0xFF) shl 24)
            }
        } catch (e: Exception) { return 0 }
    }

    private fun saveRate(context: Context, pkg: String, rate: Int) {
        // Enforce valid range. Eloquence 11k is valid, but 0 is not.
        val validRate = if (rate < 4000) 24000 else rate
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit()
            .putInt("RATE_$pkg", validRate)
            .apply()
    }

    private fun saveFallback(context: Context, pkg: String) {
        // Fallback Logic based on known engines
        var rate = 24000 // Default safe bet
        
        val lower = pkg.toLowerCase()
        if (lower.contains("espeak") || lower.contains("shan")) rate = 22050
        if (lower.contains("eloquence")) rate = 11025 // Eloquence usually 11k
        if (lower.contains("nirenr")) rate = 16000 // Talkman often 16k
        
        AppLogger.log("Using fallback rate $rate Hz for $pkg")
        saveRate(context, pkg, rate)
    }
}

