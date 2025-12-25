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
    
    // Thread Control
    private val mIsStopped = AtomicBoolean(false)
    private val executorService = Executors.newCachedThreadPool()
    
    // ★ 1.5s Delay ဖြေရှင်းချက် (၁): လက်ရှိ Run နေတဲ့ Task ကို မှတ်ထားပြီး Stop လုပ်တာနဲ့ ချက်ချင်း Cancel လုပ်မယ်
    private var currentTask: Future<*>? = null
    private val currentReadFd = AtomicReference<ParcelFileDescriptor?>(null)

    private val SYSTEM_OUTPUT_RATE = 24000
    private val BUFFER_SIZE = 16384 

    // Reusable Buffers to prevent GC pauses (Stuttering Fix)
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

            // Initialize Engines
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

    // ★ 1.5s Delay ဖြေရှင်းချက် (၂): Stop လုပ်တာနဲ့ အကုန်ဖြတ်ချမယ်
    override fun onStop() {
        mIsStopped.set(true)
        
        // 1. Thread ကို Interrupt လုပ်မယ်
        currentTask?.cancel(true)
        currentTask = null

        // 2. Pipe ကို ချိုးလိုက်မယ် (ဒါမှ Read လုပ်နေတဲ့ကောင်က Error တက်ပြီး ချက်ချင်းရပ်သွားမယ်)
        val fd = currentReadFd.getAndSet(null)
        try { fd?.close() } catch (e: IOException) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        try {
            if (request == null || callback == null) return

            // Stop flag ကို Reset ပြန်လုပ်
            mIsStopped.set(false)
            
            // ယခင်လက်ကျန် Task ရှိရင် ရှင်းထုတ်
            currentTask?.cancel(true)
            
            val text = request.charSequenceText.toString()
            val sysRate = request.speechRate / 100f
            val sysPitch = request.pitch / 100f
            
            val chunks = TTSUtils.splitHelper(text)

            // Audio Track Start
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
                        if (mIsStopped.get()) return@synchronized

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
            // Critical Error ဖြစ်ရင်တောင် App မပိတ်စေနဲ့
            callback?.error() 
        }
    }

    private fun processSafeStream(engine: TextToSpeech, pkgName: String, text: String, callback: SynthesisCallback) {
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd.set(readFd)

        // Engine Rate ကို ဖတ်မယ် (မရှိရင် Default 24000)
        val engineInputRate = prefs.getInt("RATE_$pkgName", 24000)
        
        // C++ Audio Processor Init
        val audioProcessor = try {
            AudioProcessor(engineInputRate, 1)
        } catch (e: Throwable) {
            // Native Lib Error တက်ရင် အသံမထွက်ဘဲ ကျော်သွားမယ် (App မပိတ်အောင်)
            try { writeFd.close() } catch (ex: IOException) {}
            try { readFd.close() } catch (ex: IOException) {}
            currentReadFd.set(null)
            return 
        }

        // Reader Task (Background Thread)
        currentTask = executorService.submit {
            // Priority အမြင့်ဆုံးပေးထားမယ် (Lag မဖြစ်အောင်)
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            
            val localInBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val localOutBuffer = outBufferLocal.get()!!
            val localByteArray = outBufferArrayLocal.get()!!

            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    var isFirstChunk = true
                    
                    while (!mIsStopped.get()) {
                        // ★ Latency Trick: ပထမဆုံးအသံစထွက်ချိန်မှာ Buffer အနည်းဆုံးသုံးမယ် (မြန်မြန်ထွက်အောင်)
                        // နောက်ပိုင်းကျမှ Buffer အများကြီးသုံးမယ် (အသံမပြတ်အောင်)
                        val targetReadSize = if (isFirstChunk) 1024 else BUFFER_SIZE
                        val tempBuffer = ByteArray(targetReadSize)
                        
                        // ★ Stability Fix: Read Error တက်ရင် ချက်ချင်း Loop မထွက်ဘဲ Check လုပ်မယ်
                        val bytesRead = try { 
                            fis.read(tempBuffer) 
                        } catch (e: IOException) { 
                            // Stop လုပ်လိုက်လို့ Pipe ကျိုးသွားတာဖြစ်နိုင်တယ်
                            -1 
                        }

                        if (bytesRead == -1) break // End of Stream

                        if (bytesRead > 0) {
                            isFirstChunk = false // ပထမအသံထွက်ပြီးပြီ

                            localInBuffer.clear()
                            localInBuffer.put(tempBuffer, 0, bytesRead)
                            localInBuffer.flip()

                            // Sonic Processing
                            var processed = audioProcessor.process(localInBuffer, bytesRead, localOutBuffer, localOutBuffer.capacity())
                            
                            while (processed > 0 && !mIsStopped.get()) {
                                localOutBuffer.get(localByteArray, 0, processed)
                                sendAudioToSystem(localByteArray, processed, callback)
                                localOutBuffer.clear()
                                processed = audioProcessor.process(localInBuffer, 0, localOutBuffer, localOutBuffer.capacity())
                            }
                        }
                    }
                    
                    // လက်ကျန်ရှင်းထုတ်ခြင်း (Flush)
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
                // Ignore unexpected errors during read
            } finally {
                audioProcessor.release()
            }
        }

        // Engine Synthesize Trigger (Writer)
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        
        // ★ Stability Fix: Engine က Write ဖို့ကြိုးစားချိန်မှာ Pipe ပိတ်သွားရင် Error မတက်အောင် Try/Catch ခံထား
        try {
            val result = engine.synthesizeToFile(text, params, writeFd, uuid)
            // Write Side ကို ပိတ်လိုက်မှ Read Side က EOF သိမှာ
            try { writeFd.close() } catch (e: IOException) { }

            if (result == TextToSpeech.SUCCESS) {
                try { 
                    // Reader ပြီးတဲ့အထိ စောင့်မယ် (ဒါပေမယ့် Stop လုပ်ရင် Interrupted Exception နဲ့ ထွက်သွားမယ်)
                    currentTask?.get() 
                } catch (e: Exception) { }
            } else {
                currentTask?.cancel(true)
                audioProcessor.release()
            }
        } catch (e: Exception) {
             try { writeFd.close() } catch (ex: IOException) { }
        } finally {
             currentReadFd.set(null)
        }
    }

    private fun sendAudioToSystem(buffer: ByteArray, length: Int, callback: SynthesisCallback) {
        if (mIsStopped.get()) return
        
        // ★ Stability Fix: Max Buffer Size ကို ကျော်မပို့မိအောင် စစ်ဆေးမယ်
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        while (offset < length && !mIsStopped.get()) {
            val chunk = min(length - offset, maxBufferSize)
            
            // Audio System က လက်ခံနိုင်တဲ့အထိ ပို့မယ်
            val result = callback.audioAvailable(buffer, offset, chunk)
            
            // Error တက်ရင် ရပ်မယ်
            if (result == TextToSpeech.ERROR) {
                mIsStopped.set(true)
                break
            }
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
        currentTask?.cancel(true)
        EngineScanner.stop()
        executorService.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
    }
}

