package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""

    private lateinit var prefs: SharedPreferences
    private lateinit var configPrefs: SharedPreferences

    private var lastConfiguredRate: Int = -1
    
    @Volatile private var mIsStopped = false
    private var currentReadFd: ParcelFileDescriptor? = null
    private val processLock = Any()

    private val FIXED_OUTPUT_HZ = 24000

    private val inBufferLocal = object : ThreadLocal<ByteBuffer>() {
        override fun initialValue(): ByteBuffer = ByteBuffer.allocateDirect(4096)
    }

    private val outBufferLocal = object : ThreadLocal<ByteBuffer>() {
        override fun initialValue(): ByteBuffer = ByteBuffer.allocateDirect(8192)
    }

    private val outBufferArrayLocal = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray = ByteArray(8192)
    }

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)

            val defaultEngine = try {
                val tts = TextToSpeech(this, null)
                val pkg = tts.defaultEngine
                tts.shutdown()
                pkg ?: "com.google.android.tts"
            } catch (e: Exception) { "com.google.android.tts" }

            shanPkgName = resolveEngine(
                "pref_shan_pkg",
                listOf("com.shan.tts", "com.espeak.ng", "org.himnario.espeak"),
                defaultEngine
            )
            initEngine(shanPkgName, Locale("shn", "MM")) { shanEngine = it }

            burmesePkgName = resolveEngine(
                "pref_burmese_pkg",
                listOf("org.saomaicenter.myanmartts", "com.google.android.tts", "com.samsung.SMT"),
                defaultEngine
            )
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = resolveEngine(
                "pref_english_pkg",
                listOf("com.google.android.tts", "com.samsung.SMT", "es.codefactory.eloquencetts"),
                defaultEngine
            )
            initEngine(englishPkgName, Locale.US) { englishEngine = it }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resolveEngine(prefKey: String, priorityList: List<String>, fallback: String): String {
        val userPref = prefs.getString(prefKey, "")
        if (!userPref.isNullOrEmpty() && isPackageInstalled(userPref)) {
            return userPref
        }
        for (pkg in priorityList) {
            if (isPackageInstalled(pkg)) {
                return pkg
            }
        }
        return fallback
    }

    private fun isPackageInstalled(pkgName: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkgName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun initEngine(pkg: String, locale: Locale, onReady: (TextToSpeech) -> Unit) {
        if (pkg.isEmpty()) return
        var tts: TextToSpeech? = null
        try {
            tts = TextToSpeech(this, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = locale
                    onReady(tts!!)
                }
            }, pkg)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE

    override fun onStop() {
        synchronized(processLock) {
            mIsStopped = true
            interruptCurrentTask()
            AudioProcessor.stop()
            lastConfiguredRate = -1
        }
    }

    private fun interruptCurrentTask() {
        try {
            currentReadFd?.close()
        } catch (e: Exception) { }
        currentReadFd = null
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        synchronized(processLock) {
            mIsStopped = false
            interruptCurrentTask() 
        }

        val text = request.charSequenceText.toString()
        val sysRate = request.speechRate
        val sysPitch = request.pitch
        val chunks = TTSUtils.splitHelper(text)

        callback.start(FIXED_OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

        for (chunk in chunks) {
            if (mIsStopped) break

            val langCode = when(chunk.lang) {
                "SHAN" -> "shn"
                "MYANMAR" -> "my"
                else -> "eng"
            }

            val engineData = getEngineDataForLang(langCode)
            val targetEngine = engineData.engine ?: englishEngine

            if (targetEngine != null) {
                val finalRate = configPrefs.getInt("RATE_${engineData.pkgName}", 22050)

                if (finalRate != lastConfiguredRate) {
                    AudioProcessor.initSonic(finalRate, 1)
                    lastConfiguredRate = finalRate
                }

                val rateFloat = sysRate / 100f
                val pitchFloat = sysPitch / 100f
                targetEngine.setSpeechRate(rateFloat)
                targetEngine.setPitch(pitchFloat)

                val volume = getVolumeCorrection(engineData.pkgName)
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)

                processAudioChunkInstant(targetEngine, params, chunk.text, callback, UUID.randomUUID().toString())
            }
        }
        
        callback.done()
    }

    private fun processAudioChunkInstant(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        synchronized(processLock) {
            currentReadFd = readFd
        }

        try {
            val isDone = AtomicBoolean(false)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { 
                    isDone.set(true) 
                }
                override fun onError(utteranceId: String?) { 
                    isDone.set(true) 
                }
            })

            engine.synthesizeToFile(text, params, writeFd, uuid)
            writeFd.close() 

            val localInBuffer = inBufferLocal.get()!!
            val localOutBuffer = outBufferLocal.get()!!
            val localByteArray = outBufferArrayLocal.get()!!

            ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                val fc = fis.channel
                while (!mIsStopped) {
                    localInBuffer.clear()
                    
                    val bytesRead = try {
                        fc.read(localInBuffer)
                    } catch (e: IOException) {
                        break 
                    }
                    
                    if (bytesRead == -1) break

                    if (bytesRead > 0) {
                        localInBuffer.flip()
                        localOutBuffer.clear()

                        var processed = AudioProcessor.processAudio(localInBuffer, bytesRead, localOutBuffer, localOutBuffer.capacity())
                        
                        if (processed > 0) {
                            localOutBuffer.get(localByteArray, 0, processed)
                            sendAudioToSystem(localByteArray, processed, callback)
                        }
                        
                        while (processed > 0 && !mIsStopped) {
                            localOutBuffer.clear()
                            processed = AudioProcessor.processAudio(localInBuffer, 0, localOutBuffer, localOutBuffer.capacity())
                            if (processed > 0) {
                                localOutBuffer.get(localByteArray, 0, processed)
                                sendAudioToSystem(localByteArray, processed, callback)
                            }
                        }
                    }
                }
            }
            AudioProcessor.flush()
            
        } catch (e: Exception) {
            // Broken pipe or interrupted
        } finally {
            synchronized(processLock) {
                if (currentReadFd == readFd) {
                    currentReadFd = null
                }
            }
            try { readFd.close() } catch (e: Exception) {}
            engine.setOnUtteranceProgressListener(null)
        }
    }

    private fun sendAudioToSystem(buffer: ByteArray, length: Int, callback: SynthesisCallback) {
        if (mIsStopped) return
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        while (offset < length && !mIsStopped) {
            val chunk = min(length - offset, maxBufferSize)
            callback.audioAvailable(buffer, offset, chunk)
            offset += chunk
        }
    }

    data class EngineData(val engine: TextToSpeech?, val pkgName: String)
    
    private fun getEngineDataForLang(lang: String): EngineData {
        return when (lang) {
            "SHAN", "shn" -> EngineData(if (shanEngine != null) shanEngine else englishEngine, shanPkgName)
            "MYANMAR", "my", "mya", "bur" -> EngineData(if (burmeseEngine != null) burmeseEngine else englishEngine, burmesePkgName)
            else -> EngineData(englishEngine, englishPkgName)
        }
    }

    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return if (l.contains("espeak") || l.contains("shan") || l.contains("myanmar") || l.contains("saomai")) 1.0f else 0.8f
    }

    override fun onDestroy() {
        super.onDestroy()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        AudioProcessor.stop()
    }
}

