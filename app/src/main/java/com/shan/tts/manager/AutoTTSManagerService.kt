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
    
    // Thread & State Control
    private val mIsStopped = AtomicBoolean(false)
    private val executorService = Executors.newCachedThreadPool()
    private var currentTask: Future<*>? = null
    private var currentActiveEngine: TextToSpeech? = null // လက်ရှိသုံးနေတဲ့ Engine ကို မှတ်ထားမယ်
    
    // Pipe Handling (Deadlock မဖြစ်အောင် Reference ယူထားသည်)
    private val currentReadFd = AtomicReference<ParcelFileDescriptor?>(null)
    private val currentWriteFd = AtomicReference<ParcelFileDescriptor?>(null)

    private val SYSTEM_OUTPUT_RATE = 24000
    private val BUFFER_SIZE = 8192 // Latency နည်းအောင် 8KB သုံးထားသည်

    // Reusable Buffers (Memory ယိုစိမ့်မှုမရှိအောင် ThreadLocal သုံးသည်)
    private val outBufferLocal = object : ThreadLocal<ByteBuffer>() {
        override fun initialValue(): ByteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE * 2).order(ByteOrder.LITTLE_ENDIAN)
    }

    private val outBufferArrayLocal = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray = ByteArray(BUFFER_SIZE * 2)
    }

    private val inBufferArrayLocal = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray = ByteArray(BUFFER_SIZE)
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

    // ★ FIX 1: TalkBack ပွတ်ဆွဲလိုက်ရင် ချက်ချင်းရပ်အောင် လုပ်ပေးမယ့်နေရာ ★
    override fun onStop() {
        mIsStopped.set(true)
        
        // 1. ကျွန်တော်တို့ Thread ကို အရင်သတ်မယ်
        currentTask?.cancel(true)
        
        // 2. Pipe တွေကို အတင်းပိတ်ပစ်မယ် (ဒါမှ Read လုပ်နေတဲ့ကောင် လန့်နိုးပြီး ထွက်သွားမယ်)
        try { currentReadFd.getAndSet(null)?.close() } catch (e: Exception) { }
        try { currentWriteFd.getAndSet(null)?.close() } catch (e: Exception) { }

        // 3. ★ အဓိကအချက် ★ နောက်ကွယ်က Engine (Google/Espeak) ကိုပါ အတင်းရပ်ခိုင်းမယ်
        // ဒါမှ သူက ၁.၅ စက္ကန့် ဆက်မဖတ်တော့ဘဲ ချက်ချင်းတိတ်သွားပြီး နောက်အသံကို လက်ခံနိုင်မှာပါ
        try {
            currentActiveEngine?.stop()
        } catch (e: Exception) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        try {
            if (request == null || callback == null) return

            mIsStopped.set(false)
            // ရှင်းလင်းရေး
            currentTask?.cancel(true)
            
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
                    // လက်ရှိသုံးနေတဲ့ Engine ကို မှတ်ထားမယ် (onStop မှာ ပြန်ပိတ်ဖို့)
                    currentActiveEngine = targetEngine
                    
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
            callback?.error() 
        }
    }

    private fun processSafeStream(engine: TextToSpeech, pkgName: String, text: String, callback: SynthesisCallback) {
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd.set(readFd)
        currentWriteFd.set(writeFd)

        // Engine Rate စစ်ဆေးခြင်း (0 ဖြစ်နေရင် Default 24000 သုံးမယ်)
        var engineInputRate = prefs.getInt("RATE_$pkgName", 24000)
        if (engineInputRate < 8000) engineInputRate = 24000
        
        // ★ FIX 2: AudioProcessor Init Error တက်ရင် App ပိတ်မသွားအောင် ကာကွယ်ခြင်း
        val audioProcessor = try {
            AudioProcessor(engineInputRate, 1)
        } catch (e: Throwable) {
            // JNI Error တက်ရင် Pipe ပိတ်ပြီး ကျော်သွားမယ်
            try { writeFd.close() } catch (ex: IOException) {}
            try { readFd.close() } catch (ex: IOException) {}
            return 
        }

        // Reader Task (အသံဖတ်မည့် Thread)
        currentTask = executorService.submit {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            
            // Reusable Buffer သုံးခြင်း (Stuttering သက်သာစေသည်)
            val localInBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val bufferArray = inBufferArrayLocal.get()!!
            
            val localOutBuffer = outBufferLocal.get()!!
            val localOutArray = outBufferArrayLocal.get()!!

            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (!mIsStopped.get()) {
                        val bytesRead = try { 
                            fis.read(bufferArray) 
                        } catch (e: IOException) { -1 }

                        if (bytesRead == -1) break

                        if (bytesRead > 0) {
                            localInBuffer.clear()
                            localInBuffer.put(bufferArray, 0, bytesRead)
                            localInBuffer.flip()

                            // Sonic Processing
                            var processed = audioProcessor.process(localInBuffer, bytesRead, localOutBuffer, localOutBuffer.capacity())
                            
                            while (processed > 0 && !mIsStopped.get()) {
                                localOutBuffer.get(localOutArray, 0, processed)
                                sendAudioToSystem(localOutArray, processed, callback)
                                localOutBuffer.clear()
                                processed = audioProcessor.process(localInBuffer, 0, localOutBuffer, localOutBuffer.capacity())
                            }
                        }
                    }
                    
                    // လက်ကျန်ရှင်းထုတ်ခြင်း
                    if (!mIsStopped.get()) {
                        audioProcessor.flushQueue()
                        localOutBuffer.clear()
                        var processed = audioProcessor.process(localInBuffer, 0, localOutBuffer, localOutBuffer.capacity())
                        while (processed > 0 && !mIsStopped.get()) {
                            localOutBuffer.get(localOutArray, 0, processed)
                            sendAudioToSystem(localOutArray, processed, callback)
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

        // Writer (Engine) Logic
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        
        try {
            val result = engine.synthesizeToFile(text, params, writeFd, uuid)
            
            // ★ Critical: Write လုပ်ပြီးတာနဲ့ WriteFD ကို ပိတ်ကိုပိတ်ရမယ်
            // မပိတ်ရင် Reader က စာဆုံးမှန်းမသိဘဲ စောင့်နေလိမ့်မယ် (Forever Loop ဖြစ်တတ်သည်)
            try { writeFd.close() } catch (e: IOException) { }
            currentWriteFd.set(null)

            if (result == TextToSpeech.SUCCESS) {
                try { 
                    currentTask?.get() 
                } catch (e: Exception) { }
            } else {
                currentTask?.cancel(true)
            }
        } catch (e: Exception) {
             try { writeFd.close() } catch (ex: IOException) { }
        } finally {
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

