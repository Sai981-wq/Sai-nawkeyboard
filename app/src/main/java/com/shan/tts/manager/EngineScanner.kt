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
            onComplete()
            return
        }

        mainHandler.post {
            scanRecursive(appContext, engines, 0, onComplete)
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
            val available = tts.isLanguageAvailable(targetLocale)
            if (available >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = targetLocale
            } else {
                tts.language = Locale.US
                text = "Hello fallback."
            }
        } catch (e: Exception) {}

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { latch.countDown() }
            override fun onDone(id: String?) { latch.countDown() }
        })

        Thread {
            try {
                if (tempFile.exists()) tempFile.delete()

                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)

                tts.synthesizeToFile(text, params, tempFile, uuid)

                val success = latch.await(6, TimeUnit.SECONDS)

                var retries = 0
                val MAX_RETRIES = 40
                while (tempFile.length() < 44 && retries < MAX_RETRIES) {
                    Thread.sleep(50)
                    retries++
                }

                if (tempFile.exists() && tempFile.length() > 44) {
                    val rate = readRate(tempFile)
                    if (rate in 8000..48000) {
                        saveRate(context, pkg, rate)
                    } else {
                        saveFallback(context, pkg)
                    }
                } else {
                    saveFallback(context, pkg)
                }

            } catch (e: Exception) {
                saveFallback(context, pkg)
            } finally {
                try { if (tempFile.exists()) tempFile.delete() } catch (e: Exception) {}
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
                buffer.getInt(24)
            }
        } catch (e: Exception) { 0 }
    }

    private fun saveRate(context: Context, pkg: String, rate: Int) {
        val finalRate = if (rate < 8000) 16000 else rate
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", finalRate).apply()
    }

    private fun saveFallback(context: Context, pkg: String) {
        val defaultRate = 22050
        context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", defaultRate).apply()
    }
}

