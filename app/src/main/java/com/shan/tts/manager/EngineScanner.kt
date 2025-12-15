package com.shan.tts.manager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

object EngineScanner {

    fun scanAllEngines(context: Context, onProgress: (String) -> Unit, onComplete: () -> Unit) {
        AppLogger.log("=== STARTING FULL SYSTEM SCAN ===")
        
        try {
            val intent = Intent("android.intent.action.TTS_SERVICE")
            val resolveInfos = context.packageManager.queryIntentServices(intent, 0)
            
            val engines = resolveInfos.map { it.serviceInfo.packageName }
                .filter { it != context.packageName }
            
            AppLogger.log("Detected ${engines.size} engines: $engines")

            if (engines.isEmpty()) {
                onComplete()
                return
            }

            scanRecursive(context, engines, 0, onProgress, onComplete)
        } catch (e: Exception) {
            AppLogger.error("Failed to query intent services", e)
            onComplete()
        }
    }

    private fun scanRecursive(context: Context, engines: List<String>, index: Int, onProgress: (String) -> Unit, onComplete: () -> Unit) {
        if (index >= engines.size) {
            AppLogger.log("=== SCAN COMPLETED ===")
            onComplete()
            return
        }

        val pkgName = engines[index]
        onProgress("Analyzing Engine (${index + 1}/${engines.size}):\n$pkgName")

        var tts: TextToSpeech? = null
        
        val onNext = {
            try { tts?.shutdown() } catch(e: Exception){ AppLogger.error("Shutdown error for $pkgName", e) }
            try { Thread.sleep(100) } catch(e:Exception){}
            scanRecursive(context, engines, index + 1, onProgress, onComplete)
        }

        try {
            tts = TextToSpeech(context, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    detectAndSaveRate(context, tts!!, pkgName)
                    onNext()
                } else {
                    AppLogger.error("Failed to bind: $pkgName (Status: $status)")
                    saveFallback(context, pkgName)
                    onNext()
                }
            }, pkgName)
        } catch (e: Exception) {
            AppLogger.error("Crash binding: $pkgName", e)
            saveFallback(context, pkgName)
            onNext()
        }
    }

    private fun detectAndSaveRate(context: Context, tts: TextToSpeech, pkgName: String) {
        val tempFile = File(context.cacheDir, "probe.wav")
        val uuid = "probe_${System.currentTimeMillis()}"
        
        val lowerPkg = pkgName.lowercase(Locale.ROOT)
        val probeText = when {
            lowerPkg.contains("myanmar") || lowerPkg.contains("saomai") -> "က"
            lowerPkg.contains("shan") -> "မ"
            else -> "Hello"
        }
        
        AppLogger.log("Probing $pkgName with text: '$probeText'")

        try {
            if (tempFile.exists()) tempFile.delete()
            
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {}
                override fun onError(id: String?) {}
            })

            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)

            val result = tts.synthesizeToFile(probeText, params, tempFile, uuid)
            
            if (result == TextToSpeech.SUCCESS) {
                var waits = 0
                while (!tempFile.exists() || tempFile.length() < 44) {
                    try { Thread.sleep(50) } catch (e: Exception) {}
                    waits++
                    if (waits > 60) { 
                        AppLogger.log("Timeout: No audio from $pkgName")
                        saveFallback(context, pkgName)
                        return
                    }
                }
                
                try { Thread.sleep(100) } catch(e:Exception){}

                val rate = readWavSampleRate(tempFile)
                if (rate > 0) {
                    AppLogger.log("SUCCESS: $pkgName -> $rate Hz")
                    saveRate(context, pkgName, rate)
                } else {
                    AppLogger.log("FAILED: 0Hz from $pkgName")
                    saveFallback(context, pkgName)
                }
            } else {
                AppLogger.log("Synthesis command failed for $pkgName")
                saveFallback(context, pkgName)
            }
        } catch (e: Exception) {
            AppLogger.error("Error during probe for $pkgName", e)
            saveFallback(context, pkgName)
        } finally {
            try { tempFile.delete() } catch (e: Exception) {}
        }
    }

    private fun readWavSampleRate(file: File): Int {
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 28) return 0
                raf.seek(24)
                val byte1 = raf.read()
                val byte2 = raf.read()
                val byte3 = raf.read()
                val byte4 = raf.read()
                return (byte1 and 0xFF) or ((byte2 and 0xFF) shl 8) or 
                       ((byte3 and 0xFF) shl 16) or ((byte4 and 0xFF) shl 24)
            }
        } catch (e: Exception) { 
            AppLogger.error("WAV read error", e)
            return 0 
        }
    }

    private fun saveRate(context: Context, pkg: String, rate: Int) {
        val validRate = if (rate < 4000) 24000 else rate
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit()
            .putInt("RATE_$pkg", validRate)
            .apply()
    }

    private fun saveFallback(context: Context, pkg: String) {
        var rate = 24000 
        val lower = pkg.lowercase(Locale.ROOT)
        if (lower.contains("espeak") || lower.contains("shan")) rate = 22050
        if (lower.contains("eloquence")) rate = 11025
        if (lower.contains("nirenr") || lower.contains("talkman")) rate = 16000 
        if (lower.contains("myanmar")) rate = 16000
        
        AppLogger.log("Fallback applied: $pkg -> $rate Hz")
        saveRate(context, pkg, rate)
    }
}

