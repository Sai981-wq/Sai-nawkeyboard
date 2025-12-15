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
        
        // --- FIX: Correct Intent Action ---
        val intent = Intent("android.intent.action.TTS_SERVICE")
        
        val resolveInfos = context.packageManager.queryIntentServices(intent, 0)
        val engines = resolveInfos.map { it.serviceInfo.packageName }
        
        AppLogger.log("Found ${engines.size} engines: $engines")

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
                    AppLogger.log("Bound to $pkgName. Detecting rate...")
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

            val result = tts.synthesizeToFile("a", null, tempFile, uuid)
            
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
                AppLogger.log("Detected Rate for $pkgName: $rate Hz")
                saveRate(context, pkgName, rate)
            } else {
                AppLogger.log("Synthesis failed for $pkgName")
                saveFallback(context, pkgName)
            }
        } catch (e: Exception) {
            AppLogger.error("Error detecting rate for $pkgName", e)
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
        val validRate = if (rate < 8000) 24000 else rate
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit()
            .putInt("RATE_$pkg", validRate)
            .apply()
    }

    private fun saveFallback(context: Context, pkg: String) {
        val rate = if (pkg.contains("espeak") || pkg.contains("shan")) 22050 else 24000
        AppLogger.log("Using fallback rate $rate Hz for $pkg")
        saveRate(context, pkg, rate)
    }
}

