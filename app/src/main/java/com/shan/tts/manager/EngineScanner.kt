package com.shan.tts.manager

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile

object EngineScanner {

    fun scanAllEngines(context: Context, onProgress: (String) -> Unit, onComplete: () -> Unit) {
        AppLogger.log("=== STARTING FULL SYSTEM SCAN ===")
        
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos = context.packageManager.queryIntentServices(intent, 0)
        
        // ကိုယ့် App ကလွဲရင် ကျန်တာအကုန်ယူမယ်
        val engines = resolveInfos.map { it.serviceInfo.packageName }
            .filter { it != context.packageName }
        
        AppLogger.log("Detected ${engines.size} engines: $engines")

        if (engines.isEmpty()) {
            onComplete()
            return
        }

        // တစ်ခုပြီးမှ တစ်ခု စစ်ဆေးမည့် Recursive Function
        scanRecursive(context, engines, 0, onProgress, onComplete)
    }

    private fun scanRecursive(context: Context, engines: List<String>, index: Int, onProgress: (String) -> Unit, onComplete: () -> Unit) {
        if (index >= engines.size) {
            AppLogger.log("=== SCAN COMPLETED SUCCESSFULLY ===")
            onComplete()
            return
        }

        val pkgName = engines[index]
        val displayMsg = "Analyzing Engine (${index + 1}/${engines.size}):\n$pkgName"
        
        onProgress(displayMsg)
        AppLogger.log("Scanning: $pkgName")

        var tts: TextToSpeech? = null
        
        // နောက်တစ်ခု ဆက်သွားမည့် Function
        val onNext = {
            try { tts?.shutdown() } catch(e: Exception){}
            // နည်းနည်းအနားပေးမယ် (Crash မဖြစ်အောင်)
            try { Thread.sleep(200) } catch(e:Exception){}
            scanRecursive(context, engines, index + 1, onProgress, onComplete)
        }

        try {
            tts = TextToSpeech(context, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    detectAndSaveRate(context, tts!!, pkgName)
                    onNext()
                } else {
                    AppLogger.error("Failed to bind: $pkgName")
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
        
        try {
            if (tempFile.exists()) tempFile.delete()
            
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {}
                override fun onError(id: String?) {}
            })

            // Hello လို့ အသံထွက်ခိုင်းပြီး Rate စစ်မယ်
            val result = tts.synthesizeToFile("Hello", null, tempFile, uuid)
            
            if (result == TextToSpeech.SUCCESS) {
                var waits = 0
                // 3 စက္ကန့်လောက်ထိ စောင့်ကြည့်မယ်
                while (!tempFile.exists() || tempFile.length() < 44) {
                    try { Thread.sleep(50) } catch (e: Exception) {}
                    waits++
                    if (waits > 60) { 
                        AppLogger.log("Timeout: No audio from $pkgName")
                        saveFallback(context, pkgName)
                        return
                    }
                }
                
                val rate = readWavSampleRate(tempFile)
                if (rate > 0) {
                    AppLogger.log("SUCCESS: $pkgName -> $rate Hz")
                    saveRate(context, pkgName, rate)
                } else {
                    AppLogger.log("FAILED: 0Hz from $pkgName")
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
        // 4000Hz အောက်ဆိုရင် အမှားလို့သတ်မှတ်ပြီး 24000 ပေးမယ်
        val validRate = if (rate < 4000) 24000 else rate
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit()
            .putInt("RATE_$pkg", validRate)
            .apply()
    }

    private fun saveFallback(context: Context, pkg: String) {
        var rate = 24000 
        val lower = pkg.toLowerCase()
        if (lower.contains("espeak") || lower.contains("shan")) rate = 22050
        if (lower.contains("eloquence")) rate = 11025
        if (lower.contains("nirenr")) rate = 16000 
        
        AppLogger.log("Fallback: $pkg -> $rate Hz")
        saveRate(context, pkg, rate)
    }
}

