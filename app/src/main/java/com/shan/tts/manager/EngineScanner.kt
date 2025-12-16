package com.shan.tts.manager

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

object EngineScanner {

    fun scanAllEngines(context: Context, onComplete: () -> Unit) {
        // Get all TTS engines installed
        val intent = android.content.Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos = context.packageManager.queryIntentServices(intent, 0)
        val engines = resolveInfos.map { it.serviceInfo.packageName }.filter { it != context.packageName }

        if (engines.isEmpty()) {
            onComplete()
            return
        }

        scanRecursive(context, engines, 0, onComplete)
    }

    private fun scanRecursive(context: Context, engines: List<String>, index: Int, onComplete: () -> Unit) {
        if (index >= engines.size) {
            onComplete()
            return
        }

        val pkgName = engines[index]
        var tts: TextToSpeech? = null

        val onNext = {
            try { tts?.shutdown() } catch(e: Exception){}
            scanRecursive(context, engines, index + 1, onComplete)
        }

        try {
            tts = TextToSpeech(context, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    probeEngine(context, tts!!, pkgName) { onNext() }
                } else {
                    saveFallback(context, pkgName)
                    onNext()
                }
            }, pkgName)
        } catch (e: Exception) {
            saveFallback(context, pkgName)
            onNext()
        }
    }

    private fun probeEngine(context: Context, tts: TextToSpeech, pkg: String, onDone: () -> Unit) {
        val tempFile = File(context.cacheDir, "probe.wav")
        val uuid = "probe"
        
        // Text to probe
        val lower = pkg.lowercase(Locale.ROOT)
        val text = if (lower.contains("myanmar") || lower.contains("saomai")) "က" else "a"

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { onDone() }
            // We use file check loop, but this is backup
            override fun onDone(id: String?) {} 
        })

        try {
            if (tempFile.exists()) tempFile.delete()
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f) // Silent probe

            tts.synthesizeToFile(text, params, tempFile, uuid)

            // Wait for file
            Thread {
                var wait = 0
                while (!tempFile.exists() || tempFile.length() < 100) {
                    Thread.sleep(50)
                    wait++
                    if (wait > 40) break // 2 sec timeout
                }
                
                if (tempFile.exists() && tempFile.length() > 44) {
                    val rate = readRate(tempFile)
                    if (rate > 0) saveRate(context, pkg, rate)
                    else saveFallback(context, pkg)
                } else {
                    saveFallback(context, pkg)
                }
                onDone()
            }.start()
        } catch (e: Exception) {
            saveFallback(context, pkg)
            onDone()
        }
    }

    private fun readRate(file: File): Int {
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(24) // Sample Rate position in WAV header
                return Integer.reverseBytes(raf.readInt())
            }
        } catch (e: Exception) { return 0 }
    }

    private fun saveRate(context: Context, pkg: String, rate: Int) {
        val finalRate = if (rate < 8000) 16000 else rate
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", finalRate).apply()
    }

    private fun saveFallback(context: Context, pkg: String) {
        val lower = pkg.lowercase(Locale.ROOT)
        val rate = when {
            lower.contains("eloquence") -> 11025
            lower.contains("espeak") || lower.contains("shan") -> 22050
            lower.contains("google") -> 24000
            else -> 16000
        }
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", rate).apply()
    }
}

