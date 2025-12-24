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
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.*
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

    private val mIsStopped = AtomicBoolean(false)
    private val synthesisExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    private val FIXED_OUTPUT_HZ = 24000

    private val inputBufferPool = object : ThreadLocal<ByteBuffer>() {
        override fun initialValue(): ByteBuffer = ByteBuffer.allocateDirect(4096)
    }
    private val outputBufferPool = object : ThreadLocal<ByteBuffer>() {
        override fun initialValue(): ByteBuffer = ByteBuffer.allocateDirect(8192)
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

            shanPkgName = resolveEngine("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defaultEngine)
            initEngine(shanPkgName, Locale("shn", "MM")) { shanEngine = it }

            burmesePkgName = resolveEngine("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defaultEngine)
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = resolveEngine("pref_english_pkg", listOf("com.google.android.tts", "com.samsung.SMT"), defaultEngine)
            initEngine(englishPkgName, Locale.US) { englishEngine = it }

        } catch (e: Exception) { e.printStackTrace() }
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
                    try { tts?.language = locale } catch (e: Exception) {}
                    onReady(tts!!)
                }
            }, pkg)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onStop() {
        mIsStopped.set(true)
        AudioProcessor.flush()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        mIsStopped.set(false)
        val text = request.charSequenceText.toString()
        val chunks = TTSUtils.splitHelper(text)

        callback.start(FIXED_OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

        val pendingTasks = ArrayList<Future<*>>()
        for (chunk in chunks) {
            val task = Callable {
                synthesizeAndStream(chunk, request, callback)
            }
            pendingTasks.add(synthesisExecutor.submit(task))
        }

        try {
            for (future in pendingTasks) {
                if (mIsStopped.get()) {
                    future.cancel(true)
                    break
                }
                future.get() 
            }
        } catch (e: Exception) { e.printStackTrace() }

        callback.done()
    }

    private fun synthesizeAndStream(chunk: LangChunk, request: SynthesisRequest, callback: SynthesisCallback) {
        if (mIsStopped.get()) return

        val engineData = getEngineDataForLang(chunk.lang)
        val targetEngine = engineData.engine ?: englishEngine ?: return

        val params = Bundle()
        val vol = if (engineData.pkgName.lowercase().contains("espeak")) 1.0f else 0.8f
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol)

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]

            synchronized(targetEngine) {
                targetEngine.synthesizeToFile(chunk.text, params, writeFd, UUID.randomUUID().toString())
            }
            writeFd.close()

            ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                val engineHz = configPrefs.getInt("RATE_${engineData.pkgName}", 22050)
                AudioProcessor.initSonic(engineHz, 1)

                val buffer = ByteArray(4096)
                val sonicInBuffer = inputBufferPool.get()!!
                val sonicOutBuffer = outputBufferPool.get()!!
                val sonicOutArray = ByteArray(8192)

                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    if (mIsStopped.get()) break
                    
                    sonicInBuffer.clear()
                    sonicInBuffer.put(buffer, 0, bytesRead)
                    sonicInBuffer.flip()

                    sonicOutBuffer.clear()
                    val processedBytes = AudioProcessor.processAudio(
                        sonicInBuffer, bytesRead, sonicOutBuffer, sonicOutBuffer.capacity()
                    )

                    if (processedBytes > 0) {
                        sonicOutBuffer.get(sonicOutArray, 0, processedBytes)
                        sendToCallback(sonicOutArray, processedBytes, callback)
                    }
                }
                
                flushRemainingAudio(callback)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun sendToCallback(data: ByteArray, length: Int, callback: SynthesisCallback) {
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        while (offset < length && !mIsStopped.get()) {
            val chunkLen = min(length - offset, maxBufferSize)
            val status = callback.audioAvailable(data, offset, chunkLen)
            if (status == TextToSpeech.ERROR) {
                mIsStopped.set(true)
                break
            }
            offset += chunkLen
        }
    }

    private fun flushRemainingAudio(callback: SynthesisCallback) {
        val sonicOutBuffer = outputBufferPool.get()!!
        val sonicOutArray = ByteArray(8192)
        sonicOutBuffer.clear()
        
        val remaining = AudioProcessor.flushStream(sonicOutBuffer, sonicOutBuffer.capacity())
        if (remaining > 0) {
            sonicOutBuffer.get(sonicOutArray, 0, remaining)
            sendToCallback(sonicOutArray, remaining, callback)
        }
    }

    private fun getEngineDataForLang(lang: String): EngineData {
        return when (lang) {
            "SHAN" -> EngineData(shanEngine ?: burmeseEngine ?: englishEngine, shanPkgName)
            "MYANMAR" -> EngineData(burmeseEngine ?: englishEngine, burmesePkgName)
            else -> EngineData(englishEngine, englishPkgName)
        }
    }

    data class EngineData(val engine: TextToSpeech?, val pkgName: String)

    override fun onDestroy() {
        super.onDestroy()
        mIsStopped.set(true)
        synthesisExecutor.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        AudioProcessor.stop()
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
}

