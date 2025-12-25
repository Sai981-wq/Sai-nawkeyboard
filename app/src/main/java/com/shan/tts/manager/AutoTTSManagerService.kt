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
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeechService
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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

    // Thread Executors (စာရေးမည့်သူ နှင့် စာဖတ်မည့်သူကို ခွဲထားသည်)
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val readerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var currentReaderTask: Future<*>? = null
    private var currentWriterTask: Future<*>? = null

    // State Control
    private val mIsStopped = AtomicBoolean(false)
    private var currentActiveEngine: TextToSpeech? = null

    // Pipe References
    private val currentReadFd = AtomicReference<ParcelFileDescriptor?>(null)
    private val currentWriteFd = AtomicReference<ParcelFileDescriptor?>(null)

    private val SYSTEM_OUTPUT_RATE = 24000
    
    // ★ HUGE BUFFER: 64KB (Buffer မလောက်လို့ အသံပြတ်တာမျိုး မဖြစ်စေရ)
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

    // ★ AGGRESSIVE STOP: ၁.၅ စက္ကန့် ကြာတာကို ဖြေရှင်းခြင်း
    override fun onStop() {
        mIsStopped.set(true)

        // 1. Thread တွေကို အတင်းရပ်
        currentReaderTask?.cancel(true)
        currentWriterTask?.cancel(true)

        // 2. Pipe တွေကို ချက်ချင်းရိုက်ချိုး (Engine ရော Reader ပါ Error တက်ပြီး ရပ်သွားမယ်)
        closeQuietly(currentWriteFd.getAndSet(null))
        closeQuietly(currentReadFd.getAndSet(null))

        // 3. Engine ကိုပါ Stop လှမ်းလုပ် (Double Lock)
        try {
            currentActiveEngine?.stop()
        } catch (e: Exception) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        mIsStopped.set(false)
        // အဟောင်းရှင်းထုတ်
        currentReaderTask?.cancel(true)
        currentWriterTask?.cancel(true)

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
                currentActiveEngine = targetEngine
                
                synchronized(targetEngine) {
                    if (mIsStopped.get()) return@synchronized
                    targetEngine.setSpeechRate(sysRate)
                    targetEngine.setPitch(sysPitch)
                    
                    processStreamWithHugeBuffer(targetEngine, targetPkg, chunk.text, callback)
                }
            }
        }

        if (!mIsStopped.get()) {
            callback.done()
        }
    }

    private fun processStreamWithHugeBuffer(engine: TextToSpeech, pkgName: String, text: String, callback: SynthesisCallback) {
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd.set(readFd)
        currentWriteFd.set(writeFd)

        var engineInputRate = prefs.getInt("RATE_$pkgName", 24000)
        if (engineInputRate < 8000) engineInputRate = 24000

        // Init Sonic
        val audioProcessor = try {
            AudioProcessor(engineInputRate, 1)
        } catch (e: Throwable) {
            closeQuietly(writeFd)
            closeQuietly(readFd)
            return
        }

        // Latch to wait for Engine completion (စာဖတ်လို့မပြီးမချင်း Pipe မပိတ်အောင် စောင့်မယ်)
        val synthesisLatch = CountDownLatch(1)

        // ★ THREAD 1: READER (Consumer)
        // Pipe ထဲက အသံကို စုပ်ယူပြီး Sonic နဲ့ ပြင်
        val readerFuture = readerExecutor.submit {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            
            // ★ HUGE BUFFER ALLOCATION (64KB)
            val buffer = ByteArray(BUFFER_SIZE)
            val inputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val outputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE * 2).order(ByteOrder.LITTLE_ENDIAN)
            val outputArray = ByteArray(BUFFER_SIZE * 2)

            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (!mIsStopped.get()) {
                        // Read blocking
                        val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }

                        if (bytesRead == -1) break 

                        if (bytesRead > 0) {
                            inputBuffer.clear()
                            inputBuffer.put(buffer, 0, bytesRead)
                            inputBuffer.flip()

                            // Sonic Process
                            var processed = audioProcessor.process(inputBuffer, bytesRead, outputBuffer, outputBuffer.capacity())

                            // Send to System
                            while (processed > 0 && !mIsStopped.get()) {
                                outputBuffer.get(outputArray, 0, processed)
                                sendAudioToSystem(outputArray, processed, callback)
                                outputBuffer.clear()
                                processed = audioProcessor.process(inputBuffer, 0, outputBuffer, outputBuffer.capacity())
                            }
                        }
                    }
                    
                    // Flush remaining audio
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
            } finally {
                audioProcessor.release()
            }
        }
        currentReaderTask = readerFuture

        // ★ THREAD 2: WRITER (Producer)
        // Engine ကို Pipe ထဲ စာရေးထည့်ခိုင်း
        val writerFuture = writerExecutor.submit {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            
            // Set Listener to know when DONE
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) { synthesisLatch.countDown() }
                override fun onError(id: String) { synthesisLatch.countDown() }
            })
            
            try {
                // Synthesize (Async call)
                val result = engine.synthesizeToFile(text, params, writeFd, uuid)
                
                if (result == TextToSpeech.SUCCESS) {
                    // ★ WAIT: Engine က ရေးလို့မပြီးမချင်း စောင့်မယ် (Timeout 30s)
                    // ဒါမှ စာမဆုံးခင် Pipe ပိတ်လိုက်တာမျိုး မဖြစ်တော့မှာ
                    synthesisLatch.await(30, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
            } finally {
                // ပြီးမှ Write ပိုက်ကို ပိတ် (Reader က EOF သိသွားမယ်)
                closeQuietly(writeFd)
                currentWriteFd.set(null)
            }
        }
        currentWriterTask = writerFuture

        // Main thread waits for Reader
        try {
            readerFuture.get()
        } catch (e: Exception) {
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
        
        while (offset < length && !mIsStopped.get()) {
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
        currentReaderTask?.cancel(true)
        currentWriterTask?.cancel(true)
        EngineScanner.stop()
        
        writerExecutor.shutdownNow()
        readerExecutor.shutdownNow()
        
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
    }
}

