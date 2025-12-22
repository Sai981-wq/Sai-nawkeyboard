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
        val appContext = context.applicationContext
        AppLogger.log("Scanner: [STEP 1] Starting Discovery")
        
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos = appContext.packageManager.queryIntentServices(intent, 0)
        
        val blockedEngines = listOf(
            appContext.packageName,
            "com.vnspeak.autotts"
        )

        val engines = resolveInfos.map { it.serviceInfo.packageName }
            .filter { pkgName -> !blockedEngines.contains(pkgName) }
            .distinct()

        if (engines.isEmpty()) {
            AppLogger.log("Scanner: No external TTS engines found. Stop.")
            onComplete()
            return
        }

        AppLogger.log("Scanner: Found ${engines.size} engines: $engines")
        mainHandler.post {
            scanRecursive(appContext, engines, 0, onComplete)
        }
    }

    private fun scanRecursive(context: Context, engines: List<String>, index: Int, onComplete: () -> Unit) {
        if (index >= engines.size) {
            AppLogger.log("Scanner: [DONE] All tasks completed.")
            onComplete()
            return
        }

        val pkgName = engines[index]
        AppLogger.log("------------------------------------------------")
        AppLogger.log("Scanner: [STEP 2] Initializing -> $pkgName")
        
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
                    AppLogger.error("Scanner: Init FAILED ($pkgName)")
                    saveFallback(context, pkgName)
                    onNext()
                }
            }, pkgName)
        } catch (e: Exception) {
            AppLogger.error("Scanner: Exception on Init ($pkgName)", e)
            saveFallback(context, pkgName)
            onNext()
        }
    }

    private fun probeEngine(context: Context, tts: TextToSpeech, pkg: String, onDone: () -> Unit) {
        val tempFile = File(context.cacheDir, "probe_$pkg.wav")
        val uuid = UUID.randomUUID().toString()
        val latch = CountDownLatch(1)

        val lower = pkg.lowercase(Locale.ROOT)
        var text = "Hello, testing frequency."
        var targetLocale = Locale.US
        var useSSML = false

        if (pkg == "org.saomaicenter.myanmartts") {
            text = "<speak xml:lang=\"my\">မင်္ဂလာပါခင်ဗျာ</speak>"
            targetLocale = Locale("my", "MM")
            useSSML = true
        } 
        else if (lower.contains("shan") || lower.contains("shn")) {
            text = "မႂ်ႇသုင်ၶႃႈ"
            targetLocale = Locale("shn", "MM")
        } 
        else if (lower.contains("myanmar") || lower.contains("burmese") || lower.contains("ttsm")) {
            text = "<speak xml:lang=\"my\">မင်္ဂလာပါခင်ဗျာ</speak>"
            targetLocale = Locale("my", "MM")
            useSSML = true
        }
        
        try { tts.setLanguage(targetLocale) } catch (e: Exception) {}

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { 
                AppLogger.error("Scanner: onError Callback ($pkg)")
                latch.countDown()
            }
            override fun onDone(id: String?) { 
                latch.countDown()
            }
        })

        Thread {
            try {
                if (tempFile.exists()) tempFile.delete()

                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)
                
                val logText = if (useSSML) "SSML" else "Raw"
                AppLogger.log("Scanner: [STEP 3] Synthesizing... ($logText)")
                
                tts.synthesizeToFile(text, params, tempFile, uuid)

                val success = latch.await(6, TimeUnit.SECONDS)
                if (!success) AppLogger.error("Scanner: Timeout waiting for onDone!")

                var waitCount = 0
                while (tempFile.length() <= 44 && waitCount < 40) {
                    Thread.sleep(50)
                    waitCount++
                }

                val fileSize = tempFile.length()

                if (fileSize > 44) {
                    val (rate, dataSizeFromHeader) = readHeaderInfo(tempFile)
                    
                    val expectedFileSize = dataSizeFromHeader + 44
                    val diff = kotlin.math.abs(fileSize - expectedFileSize)
                    if (diff > 10) {
                        AppLogger.log("Scanner: [INFO] Header Mismatch detected (Diff: $diff).")
                    } else {
                        AppLogger.log("Scanner: Header Integrity OK.")
                    }

                    if (rate > 0) {
                        saveRate(context, pkg, rate)
                    } else {
                        AppLogger.error("Scanner: Header parsing failed (Hz=0).")
                        saveFallback(context, pkg)
                    }
                } else {
                    AppLogger.error("Scanner: RESULT -> SILENCE or NO FILE (Size=$fileSize).")
                    saveFallback(context, pkg)
                }

            } catch (e: Exception) {
                AppLogger.error("Scanner: CRITICAL ERROR ($pkg)", e)
                saveFallback(context, pkg)
            } finally {
                mainHandler.post { onDone() }
            }
        }.start()
    }

    private fun readHeaderInfo(file: File): Pair<Int, Int> {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                val bytesRead = fis.read(header)
                if (bytesRead < 44) return Pair(0, 0)

                val buffer = ByteBuffer.wrap(header)
                buffer.order(ByteOrder.LITTLE_ENDIAN)

                val sampleRate = buffer.getInt(24)
                val dataSize = buffer.getInt(40)
                
                AppLogger.log("Scanner: [STEP 5] Header Analysis -> Rate: $sampleRate, Declared Data: $dataSize")
                Pair(sampleRate, dataSize)
            }
        } catch (e: Exception) {
            AppLogger.error("Scanner: Error parsing WAV header", e)
            Pair(0, 0)
        }
    }

    private fun saveRate(context: Context, pkg: String, rate: Int) {
        val finalRate = if (rate < 8000) 16000 else rate
        
        AppLogger.log("Scanner: [SUCCESS] $pkg -> Final Hz: $finalRate")
        
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", finalRate).apply()
    }

    private fun saveFallback(context: Context, pkg: String) {
        val defaultRate = 22050
        AppLogger.log("Scanner: [FALLBACK] $pkg -> Using $defaultRate")

        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", defaultRate).apply()
    }
}

