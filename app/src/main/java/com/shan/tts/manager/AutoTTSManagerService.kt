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
import android.speech.tts.Voice
import java.io.IOException
import java.nio.ByteBuffer
import java.util.HashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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

    @Volatile private var lastConfiguredRate: Int = -1

    // Session ID (Talkback ပွတ်ဆွဲရင် အဟောင်းတွေကို ချက်ချင်းဖျက်ပစ်ဖို့)
    private val currentSessionId = AtomicLong(0)

    // Reader (စာဖတ်သမား) အတွက် သီးသန့် Thread များ (အကန့်အသတ်မရှိ)
    private val readerExecutor: ExecutorService = Executors.newCachedThreadPool()
    
    // Processor (အသံထွက်သမား) အတွက် တစ်လမ်းမောင်း Thread (အသံမကျော်အောင် တန်းစီလုပ်မည်)
    private val processorExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // ရေပုံး (Buffer) - အကန့်အသတ်မရှိ (Unlimited)
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    
    private val FIXED_OUTPUT_HZ = 24000
    private val END_OF_STREAM = ByteArray(0)

    // Memory အပိုမသုံးအောင် Buffer များကို ပြန်လည်အသုံးပြုခြင်း
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
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onGetVoices(): List<Voice>? {
        val voices = ArrayList<Voice>()
        val features = HashSet<String>()
        features.add("networkTimeoutMs")
        features.add("networkRetriesCount")
        voices.add(Voice("AutoTTS_Universal", Locale.US, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, features))
        return voices
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

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

    override fun onStop() {
        // ID တိုးလိုက်တာနဲ့ အရင်အသံတွေ အကုန် Cancel ဖြစ်သွားမယ်
        currentSessionId.incrementAndGet()
        // Queue ကို ရှင်းမယ် (ဒုတ်ဒုတ်အသံ ပျောက်ဖို့)
        audioQueue.clear()
        AudioProcessor.flush()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        // စာကြောင်းသစ်အတွက် ID အသစ်ယူမယ်
        val mySessionId = currentSessionId.incrementAndGet()
        
        // အရင်လက်ကျန်တွေ ရှင်းမယ်
        audioQueue.clear()

        val text = request.charSequenceText.toString()
        val chunks = TTSUtils.splitHelper(text)

        callback.start(FIXED_OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

        for (chunk in chunks) {
            // Talkback ပွတ်ဆွဲလိုက်ရင် ID မတူတော့လို့ ချက်ချင်းရပ်မယ်
            if (currentSessionId.get() != mySessionId) break

            val langCode = when (chunk.lang) {
                "SHAN" -> "shn"
                "MYANMAR" -> "my"
                else -> "eng"
            }
            val engineData = getEngineDataForLang(langCode)
            val targetEngine = engineData.engine ?: englishEngine

            if (targetEngine != null) {
                val sysRate = request.speechRate / 100f 
                val sysPitch = request.pitch / 100f
                val (savedRate, savedPitch) = when (langCode) {
                    "shn" -> Pair(configPrefs.getInt("SHAN_RATE", 100), configPrefs.getInt("SHAN_PITCH", 100))
                    "my" -> Pair(configPrefs.getInt("MYANMAR_RATE", 100), configPrefs.getInt("MYANMAR_PITCH", 100))
                    else -> Pair(configPrefs.getInt("ENGLISH_RATE", 100), configPrefs.getInt("ENGLISH_PITCH", 100))
                }
                
                val finalRate = sysRate * (savedRate / 100f)
                val finalPitch = sysPitch * (savedPitch / 100f)
                val sonicSampleRate = configPrefs.getInt("RATE_${engineData.pkgName}", 22050)

                targetEngine.setSpeechRate(finalRate)
                targetEngine.setPitch(finalPitch)
                
                val volume = getVolumeCorrection(engineData.pkgName)
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)

                // အသံမစောင့်တော့ဘူး၊ Queue ထဲ ပစ်ထည့်ပြီး ရှေ့ဆက်တိုးမယ်
                processStreamBuffered(targetEngine, params, chunk.text, callback, UUID.randomUUID().toString(), sonicSampleRate, mySessionId)
            }
        }
        // ဒီနေရာမှာ callback.done() ကို ချက်ချင်းမခေါ်ဘူး၊ Processor ပြီးမှ ခေါ်ဖို့ လိုကောင်းလိုနိုင်ပေမယ့်
        // Android TTS က done() ခေါ်လိုက်ရင်တောင် buffer ရှိသရွေ့ ဆက်ဖွင့်ပေးလေ့ရှိပါတယ်။
        // အရေးကြီးတာက blocking မလုပ်ဖို့ပါပဲ။
        callback.done()
    }

    private fun processStreamBuffered(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String, sonicSampleRate: Int, sessionId: Long) {
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return }
        val readFd = pipe[0]
        val writeFd = pipe[1]

        // ၁။ Reader Thread (Parallel) - Pipe ထဲကနေ Queue ထဲ အမြန်ဆုံး မောင်းထည့်မယ်
        readerExecutor.submit {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
             ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                val buffer = ByteArray(4096)
                while (currentSessionId.get() == sessionId && !Thread.currentThread().isInterrupted) {
                    val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }
                    if (bytesRead == -1) break 
                    if (bytesRead > 0) {
                        try { audioQueue.put(buffer.copyOfRange(0, bytesRead)) } catch (e: InterruptedException) { break }
                    }
                }
                if (currentSessionId.get() == sessionId) {
                    try { audioQueue.put(END_OF_STREAM) } catch (e: InterruptedException) {}
                }
            }
        }

        // ၂။ Processor Thread (Sequential) - SingleThreadExecutor သုံးထားလို့ ရှေ့နောက် မကျော်ဘူး
        processorExecutor.submit {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            
            // Sonic Setup
            if (sonicSampleRate != lastConfiguredRate) {
                AudioProcessor.initSonic(sonicSampleRate, 1)
                lastConfiguredRate = sonicSampleRate
            } else {
                AudioProcessor.flush()
            }

            val localInBuffer = ByteBuffer.allocateDirect(4096)
            val localOutBuffer = outBufferLocal.get()!!
            val localByteArray = outBufferArrayLocal.get()!!

            // Queue ထဲက Data ကို ဆွဲထုတ်ပြီး အသံပြောင်းမယ်
            // END_OF_STREAM တွေ့တဲ့အထိ လုပ်မယ် (ဒါမှ Chunk တစ်ခုပြီးမှ တစ်ခုသွားမယ်)
            while (currentSessionId.get() == sessionId && !Thread.currentThread().isInterrupted) {
                // Poll သုံးခြင်းက Deadlock မဖြစ်အောင် ကာကွယ်ပေးသည်
                val data = try { audioQueue.poll(200, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }
                
                if (currentSessionId.get() != sessionId) break
                if (data == null) continue // Data မလာသေးရင် ခဏစောင့်မယ်
                if (data === END_OF_STREAM) break // ဒီ Chunk ပြီးပြီ၊ နောက် Chunk အတွက် နေရာပေးမယ်

                localInBuffer.clear()
                if (localInBuffer.capacity() < data.size) { } 
                localInBuffer.put(data)
                localInBuffer.flip()

                if (currentSessionId.get() != sessionId) break

                var processed = AudioProcessor.processAudio(localInBuffer, data.size, localOutBuffer, localOutBuffer.capacity())
                
                if (currentSessionId.get() != sessionId) break
                
                if (processed > 0) {
                    localOutBuffer.get(localByteArray, 0, processed)
                    sendAudioToSystem(localByteArray, processed, callback)
                }

                while (processed > 0 && currentSessionId.get() == sessionId) {
                    localOutBuffer.clear()
                    processed = AudioProcessor.processAudio(localInBuffer, 0, localOutBuffer, localOutBuffer.capacity())
                    if (processed > 0) {
                        localOutBuffer.get(localByteArray, 0, processed)
                        sendAudioToSystem(localByteArray, processed, callback)
                    }
                }
            }
             if (currentSessionId.get() == sessionId) {
                 AudioProcessor.flush()
             }
        }

        // ၃။ Engine Write (Main Thread)
        // ဒီကောင်က သူ့ဟာသူ Pipe ပြည့်ရင် ခဏစောင့်၊ လျော့ရင် ဆက်ရေး လုပ်သွားလိမ့်မယ်
        // Reader က Queue (Infinite) ထဲ ထည့်နေတာမို့ Pipe ပြည့်ပြီး ရပ်သွားတာမျိုး မဖြစ်တော့ဘူး
        engine.synthesizeToFile(text, params, writeFd, uuid)
        try { writeFd.close() } catch (e: Exception) {}
        try { readFd.close() } catch (e: Exception) {}
        
        // အရေးကြီးဆုံးအချက်: ဒီမှာ latch.await() မသုံးတော့ဘူး။
        // Engine ရေးပြီးတာနဲ့ နောက်တစ်ကြောင်း ချက်ချင်းကူးမယ်။
        // Processor ကတော့ နောက်ကနေ သူ့အလှည့်နဲ့သူ လုပ်သွားလိမ့်မယ်။
    }

    private fun sendAudioToSystem(buffer: ByteArray, length: Int, callback: SynthesisCallback) {
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        while (offset < length) {
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
        currentSessionId.incrementAndGet()
        readerExecutor.shutdownNow()
        processorExecutor.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        AudioProcessor.stop()
    }
}

