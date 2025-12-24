package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""

    private lateinit var prefs: SharedPreferences
    
    private val mIsStopped = AtomicBoolean(false)
    private val executorService = Executors.newCachedThreadPool()
    private val currentReadFd = AtomicReference<ParcelFileDescriptor?>(null)

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

            val defaultEngine = try {
                val tts = TextToSpeech(this, null)
                val pkg = tts.defaultEngine
                tts.shutdown()
                pkg ?: "com.google.android.tts"
            } catch (e: Exception) { "com.google.android.tts" }

            shanPkgName = resolveEngine("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng", "org.himnario.espeak"), defaultEngine)
            initEngine(shanPkgName, Locale("shn", "MM")) { shanEngine = it }

            burmesePkgName = resolveEngine("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts", "com.samsung.SMT"), defaultEngine)
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = resolveEngine("pref_english_pkg", listOf("com.google.android.tts", "com.samsung.SMT", "es.codefactory.eloquencetts"), defaultEngine)
            initEngine(englishPkgName, Locale.US) { englishEngine = it }

            AudioProcessor.initSonic(24000, 1)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resolveEngine(prefKey: String, priorityList: List<String>, fallback: String): String {
        val userPref = prefs.getString(prefKey, "")
        if (!userPref.isNullOrEmpty() && isPackageInstalled(userPref)) return userPref
        for (pkg in priorityList) if (isPackageInstalled(pkg)) return pkg
        return fallback
    }

    private fun isPackageInstalled(pkgName: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkgName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) { false }
    }

    private fun initEngine(pkg: String, locale: Locale, onReady: (TextToSpeech) -> Unit) {
        if (pkg.isEmpty()) return
        var tts: TextToSpeech? = null
        try {
            tts = TextToSpeech(this, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        tts?.language = locale
                        onReady(tts!!)
                    } catch (e: Exception) { }
                }
            }, pkg)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onGetLanguage(): Array<String> {
        val locale = Locale.US
        return arrayOf(locale.language, locale.country, "")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val iso3 = try { Locale(lang ?: "eng").isO3Language } catch (e: Exception) { "eng" }
        return when (iso3) {
            "shn" -> if (shanEngine != null) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
            "mya", "bur" -> if (burmeseEngine != null) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
            "eng" -> if (englishEngine != null) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onStop() {
        mIsStopped.set(true)
        val fd = currentReadFd.getAndSet(null)
        try {
            fd?.close()
        } catch (e: IOException) { }
        
        AudioProcessor.flush()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        mIsStopped.set(false)
        
        val text = request.charSequenceText.toString()
        val sysRate = request.speechRate / 100f
        val sysPitch = request.pitch / 100f
        
        val chunks = TTSUtils.splitHelper(text)

        callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)

        for (chunk in chunks) {
            if (mIsStopped.get()) break

            val langCode = when (chunk.lang) {
                "SHAN" -> "shn"
                "MYANMAR" -> "my"
                else -> "eng"
            }
            
            val engineData = getEngineDataForLang(langCode)
            val targetEngine = engineData.engine ?: englishEngine

            if (targetEngine != null) {
                synchronized(targetEngine) {
                    targetEngine.setSpeechRate(sysRate)
                    targetEngine.setPitch(sysPitch)
                    processSafeStream(targetEngine, chunk.text, callback)
                }
            }
        }
        
        if (!mIsStopped.get()) {
            callback.done()
        }
    }

    private fun processSafeStream(engine: TextToSpeech, text: String, callback: SynthesisCallback) {
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            return
        }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd.set(readFd)

        val readerTask: Future<*> = executorService.submit {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            
            val buffer = ByteArray(4096)
            val localInBuffer = ByteBuffer.allocateDirect(4096)
            val localOutBuffer = outBufferLocal.get()!!
            val localByteArray = outBufferArrayLocal.get()!!

            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (!mIsStopped.get()) {
                        val bytesRead = try {
                            fis.read(buffer)
                        } catch (e: IOException) { -1 }

                        if (bytesRead == -1) break

                        if (bytesRead > 0) {
                            localInBuffer.clear()
                            if (localInBuffer.capacity() < bytesRead) {
                                // Implicit guard
                            }
                            localInBuffer.put(buffer, 0, bytesRead)
                            localInBuffer.flip()

                            var processed = AudioProcessor.processAudio(localInBuffer, bytesRead, localOutBuffer, localOutBuffer.capacity())
                            
                            if (processed > 0) {
                                localOutBuffer.get(localByteArray, 0, processed)
                                sendAudioToSystem(localByteArray, processed, callback)
                            }

                            while (processed > 0 && !mIsStopped.get()) {
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
            } catch (e: Exception) { }
        }

        val params = Bundle()
        val result = engine.synthesizeToFile(text, params, writeFd, uuid)
        
        try {
            writeFd.close()
        } catch (e: IOException) { }

        if (result == TextToSpeech.SUCCESS) {
            try {
                readerTask.get()
            } catch (e: Exception) { }
        } else {
            readerTask.cancel(true)
        }
        
        currentReadFd.set(null)
    }

    private fun sendAudioToSystem(buffer: ByteArray, length: Int, callback: SynthesisCallback) {
        if (mIsStopped.get()) return
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        while (offset < length && !mIsStopped.get()) {
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

    override fun onDestroy() {
        super.onDestroy()
        mIsStopped.set(true)
        executorService.shutdownNow()
        
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        
        AudioProcessor.stop()
    }
}

