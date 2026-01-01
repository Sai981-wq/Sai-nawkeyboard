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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object EngineScanner {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)
    private const val PREF_NAME = "TTS_CONFIG"

    fun scanAllEngines(context: Context, onComplete: () -> Unit) {
        if (isRunning.getAndSet(true)) {
            AppLogger.log("Scan already running.")
            return
        }
        AppLogger.log("Starting Engine Scan...")

        scanExecutor.execute {
            val appContext = context.applicationContext
            val intent = Intent("android.intent.action.TTS_SERVICE")
            val resolveInfos = appContext.packageManager.queryIntentServices(intent, 0)

            val blockedEngines = listOf(appContext.packageName, "com.vnspeak.autotts")
            val engines = resolveInfos.map { it.serviceInfo.packageName }
                .filter { !blockedEngines.contains(it) }
                .distinct()

            AppLogger.log("Found Engines to scan: $engines")

            if (engines.isEmpty()) {
                finishScan(onComplete)
                return@execute
            }

            scanRecursive(appContext, engines, 0, onComplete)
        }
    }

    private fun finishScan(onComplete: () -> Unit) {
        AppLogger.log("Engine Scan Finished.")
        isRunning.set(false)
        mainHandler.post { onComplete() }
    }

    private fun scanRecursive(context: Context, engines: List<String>, index: Int, onComplete: () -> Unit) {
        if (index >= engines.size || !isRunning.get()) {
            finishScan(onComplete)
            return
        }

        val pkgName = engines[index]
        AppLogger.log("Scanning Engine: $pkgName")
        var tts: TextToSpeech? = null
        
        val nextStep = {
            tts?.shutdown()
            scanRecursive(context, engines, index + 1, onComplete)
        }

        try {
            val initLatch = CountDownLatch(1)
            var initialized = false
            
            tts = TextToSpeech(context, { status ->
                initialized = (status == TextToSpeech.SUCCESS)
                initLatch.countDown()
            }, pkgName)

            if (initLatch.await(5, TimeUnit.SECONDS) && initialized) {
                probeEngine(context, tts!!, pkgName, nextStep)
            } else {
                AppLogger.error("Failed to init $pkgName")
                saveFallback(context, pkgName)
                nextStep()
            }
        } catch (e: Exception) {
            AppLogger.error("Exception scanning $pkgName", e)
            saveFallback(context, pkgName)
            nextStep()
        }
    }

    private fun probeEngine(context: Context, tts: TextToSpeech, pkg: String, onDone: () -> Unit) {
        val tempFile = File(context.cacheDir, "probe_$pkg.wav")
        val uuid = UUID.randomUUID().toString()
        val synthesisLatch = CountDownLatch(1)

        val lower = pkg.lowercase(Locale.ROOT)
        val (text, targetLocale) = when {
            lower.contains("shan") || lower.contains("shn") -> "မႂ်ႇသုင်ၶႃႈ" to Locale("shn", "MM")
            lower.contains("myanmar") || lower.contains("burmese") -> "မင်္ဂလာပါ" to Locale("my", "MM")
            else -> "Hello test" to Locale.US
        }

        try {
            if (tts.isLanguageAvailable(targetLocale) >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = targetLocale
            } else {
                tts.language = Locale.US
            }
        } catch (e: Exception) {}

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { synthesisLatch.countDown() }
            override fun onDone(id: String?) { synthesisLatch.countDown() }
        })

        try { Thread.sleep(150) } catch (e: Exception) {}

        try {
            if (tempFile.exists()) tempFile.delete()
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.1f) 
            
            AppLogger.log("Probing $pkg...")
            tts.synthesizeToFile(text, params, tempFile, uuid)
            
            val finished = synthesisLatch.await(6, TimeUnit.SECONDS)
            if (!finished) AppLogger.error("Probe Timeout for $pkg")

            var retries = 0
            while (tempFile.length() < 44 && retries < 20) {
                Thread.sleep(50)
                retries++
            }

            if (tempFile.length() > 44) {
                val rate = readRate(tempFile)
                AppLogger.log("Result $pkg: Detected Rate = $rate")
                if (rate > 0) saveRate(context, pkg, rate) else saveFallback(context, pkg)
            } else {
                AppLogger.error("Probe failed for $pkg (Empty file)")
                saveFallback(context, pkg)
            }
        } catch (e: Exception) {
            AppLogger.error("Probe Exception $pkg", e)
            saveFallback(context, pkg)
        } finally {
            try { tempFile.delete() } catch (e: Exception) {}
            onDone()
        }
    }

    private fun readRate(file: File): Int {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) < 44) return 0
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val rate = buffer.getInt(24)
                if (rate in 8000..48000) rate else 0
            }
        } catch (e: Exception) { 0 }
    }

    private fun saveRate(context: Context, pkg: String, rate: Int) {
        val validRate = if (rate < 8000) 16000 else rate
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", validRate).apply()
    }

    private fun saveFallback(context: Context, pkg: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putInt("RATE_$pkg", 22050).apply()
    }

    fun stop() {
        isRunning.set(false)
        scanExecutor.shutdownNow()
    }
}

