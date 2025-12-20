package com.shan.tts.manager

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object EngineScanner {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun scanAllEngines(context: Context, onComplete: () -> Unit) {
        val intent = android.content.Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos = context.packageManager.queryIntentServices(intent, 0)
        val engines = resolveInfos.map { it.serviceInfo.packageName }.filter { it != context.packageName }

        if (engines.isEmpty()) {
            onComplete()
            return
        }

        // Main Thread ပေါ်မှာ စမယ်
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

        // နောက်တစ်ခုမကူးခင် လက်ရှိဟာကို သေချာရှင်းထုတ်မယ်
        val onNext = {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (e: Exception) {}
            
            // Stack Overflow မဖြစ်အောင် Handler နဲ့ ခွဲထုတ်လိုက်တာ ပိုကောင်းပါတယ်
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
        val tempFile = File(context.cacheDir, "probe.wav")
        val uuid = "probe"
        
        // Double Call မဖြစ်အောင် Lock ခတ်မယ်
        val isFinished = AtomicBoolean(false)
        
        // ပြီးဆုံးကြောင်း သတ်မှတ်တဲ့ Function (တစ်ခါပဲ အလုပ်လုပ်မယ်)
        fun finishOnce() {
            if (isFinished.compareAndSet(false, true)) {
                onDone()
            }
        }

        val lower = pkg.lowercase(Locale.ROOT)
        // For Burmese engines, using Myanmar char might help initialization
        val text = if (lower.contains("myanmar") || lower.contains("saomai") || lower.contains("ttsm")) "က" else "a"

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { 
                saveFallback(context, pkg)
                finishOnce() 
            }
            override fun onDone(id: String?) { 
                // onDone ခေါ်တိုင်း ပြီးပြီလို့ မသတ်မှတ်သေးဘူး (File Write မပြီးခင် ဖြစ်တတ်လို့)
                // Thread ကပဲ အဓိက စစ်ပေးလိမ့်မယ်
            }
        })

        try {
            if (tempFile.exists()) tempFile.delete()
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)

            tts.synthesizeToFile(text, params, tempFile, uuid)

            // Watcher Thread
            Thread {
                var wait = 0
                // 2.5 seconds timeout (နည်းနည်းတိုးထားတယ်)
                while (!isFinished.get()) {
                    if (tempFile.exists() && tempFile.length() > 44) {
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
        // eSpeak/Flite လိုဟာတွေက Rate နိမ့်တတ်လို့ 8000 အောက်ဆို 16000 ပြောင်းတာ မှန်ပါတယ်
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
            lower.contains("samsung") -> 22050
            lower.contains("vocalizer") -> 22050
            else -> 16000
        }
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", rate).apply()
    }
}

