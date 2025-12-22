package com.shan.tts.manager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

object EngineScanner {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun scanAllEngines(context: Context, onComplete: () -> Unit) {
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos = context.packageManager.queryIntentServices(intent, 0)
        val engines = resolveInfos.map { it.serviceInfo.packageName }.filter { it != context.packageName }.distinct()

        if (engines.isEmpty()) {
            onComplete()
            return
        }

        mainHandler.post {
            scanRecursive(context, engines, 0, onComplete)
        }
    }

    private fun scanRecursive(context: Context, engines: List<String>, index: Int, onComplete: () -> Unit) {
        if (index >= engines.size) {
            onComplete()
            return
        }

        val pkgName = engines[index]
        var tts: TextToSpeech? = null

        val onNext = {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (e: Exception) {}
            
            mainHandler.post {
                scanRecursive(context, engines, index + 1, onComplete)
            }
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
        val tempFile = File(context.cacheDir, "probe_$pkg.wav")
        val uuid = UUID.randomUUID().toString()
        
        val isFinished = AtomicBoolean(false)
        
        fun finishOnce() {
            if (isFinished.compareAndSet(false, true)) {
                try { if (tempFile.exists()) tempFile.delete() } catch (e: Exception) {}
                onDone()
            }
        }

        val lower = pkg.lowercase(Locale.ROOT)
        var text = "Hello"
        var targetLocale = Locale.US

        if (lower.contains("shan") || lower.contains("shn")) {
            text = "မႂ်ႇသုင်ၶႃႈ"
            targetLocale = Locale("shn", "MM")
        } else if (lower.contains("myanmar") || lower.contains("ttsm") || lower.contains("burmese")) {
            text = "မင်္ဂလာပါ"
            targetLocale = Locale("my", "MM")
        } 
        
        try {
            val result = tts.setLanguage(targetLocale)
        } catch (e: Exception) {}

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { 
                saveFallback(context, pkg)
                finishOnce() 
            }
            override fun onDone(id: String?) { 
            }
        })

        try {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)

            tts.synthesizeToFile(text, params, tempFile, uuid)

            Thread {
                var wait = 0
                while (!isFinished.get()) {
                    if (tempFile.exists() && tempFile.length() > 100) {
                        break
                    }
                    Thread.sleep(50)
                    wait++
                    if (wait > 50) break 
                }
                
                if (!isFinished.get()) {
                    if (tempFile.exists() && tempFile.length() > 44) {
                        val rate = readRate(tempFile)
                        if (rate > 0) saveRate(context, pkg, rate)
                        else saveFallback(context, pkg)
                    } else {
                        saveFallback(context, pkg)
                    }
                    finishOnce()
                }
            }.start()
        } catch (e: Exception) {
            saveFallback(context, pkg)
            finishOnce()
        }
    }

    private fun readRate(file: File): Int {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(24) 
                Integer.reverseBytes(raf.readInt())
            }
        } catch (e: Exception) { 0 }
    }

    private fun saveRate(context: Context, pkg: String, rate: Int) {
        val finalRate = if (rate < 8000) 16000 else rate
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", finalRate).apply()
    }

    private fun saveFallback(context: Context, pkg: String) {
        val lower = pkg.lowercase(Locale.ROOT)
        val rate = when {
            lower.contains("google") -> 24000
            lower.contains("eloquence") -> 11025
            else -> 22050 
        }
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", rate).apply()
    }
}

