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
        AppLogger.log("Scanner: [STEP 1] Starting Discovery using ApplicationContext.")
        
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
        AppLogger.log("Scanner: [STEP 2] Initializing -> $pkgName (${index + 1}/${engines.size})")
        
        var tts: TextToSpeech? = null

        val onNext = {
            try {
                tts?.stop()
                tts?.shutdown()
                AppLogger.log("Scanner: TTS Shutdown for $pkgName")
            } catch (e: Exception) {}
            
            mainHandler.post {
                scanRecursive(context, engines, index + 1, onComplete)
            }
        }

        try {
            tts = TextToSpeech(context, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    AppLogger.log("Scanner: Init OK ($pkgName). Starting probe logic...")
                    probeEngine(context, tts!!, pkgName) { onNext() }
                } else {
                    AppLogger.error("Scanner: Init FAILED ($pkgName) Status Code: $status")
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

        // *** ဘာစာသား ဖတ်ခိုင်းလဲဆိုတာ အတိအကျ စစ်မယ် ***
        if (lower.contains("shan") || lower.contains("shn")) {
            text = "မႂ်ႇသုင်ၶႃႈ ၼႆႉပဵၼ် ၶိူင်ႈသဵင်တႆးဢေႃႈ" // ရှမ်းစာ ပိုရှည်ရှည်ထည့်မယ်
            targetLocale = Locale("shn", "MM")
        } else if (lower.contains("myanmar") || lower.contains("burmese") || lower.contains("saomai") || lower.contains("ttsm")) {
            text = "မင်္ဂလာပါခင်ဗျာ၊ ဒါကတော့ မြန်မာစာ အသံထွက်ကို စမ်းသပ်နေခြင်း ဖြစ်ပါတယ်" // မြန်မာစာ ရှည်ရှည်
            targetLocale = Locale("my", "MM")
        }
        
        try {
            val res = tts.setLanguage(targetLocale)
            val langStatus = when(res) {
                TextToSpeech.LANG_AVAILABLE -> "Available"
                TextToSpeech.LANG_MISSING_DATA -> "Missing Data (Voice not installed!)"
                TextToSpeech.LANG_NOT_SUPPORTED -> "Not Supported"
                else -> "Status($res)"
            }
            AppLogger.log("Scanner: SetLanguage ($targetLocale) -> $langStatus for $pkg")
        } catch (e: Exception) {
            AppLogger.error("Scanner: SetLanguage crashed for $pkg")
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                AppLogger.log("Scanner: Callback -> onStart ($pkg)")
            }
            override fun onError(id: String?) { 
                AppLogger.error("Scanner: Callback -> onError ($pkg)")
                latch.countDown()
            }
            override fun onDone(id: String?) { 
                AppLogger.log("Scanner: Callback -> onDone ($pkg)")
                latch.countDown()
            }
        })

        Thread {
            try {
                if (tempFile.exists()) tempFile.delete()

                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)
                
                AppLogger.log("Scanner: [STEP 3] Synthesizing text: '$text'")
                val result = tts.synthesizeToFile(text, params, tempFile, uuid)
                
                if (result != TextToSpeech.SUCCESS) {
                    AppLogger.error("Scanner: synthesizeToFile returned ERROR code.")
                }

                // Timeout 6 Seconds
                val success = latch.await(6, TimeUnit.SECONDS)
                if (!success) AppLogger.error("Scanner: Timeout waiting for onDone!")

                // File Flush Wait Logic
                var waitCount = 0
                while (tempFile.length() <= 44 && waitCount < 40) {
                    Thread.sleep(50)
                    waitCount++
                }

                val fileSize = tempFile.length()
                AppLogger.log("Scanner: [STEP 4] File Check. Final Size: $fileSize bytes")

                // *** အသံထွက်/မထွက် စစ်ဆေးခြင်း ***
                if (fileSize <= 0) {
                    AppLogger.error("Scanner: RESULT -> NO FILE CREATED. Engine failed completely.")
                    saveFallback(context, pkg)
                } else if (fileSize <= 44) {
                    // 44 Bytes ဆိုတာ Header ပဲရှိပြီး အသံဒေတာ မပါပါ (Silence)
                    AppLogger.error("Scanner: RESULT -> SILENCE DETECTED (Size=44). Engine ran but didn't speak the text.")
                    saveFallback(context, pkg)
                } else {
                    // 44 ထက်များရင် အသံပါတယ်
                    AppLogger.log("Scanner: RESULT -> AUDIO DATA FOUND (Size=$fileSize). Parsing Hz...")
                    val rate = readRate(tempFile)
                    if (rate > 0) {
                        saveRate(context, pkg, rate)
                    } else {
                        AppLogger.error("Scanner: File exists but Header parsing failed.")
                        saveFallback(context, pkg)
                    }
                }

            } catch (e: Exception) {
                AppLogger.error("Scanner: CRITICAL EXCEPTION in probe thread ($pkg)", e)
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

                // Debug: Header Bytes Check
                val b1 = header[24]; val b2 = header[25]; val b3 = header[26]; val b4 = header[27]
                val sampleRate = buffer.getInt(24)
                
                AppLogger.log("Scanner: [STEP 5] Header Bytes[24-27]=[$b1, $b2, $b3, $b4] -> Int: $sampleRate")
                sampleRate
            }
        } catch (e: Exception) {
            AppLogger.error("Scanner: Error parsing WAV header", e)
            0 
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

