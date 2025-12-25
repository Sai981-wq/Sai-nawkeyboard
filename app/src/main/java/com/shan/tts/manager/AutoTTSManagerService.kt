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
import java.nio.ByteOrder
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
    private val PREF_NAME = "TTS_CONFIG"
    
    private val mIsStopped = AtomicBoolean(false)
    private val executorService = Executors.newCachedThreadPool()
    private val currentReadFd = AtomicReference<ParcelFileDescriptor?>(null)

    private val SYSTEM_OUTPUT_RATE = 24000
    private val BUFFER_SIZE = 16384 

    private val outBufferLocal = object : ThreadLocal<ByteBuffer>() {
        override fun initialValue(): ByteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE * 4).order(ByteOrder.LITTLE_ENDIAN)
    }

    private val outBufferArrayLocal = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray = ByteArray(BUFFER_SIZE * 4)
    }

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defaultEngine = getDefaultEngineFallback()

            shanPkgName = resolveEngine("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defaultEngine)
            initEngine(shanPkgName, Locale("shn", "MM")) { shanEngine = it }

            burmesePkgName = resolveEngine("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defaultEngine)
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = resolveEngine("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defaultEngine)
            initEngine(englishPkgName, Locale.US) { englishEngine = it }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getDefaultEngineFallback(): String {
        return try {
            val tts = TextToSpeech(this, null)
            val pkg = tts.defaultEngine
            tts.shutdown()
            pkg ?: "com.google.android.tts"
        } catch (e: Exception) { "com.google.android.tts" }
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
        try {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(this, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.let { engine ->
                        onReady(engine)
                        try { engine.language = locale } catch (e: Exception) {}
                    }
                }
            }, pkg)
        } catch (e: Exception) { }
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE

    override fun onStop() {
        mIsStopped.set(true)
        val fd = currentReadFd.getAndSet(null)
        try { fd?.close() } catch (e: IOException) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        try {
            if (request == null || callback == null) return

            mIsStopped.set(false)
            val text = request.charSequenceText.toString()
            val sysRate = request.speechRate / 100f
            val sysPitch = request.pitch / 100f
            
            val chunks = TTSUtils.splitHelper(text)

            callback.start(SYSTEM_OUTPUT_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)

            for (chunk in chunks) {
                if (mIsStopped.get()) break

                val langCode = when (chunk.lang) {
                    "SHAN" -> "shn"
                    "MYANMAR" -> "my"
                    else -> "eng"
                }
                
                val engineData = getEngineDataForLang(langCode)
                val targetEngine = engineData.engine ?: englishEngine
                val targetPkg = engineData.pkgName

                if (targetEngine != null) {
                    synchronized(targetEngine) {
                        targetEngine.setSpeechRate(sysRate)
                        targetEngine.setPitch(sysPitch)
                        processSafeStream(targetEngine, targetPkg, chunk.text, callback)
                    }
                }
            }
            
            if (!mIsStopped.get()) {
                callback.done()
            }
        } catch (e: Throwable) {
            callback?.error() 
        }
    }

    private fun processSafeStream(engine: TextToSpeech, pkgName: String, text: String, callback: SynthesisCallback) {
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd.set(readFd)

        try {
            val engineInputRate = prefs.getInt("RATE_$pkgName", 24000)
            
            if (engineInputRate == SYSTEM_OUTPUT_RATE) {
                 val readerTask: Future<*> = executorService.submit {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    val buffer = ByteArray(BUFFER_SIZE)
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                            while (!mIsStopped.get()) {
                                val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }
                                if (bytesRead == -1) break
                                if (bytesRead > 0) sendAudioToSystem(buffer, bytesRead, callback)
                            }
                        }
                    } catch (e: Exception) {}
                }
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                val result = engine.synthesizeToFile(text, params, writeFd, uuid)
                try { writeFd.close() } catch (e: IOException) { }
                if (result == TextToSpeech.SUCCESS) try { readerTask.get() } catch (e: Exception) { }
                else readerTask.cancel(true)
                return
            }

            val audioProcessor = try {
                AudioProcessor(engineInputRate, 1)
            } catch (e: Throwable) {
                try { writeFd.close() } catch (ex: IOException) {}
                try { readFd.close() } catch (ex: IOException) {}
                currentReadFd.set(null)
                return 
            }

            val readerTask: Future<*> = executorService.submit {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                
                val localInBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                val localOutBuffer = outBufferLocal.get()!!
                val localByteArray = outBufferArrayLocal.get()!!

                try {
                    ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                        var isFirstChunk = true
                        
                        while (!mIsStopped.get()) {
                            val targetReadSize = if (isFirstChunk) 512 else BUFFER_SIZE
                            val tempBuffer = ByteArray(targetReadSize)
                            
                            val bytesRead = try { fis.read(tempBuffer) } catch (e: IOException) { -1 }
                            if (bytesRead == -1) break

                            if (bytesRead > 0) {
                                isFirstChunk = false

                                localInBuffer.clear()
                                localInBuffer.put(tempBuffer, 0, bytesRead)
                                localInBuffer.flip()

                                var processed = audioProcessor.process(localInBuffer, bytesRead, localOutBuffer, localOutBuffer.capacity())
                                
                                while (processed > 0 && !mIsStopped.get()) {
                                    localOutBuffer.get(localByteArray, 0, processed)
                                    sendAudioToSystem(localByteArray, processed, callback)
                                    localOutBuffer.clear()
                                    processed = audioProcessor.process(localInBuffer, 0, localOutBuffer, localOutBuffer.capacity())
                                }
                            }
                        }
                        
                        if (!mIsStopped.get()) {
                            audioProcessor.flushQueue()
                            localOutBuffer.clear()
                            var processed = audioProcessor.process(localInBuffer, 0, localOutBuffer, localOutBuffer.capacity())
                            while (processed > 0 && !mIsStopped.get()) {
                                localOutBuffer.get(localByteArray, 0, processed)
                                sendAudioToSystem(localByteArray, processed, callback)
                                localOutBuffer.clear()
                                processed = audioProcessor.process(localInBuffer, 0, localOutBuffer, localOutBuffer.capacity())
                            }
                        }
                    }
                } catch (e: Exception) { 
                } finally {
                    audioProcessor.release()
                }
            }

            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)

            val result = engine.synthesizeToFile(text, params, writeFd, uuid)
            try { writeFd.close() } catch (e: IOException) { }

            if (result == TextToSpeech.SUCCESS) {
                try { readerTask.get() } catch (e: Exception) { }
            } else {
                readerTask.cancel(true)
                audioProcessor.release()
            }
        } catch (e: Exception) {
        } finally {
             currentReadFd.set(null)
        }
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
        EngineScanner.stop()
        executorService.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
    }
}

