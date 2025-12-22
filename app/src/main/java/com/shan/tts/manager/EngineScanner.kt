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
        AppLogger.log("Scanner: === STARTING ENGINE DISCOVERY ===")
        
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos = context.packageManager.queryIntentServices(intent, 0)
        
        // ကိုယ့် Package ကလွဲရင် ကျန်တာအကုန်ယူမယ်
        val engines = resolveInfos.map { it.serviceInfo.packageName }
            .filter { it != context.packageName }
            .distinct()

        if (engines.isEmpty()) {
            AppLogger.log("Scanner: No external TTS engines found.")
            onComplete()
            return
        }

        AppLogger.log("Scanner: Found ${engines.size} engines: $engines")
        mainHandler.post {
            scanRecursive(context, engines, 0, onComplete)
        }
    }

    private fun scanRecursive(context: Context, engines: List<String>, index: Int, onComplete: () -> Unit) {
        if (index >= engines.size) {
            AppLogger.log("Scanner: === ALL TASKS COMPLETED ===")
            onComplete()
            return
        }

        val pkgName = engines[index]
        AppLogger.log("Scanner: [${index + 1}/${engines.size}] Probing -> $pkgName")
        
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
                    AppLogger.log("Scanner: Init Success ($pkgName). Starting synthesis...")
                    probeEngine(context, tts!!, pkgName) { onNext() }
                } else {
                    AppLogger.error("Scanner: Init Failed for $pkgName (Status: $status)")
                    saveFallback(context, pkgName)
                    onNext()
                }
            }, pkgName)
        } catch (e: Exception) {
            AppLogger.error("Scanner: Exception during init of $pkgName", e)
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
        var text = "Hello, testing frequency."
        var targetLocale = Locale.US

        if (lower.contains("shan") || lower.contains("shn")) {
            text = "မႂ်ႇသုင်ၶႃႈ"
            targetLocale = Locale("shn", "MM")
        } else if (lower.contains("myanmar") || lower.contains("burmese") || lower.contains("saomai") || lower.contains("ttsm")) {
            text = "မင်္ဂလာပါ ခင်ဗျာ"
            targetLocale = Locale("my", "MM")
        }
        
        try {
            tts.setLanguage(targetLocale)
        } catch (e: Exception) {
            AppLogger.log("Scanner: Warning - Could not set locale for $pkg")
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { 
                AppLogger.error("Scanner: Engine reported error during synthesis ($pkg)")
                saveFallback(context, pkg)
                finishOnce() 
            }
            override fun onDone(id: String?) { }
        })

        try {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)

            tts.synthesizeToFile(text, params, tempFile, uuid)

            Thread {
                var wait = 0
                while (!isFinished.get()) {
                    if (tempFile.exists() && tempFile.length() > 200) break
                    Thread.sleep(50)
                    wait++
                    if (wait > 60) break // 3 seconds timeout
                }
                
                if (!isFinished.get()) {
                    if (tempFile.exists() && tempFile.length() > 44) {
                        val rate = readRate(tempFile)
                        if (rate > 0) {
                            saveRate(context, pkg, rate)
                        } else {
                            AppLogger.error("Scanner: File exists but readRate failed (0) for $pkg")
                            saveFallback(context, pkg)
                        }
                    } else {
                        AppLogger.error("Scanner: Timeout or File missing for $pkg")
                        saveFallback(context, pkg)
                    }
                    finishOnce()
                }
            }.start()
        } catch (e: Exception) {
            AppLogger.error("Scanner: Exception in probe logic for $pkg", e)
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
        
        // အောင်မြင်ကြောင်း LOG
        AppLogger.log("Scanner: [SUCCESS] $pkg -> Detected Rate: $finalRate Hz")
        
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", finalRate).apply()
    }

    private fun saveFallback(context: Context, pkg: String) {
        // မအောင်မြင်၍ Fallback သုံးကြောင်း LOG
        val defaultRate = 22050
        AppLogger.log("Scanner: [FALLBACK] $pkg -> Using Standard $defaultRate Hz")

        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", defaultRate).apply()
    }
}

