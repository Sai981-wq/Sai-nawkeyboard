package com.shan.tts.manager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

object EngineScanner {

    private const val PREF_NAME = "TTS_CONFIG"
    private var scanJob: Job? = null

    fun scanAllEngines(context: Context, onComplete: () -> Unit) {
        if (scanJob?.isActive == true) return

        scanJob = CoroutineScope(Dispatchers.IO).launch {
            val appContext = context.applicationContext
            val intent = Intent("android.intent.action.TTS_SERVICE")
            val resolveInfos = appContext.packageManager.queryIntentServices(intent, 0)

            val blockedEngines = listOf(appContext.packageName, "com.vnspeak.autotts")
            val engines = resolveInfos.map { it.serviceInfo.packageName }
                .filter { !blockedEngines.contains(it) }
                .distinct()

            if (engines.isEmpty()) {
                finishScan(onComplete)
                return@launch
            }

            for (pkg in engines) {
                if (!isActive) break
                scanSingleEngine(appContext, pkg)
            }

            finishScan(onComplete)
        }
    }

    private suspend fun scanSingleEngine(context: Context, pkg: String) {
        var tts: TextToSpeech? = null
        try {
            tts = initTTS(context, pkg)
            
            if (tts != null) {
                val rate = probeEngine(context, tts, pkg)
                if (rate > 0) {
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
            try { tts?.shutdown() } catch (e: Exception) {}
        }
    }

    private suspend fun initTTS(context: Context, pkg: String): TextToSpeech? = withTimeoutOrNull(5000L) {
        suspendCancellableCoroutine { cont ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context, { status ->
                if (cont.isActive) {
                    if (status == TextToSpeech.SUCCESS) {
                        cont.resume(tts)
                    } else {
                        cont.resume(null)
                    }
                }
            }, pkg)
        }
    }

    private suspend fun probeEngine(context: Context, tts: TextToSpeech, pkg: String): Int = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "probe_$pkg.wav")
        val uuid = UUID.randomUUID().toString()
        
        try {
            if (tempFile.exists()) tempFile.delete()

            val lower = pkg.lowercase(Locale.ROOT)
            val (text, targetLocale) = when {
                lower.contains("shan") || lower.contains("shn") -> "မႂ်ႇသုင်ၶႃႈ" to Locale("shn", "MM")
                lower.contains("myanmar") || lower.contains("burmese") -> "မင်္ဂလာပါ" to Locale("my", "MM")
                else -> "Hello test" to Locale.US
            }
            
            try { tts.language = targetLocale } catch (e: Exception) { tts.language = Locale.US }

            val success = suspendCancellableCoroutine<Boolean> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (cont.isActive) cont.resume(true) }
                    override fun onError(id: String?) { if (cont.isActive) cont.resume(false) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?, errorCode: Int) { if (cont.isActive) cont.resume(false) }
                })

                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.1f)
                tts.synthesizeToFile(text, params, tempFile, uuid)
            }

            if (success) delay(150) 

            if (tempFile.length() > 44) {
                return@withContext readRate(tempFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { tempFile.delete() } catch (e: Exception) {}
        }
        return@withContext 0
    }

    private fun finishScan(onComplete: () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            onComplete()
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
        scanJob?.cancel()
    }
}

