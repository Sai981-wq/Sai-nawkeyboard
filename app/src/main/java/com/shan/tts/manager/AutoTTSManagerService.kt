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
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    
    private val currentReadFd = AtomicReference<ParcelFileDescriptor?>(null)
    private val currentWriteFd = AtomicReference<ParcelFileDescriptor?>(null)
    private var currentActiveEngine: TextToSpeech? = null

    private val BUFFER_SIZE = 65536 

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
        closeQuietly(currentWriteFd.getAndSet(null))
        closeQuietly(currentReadFd.getAndSet(null))
        try { currentActiveEngine?.stop() } catch (e: Exception) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        mIsStopped.set(false)

        val text = request.charSequenceText.toString()
        val sysRate = request.speechRate / 100f
        val sysPitch = request.pitch / 100f

        val chunks = TTSUtils.splitHelper(text)

        runBlocking {
            // ★ Hz Logic: Loop အပြင်မှာ အသေမဆောက်တော့ပါဘူး
            var sharedAudioProcessor: AudioProcessor? = null
            var currentProcessorRate = 0

            try {
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
                        currentActiveEngine = targetEngine
                        
                        // ★ (၁) Engine Scanner မှရသော Rate အမှန်ကို ယူမယ်
                        var realEngineRate = prefs.getInt("RATE_$targetPkg", 24000)
                        if (realEngineRate < 8000) realEngineRate = 24000

                        // ★ (၂) Rate မတူမှသာ Processor ကို အသစ်ပြန်ဆောက်မယ် (Crash မဖြစ်အောင် Reuse လုပ်ခြင်း)
                        if (sharedAudioProcessor == null || currentProcessorRate != realEngineRate) {
                            sharedAudioProcessor?.release() // အဟောင်းကိုဖျက်
                            try {
                                sharedAudioProcessor = AudioProcessor(realEngineRate, 1)
                                currentProcessorRate = realEngineRate
                            } catch (e: Throwable) {
                                sharedAudioProcessor = null
                            }
                        }

                        if (sharedAudioProcessor != null) {
                            // User စိတ်ကြိုက် Speed/Pitch ချိန်ပေးမယ်
                            sharedAudioProcessor.setSpeed(sysRate)
                            sharedAudioProcessor.setPitch(sysPitch)

                            targetEngine.setSpeechRate(sysRate)
                            targetEngine.setPitch(sysPitch)
                            
                            // ★ (၃) အရေးကြီးဆုံး: System ကိုလည်း Rate အမှန်အတိုင်း လက်ခံဖို့ ပြောရမယ်
                            // ဒါမှ Hz လွဲပြီး အသံပြာတာ/အသံကြီးတာ မဖြစ်မှာပါ
                            callback.start(realEngineRate, AudioFormat.ENCODING_PCM_16BIT, 1)
                            
                            processStreamBlocking(targetEngine, chunk.text, callback, sharedAudioProcessor!!)
                        }
                    }
                }
                if (!mIsStopped.get()) {
                    callback.done()
                }
            } finally {
                // Loop ပြီးမှ အားလုံးဖျက်မယ်
                sharedAudioProcessor?.release()
            }
        }
    }

    private suspend fun processStreamBlocking(
        engine: TextToSpeech, 
        text: String, 
        callback: SynthesisCallback,
        audioProcessor: AudioProcessor
    ) = coroutineScope {

        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return@coroutineScope }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd.set(readFd)
        currentWriteFd.set(writeFd)

        val synthesisLatch = CountDownLatch(1)

        val readerJob = launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            val inputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val outputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE * 2).order(ByteOrder.LITTLE_ENDIAN)
            val outputArray = ByteArray(BUFFER_SIZE * 2)

            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (isActive && !mIsStopped.get()) {
                        val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }

                        if (bytesRead == -1) break 

                        if (bytesRead > 0) {
                            inputBuffer.clear()
                            inputBuffer.put(buffer, 0, bytesRead)
                            inputBuffer.flip()

                            var processed = audioProcessor.process(inputBuffer, bytesRead, outputBuffer, outputBuffer.capacity())

                            while (processed > 0 && !mIsStopped.get()) {
                                outputBuffer.get(outputArray, 0, processed)
                                sendAudioToSystem(outputArray, processed, callback)
                                outputBuffer.clear()
                                processed = audioProcessor.process(inputBuffer, 0, outputBuffer, outputBuffer.capacity())
                            }
                        }
                    }
                    
                    if (!mIsStopped.get()) {
                        audioProcessor.flushQueue()
                        outputBuffer.clear()
                        var processed = audioProcessor.process(inputBuffer, 0, outputBuffer, outputBuffer.capacity())
                        while (processed > 0 && !mIsStopped.get()) {
                            outputBuffer.get(outputArray, 0, processed)
                            sendAudioToSystem(outputArray, processed, callback)
                            outputBuffer.clear()
                            processed = audioProcessor.process(inputBuffer, 0, outputBuffer, outputBuffer.capacity())
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        val writerJob = launch(Dispatchers.IO) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) { synthesisLatch.countDown() }
                override fun onError(id: String) { synthesisLatch.countDown() }
                override fun onError(id: String, errorCode: Int) { synthesisLatch.countDown() }
            })
            
            try {
                // Pipe Full ဖြစ်နေရင် စောင့်နေမယ်
                engine.synthesizeToFile(text, params, writeFd, uuid)
            } catch (e: Exception) {
            } finally {
                // ★ Deadlock Fix: ရေးပြီးတာနဲ့ ချက်ချင်းပိတ်မယ်
                closeQuietly(writeFd)
                currentWriteFd.set(null)
            }
        }

        try {
            joinAll(readerJob, writerJob)
        } finally {
            closeQuietly(readFd)
            closeQuietly(writeFd)
            currentReadFd.set(null)
            currentWriteFd.set(null)
        }
    }

    private fun sendAudioToSystem(buffer: ByteArray, length: Int, callback: SynthesisCallback) {
        if (mIsStopped.get()) return 
        
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        
        while (offset < length) {
            val chunk = min(length - offset, maxBufferSize)
            val result = callback.audioAvailable(buffer, offset, chunk)
            if (result == TextToSpeech.ERROR) {
                mIsStopped.set(true)
                break
            }
            offset += chunk
        }
    }
    
    private fun closeQuietly(fd: ParcelFileDescriptor?) {
        try { fd?.close() } catch (e: Exception) { }
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
        
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
    }
}

