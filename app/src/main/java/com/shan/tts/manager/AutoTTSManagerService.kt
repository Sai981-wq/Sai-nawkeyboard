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
import java.util.concurrent.ExecutorService
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

    // ★ STABILITY: Thread တွေကို သီးသန့်ခွဲထုတ်လိုက်ပါတယ်
    // writerExecutor -> Google/Espeak ကနေ Pipe ထဲ ရေးထည့်မယ့် Thread
    // readerExecutor -> Pipe ထဲကနေ ဖတ်ပြီး Sonic နဲ့ ပြင်မယ့် Thread
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val readerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var currentReaderTask: Future<*>? = null
    private var currentWriterTask: Future<*>? = null

    // State Control
    private val mIsStopped = AtomicBoolean(false)
    private var currentActiveEngine: TextToSpeech? = null

    // Pipe References (Deadlock မဖြစ်အောင် သိမ်းထားမည်)
    private val currentReadFd = AtomicReference<ParcelFileDescriptor?>(null)
    private val currentWriteFd = AtomicReference<ParcelFileDescriptor?>(null)

    private val SYSTEM_OUTPUT_RATE = 24000
    // ★ PERFORMANCE: Buffer ကို 16KB ထားလိုက်ပါ (latency နဲ့ performance မျှတအောင်)
    private val BUFFER_SIZE = 16384 

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

    // ★ AGGRESSIVE STOP IMPLEMENTATION ★
    // TalkBack ပွတ်လိုက်ရင် ၁.၅ စက္ကန့် မကြာအောင် ဖြေရှင်းသည့်နည်းလမ်း
    override fun onStop() {
        mIsStopped.set(true)

        // 1. Thread တွေကို အတင်းရပ်ခိုင်းမယ်
        currentReaderTask?.cancel(true)
        currentWriterTask?.cancel(true)

        // 2. Pipe တွေကို "ရိုက်ချိုး" မယ် (Close Immediately)
        // Write Pipe ကို ပိတ်လိုက်ရင် Engine က စာရေးလို့မရတော့ဘဲ "Broken Pipe" Error တက်ပြီး ချက်ချင်းရပ်သွားမယ်
        closeQuietly(currentWriteFd.getAndSet(null))
        closeQuietly(currentReadFd.getAndSet(null))

        // 3. Engine ကိုပါ Stop လှမ်းလုပ်မယ် (Double Lock)
        try {
            currentActiveEngine?.stop()
        } catch (e: Exception) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        // အသစ်မစခင် အဟောင်းတွေကို ရှင်းထုတ်
        mIsStopped.set(false)
        currentReaderTask?.cancel(true)
        currentWriterTask?.cancel(true)

        val text = request.charSequenceText.toString()
        val sysRate = request.speechRate / 100f
        val sysPitch = request.pitch / 100f

        val chunks = TTSUtils.splitHelper(text)

        // Start Audio Stream
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
                
                // Engine Configuration
                synchronized(targetEngine) {
                    if (mIsStopped.get()) return@synchronized
                    targetEngine.setSpeechRate(sysRate)
                    targetEngine.setPitch(sysPitch)
                    
                    // Process Audio Stream
                    processStreamRobust(targetEngine, targetPkg, chunk.text, callback)
                }
            }
        }

        if (!mIsStopped.get()) {
            callback.done()
        }
    }

    private fun processStreamRobust(engine: TextToSpeech, pkgName: String, text: String, callback: SynthesisCallback) {
        // Create Pipe
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd.set(readFd)
        currentWriteFd.set(writeFd)

        var engineInputRate = prefs.getInt("RATE_$pkgName", 24000)
        if (engineInputRate < 8000) engineInputRate = 24000

        // Init Sonic Processor
        val audioProcessor = try {
            AudioProcessor(engineInputRate, 1)
        } catch (e: Throwable) {
            closeQuietly(writeFd)
            closeQuietly(readFd)
            return
        }

        // ★ THREAD 1: READER (Consumer)
        // Pipe ထဲက အသံကို စုပ်ယူပြီး Sonic နဲ့ ပြင်၊ ပြီးရင် System ကို ပို့
        val readerFuture = readerExecutor.submit {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            
            // Allocate New Buffers (No Cache - More RAM but Safer)
            val buffer = ByteArray(BUFFER_SIZE)
            val inputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val outputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE * 2).order(ByteOrder.LITTLE_ENDIAN)
            val outputArray = ByteArray(BUFFER_SIZE * 2)

            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (!mIsStopped.get()) {
                        // Read from Pipe (Blocking Call)
                        // Pipe ပိတ်သွားရင် IOException တက်ပြီး Loop ထဲက ထွက်သွားမယ်
                        val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }

                        if (bytesRead == -1) break 

                        if (bytesRead > 0) {
                            inputBuffer.clear()
                            inputBuffer.put(buffer, 0, bytesRead)
                            inputBuffer.flip()

                            // Process with Sonic
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
                // Pipe broken or thread interrupted - safe to ignore
            } finally {
                audioProcessor.release()
            }
        }
        currentReaderTask = readerFuture

        // ★ THREAD 2: WRITER (Producer)
        // Engine ကို Pipe ထဲ စာရေးထည့်ခိုင်း (သီးသန့် Thread နဲ့ မောင်းမယ်)
        val writerFuture = writerExecutor.submit {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            
            try {
                // Synthesize to Pipe
                engine.synthesizeToFile(text, params, writeFd, uuid)
            } catch (e: Exception) {
                // Engine error
            } finally {
                // အရေးကြီး: ရေးပြီးတာနဲ့ Write ပိုက်ကို ပိတ်ကိုပိတ်ရမယ်
                // ဒါမှ Reader က စာဆုံးပြီဆိုတာ သိပြီး ရပ်မှာ
                closeQuietly(writeFd)
                currentWriteFd.set(null)
            }
        }
        currentWriterTask = writerFuture

        // Main thread က Reader ပြီးတဲ့အထိ စောင့်မယ်
        try {
            readerFuture.get() // Wait for reader to finish
        } catch (e: Exception) {
            // Interrupted
        } finally {
            // Cleanup
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
            
            // Audio System သို့ ပို့ခြင်း
            val result = callback.audioAvailable(buffer, offset, chunk)
            
            // System က လက်မခံရင် (ဥပမာ - User က Stop နှိပ်လိုက်ရင်) Error ပြန်လာတတ်တယ်
            if (result == TextToSpeech.ERROR) {
                mIsStopped.set(true)
                break
            }
            offset += chunk
        }
    }
    
    // Helper to close streams safely
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

