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
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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

    @Volatile
    private var mIsStopped = false

    // Thread Pool
    private val executorService: ExecutorService = Executors.newFixedThreadPool(2)
    
    // Tasks (ရပ်ချင်တဲ့အခါ ချက်ချင်း Cancel လုပ်ဖို့ သိမ်းထားမယ်)
    private var readerTask: Future<*>? = null
    private var processorTask: Future<*>? = null

    // "ကြားခံကန်" ကို Global ကြေညာထားမှ Stop လုပ်ရင် လှမ်းသွန်ပစ်လို့ရမယ်
    private val audioQueue = LinkedBlockingQueue<ByteArray>()

    private val FIXED_OUTPUT_HZ = 24000
    private val END_OF_STREAM = ByteArray(0)

    // Buffers
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

            shanPkgName = resolveEngine("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng", "org.himnario.espeak"), defaultEngine)
            initEngine(shanPkgName, Locale("shn", "MM")) { shanEngine = it }

            burmesePkgName = resolveEngine("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts", "com.samsung.SMT"), defaultEngine)
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = resolveEngine("pref_english_pkg", listOf("com.google.android.tts", "com.samsung.SMT", "es.codefactory.eloquencetts"), defaultEngine)
            initEngine(englishPkgName, Locale.US) { englishEngine = it }

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
                    tts?.language = locale
                    onReady(tts!!)
                }
            }, pkg)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE

    // ချက်ချင်းရပ်စေမည့် Function
    override fun onStop() {
        mIsStopped = true
        
        // ၁။ Queue (ကြားခံကန်) ကို ချက်ချင်း သွန်ပစ်မယ်
        audioQueue.clear()
        
        // ၂။ လုပ်လက်စ အလုပ်တွေကို Cancel လုပ်မယ် (Interrupt)
        readerTask?.cancel(true)
        processorTask?.cancel(true)
        
        AudioProcessor.stop()
        lastConfiguredRate = -1
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        // စာအသစ်လာရင် အရင်ဆုံး အဟောင်းတွေကို ရှင်းထုတ်မယ်
        onStop() 
        mIsStopped = false
        audioQueue.clear() // သေချာအောင် နောက်တစ်ခေါက် ထပ်ရှင်းမယ်

        val text = request.charSequenceText.toString()
        val sysRate = request.speechRate
        val sysPitch = request.pitch
        val chunks = TTSUtils.splitHelper(text)

        callback.start(FIXED_OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

        for (chunk in chunks) {
            if (mIsStopped) break

            val langCode = when (chunk.lang) {
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

                var useRate = sysRate / 100f
                var usePitch = sysPitch / 100f
                val lowerPkg = engineData.pkgName.lowercase(Locale.ROOT)
                if (lowerPkg.contains("myanmar") || lowerPkg.contains("saomai") || lowerPkg.contains("ttsm")) {
                    useRate = 1.0f
                    usePitch = 1.0f
                }
                targetEngine.setSpeechRate(useRate)
                targetEngine.setPitch(usePitch)
                val volume = getVolumeCorrection(engineData.pkgName)
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)

                processStreamBuffered(targetEngine, params, chunk.text, callback, UUID.randomUUID().toString())
            }
        }
        callback.done()
    }

    private fun processStreamBuffered(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }
        val readFd = pipe[0]
        val writeFd = pipe[1]

        // Thread 1: Reader (ပိုက်လိုင်းထဲက ဒေတာကို Queue ထဲ အမြန်မောင်းထည့်မည့်သူ)
        readerTask = executorService.submit {
             ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                val buffer = ByteArray(4096)
                while (!mIsStopped && !Thread.currentThread().isInterrupted) {
                    val bytesRead = try {
                         fis.read(buffer)
                    } catch (e: IOException) { -1 }

                    if (bytesRead == -1) break 

                    if (bytesRead > 0) {
                        try {
                            // Queue ထဲထည့်မယ် (InterruptedException တက်ရင် ချက်ချင်းရပ်မယ်)
                            audioQueue.put(buffer.copyOfRange(0, bytesRead))
                        } catch (e: InterruptedException) {
                            break // Stop နှိပ်လိုက်ရင် ဒီနေရာမှာ Loop ကျိုးသွားမယ်
                        }
                    }
                }
                try { audioQueue.put(END_OF_STREAM) } catch (e: InterruptedException) {}
            }
        }

        // Thread 2: Processor (Queue ထဲကဟာကို ယူပြီး Sonic နဲ့ အသံပြောင်းမည့်သူ)
        processorTask = executorService.submit {
            val localInBuffer = ByteBuffer.allocateDirect(4096)
            val localOutBuffer = outBufferLocal.get()!!
            val localByteArray = outBufferArrayLocal.get()!!

            while (!mIsStopped && !Thread.currentThread().isInterrupted) {
                val data = try {
                    // Queue ထဲမှာ ဒေတာမရှိရင် စောင့်နေမယ်
                    audioQueue.take() 
                } catch (e: InterruptedException) {
                    break // Stop နှိပ်ရင် ဒီနေရာမှာ Loop ကျိုးသွားမယ်
                }
                
                if (data === END_OF_STREAM) break

                localInBuffer.clear()
                if (localInBuffer.capacity() < data.size) {
                     // လိုအပ်ရင် Buffer ချဲ့တာမျိုး လုပ်နိုင်သည်
                }
                localInBuffer.put(data)
                localInBuffer.flip()

                // Sonic Process
                var processed = AudioProcessor.processAudio(localInBuffer, data.size, localOutBuffer, localOutBuffer.capacity())
                
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
            AudioProcessor.flush()
        }

        val result = engine.synthesizeToFile(text, params, writeFd, uuid)
        
        try { writeFd.close() } catch (e: Exception) {}

        if (result == TextToSpeech.SUCCESS) {
            try {
                // Processor ပြီးတဲ့အထိ စောင့်မယ် (ဒါပေမဲ့ Stop နှိပ်ရင် Cancel လုပ်ထားလို့ ချက်ချင်းထွက်သွားမယ်)
                processorTask?.get()
            } catch (e: Exception) {
                // Cancel လုပ်လိုက်ရင် ဒီမှာ Exception တက်မယ်၊ ဘာမှလုပ်စရာမလို၊ ပုံမှန်ပဲ
            }
        } else {
            readerTask?.cancel(true)
            processorTask?.cancel(true)
        }
        
        try { readFd.close() } catch (e: Exception) {}
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
        onStop() // သေချာအောင် ခေါ်မယ်
        executorService.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        AudioProcessor.stop()
    }
}

