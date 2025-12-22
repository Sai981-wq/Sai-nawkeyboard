package com.shan.tts.manager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object EngineScanner {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun scanAllEngines(context: Context, onComplete: () -> Unit) {
        AppLogger.log("Scanner: === STARTING ENGINE DISCOVERY ===")
        
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos = context.packageManager.queryIntentServices(intent, 0)
        
        // (၁) မစစ်ဆေးချင်သော Engine များ (Block List)
        val blockedEngines = listOf(
            context.packageName,       // မိမိ App (Cherry TTS)
            "com.vnspeak.autotts"      // VN Speak Auto TTS (ကျော်မယ်)
        )

        // (၂) Block List ဖယ်ထုတ်ပြီး ကျန်တာယူမယ်
        val engines = resolveInfos.map { it.serviceInfo.packageName }
            .filter { pkgName -> !blockedEngines.contains(pkgName) }
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
        
        // Timeout စောင့်မည့် Lock
        val latch = CountDownLatch(1)

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
                latch.countDown()
            }
            override fun onDone(id: String?) { 
                // Engine ပြီးမှ Lock ဖွင့်မယ်
                latch.countDown()
            }
        })

        Thread {
            try {
                if (tempFile.exists()) tempFile.delete()

                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)
                
                tts.synthesizeToFile(text, params, tempFile, uuid)

                // ၆ စက္ကန့်ထိ စောင့်မယ် (Google တို့အတွက်)
                val success = latch.await(6, TimeUnit.SECONDS)

                if (!success) {
                    AppLogger.error("Scanner: Timeout waiting for onDone ($pkg)")
                }

                if (tempFile.exists() && tempFile.length() > 44) {
                    val rate = readRate(tempFile)
                    if (rate > 0) {
                        saveRate(context, pkg, rate)
                    } else {
                        AppLogger.error("Scanner: ReadRate failed (Got 0) for $pkg. FileSize: ${tempFile.length()}")
                        saveFallback(context, pkg)
                    }
                } else {
                    AppLogger.error("Scanner: File missing or too small for $pkg")
                    saveFallback(context, pkg)
                }

            } catch (e: Exception) {
                AppLogger.error("Scanner: Critical error probing $pkg", e)
                saveFallback(context, pkg)
            } finally {
                mainHandler.post { onDone() }
            }
        }.start()
    }

    private fun readRate(file: File): Int {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                val bytesRead = fis.read(header)
                if (bytesRead < 44) return 0

                val buffer = ByteBuffer.wrap(header)
                buffer.order(ByteOrder.LITTLE_ENDIAN)

                // Offset 24 မှာ Hz ပါတယ်
                val sampleRate = buffer.getInt(24)
                sampleRate
            }
        } catch (e: Exception) {
            AppLogger.error("Scanner: Error parsing WAV header", e)
            0 
        }
    }

    private fun saveRate(context: Context, pkg: String, rate: Int) {
        val finalRate = if (rate < 8000) 16000 else rate
        // *** အောင်မြင်ကြောင်း Log ***
        AppLogger.log("Scanner: [SUCCESS] $pkg -> Detected Rate: $finalRate Hz")
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", finalRate).apply()
    }

    private fun saveFallback(context: Context, pkg: String) {
        val defaultRate = 22050
        // *** မအောင်မြင်ကြောင်း (Fallback) Log ***
        AppLogger.log("Scanner: [FALLBACK] $pkg -> Using Standard $defaultRate Hz")
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", defaultRate).apply()
    }
}

