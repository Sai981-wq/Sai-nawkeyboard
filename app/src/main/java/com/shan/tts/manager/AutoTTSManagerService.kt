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
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
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

    private val mIsStopped = AtomicBoolean(false)
    // ID စနစ်ကို ခဏဖယ်ပြီး ရိုးရှင်းတဲ့ Stop Flag နဲ့သွားပါမယ်
    private val executorService = Executors.newCachedThreadPool()

    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val END_OF_STREAM = ByteArray(0)
    
    // Pipe အဟောင်းကို သိမ်းထားမယ့်နေရာ
    @Volatile private var currentReadFd: ParcelFileDescriptor? = null

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
        voices.add(Voice("AutoTTS_Universal", Locale.US, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, null))
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
        try {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(this, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = locale
                    onReady(tts!!)
                }
            }, pkg)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onStop() {
        mIsStopped.set(true)
        // Force Close Pipe (အသံတုံ့ပြန်မှု မြန်စေရန်)
        try {
            currentReadFd?.close()
        } catch (e: Exception) {}
        currentReadFd = null
        
        audioQueue.clear()
        AudioProcessor.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        mIsStopped.set(false)
        audioQueue.clear()

        val text = request.charSequenceText.toString()
        val chunks = TTSUtils.splitHelper(text)

        // Default output rate (24000Hz is standard for Android)
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
                val sysRate = request.speechRate / 100f 
                val sysPitch = request.pitch / 100f
                // Slider တွေဖြုတ်ထားလို့ Default 100% ပဲ ထားပါမယ်
                val savedRate = 100
                val savedPitch = 100
                
                var finalRate = sysRate * (savedRate / 100f)
                var finalPitch = sysPitch * (savedPitch / 100f)
                
                if (finalPitch < 0.2f) finalPitch = 0.2f
                if (finalPitch > 2.0f) finalPitch = 2.0f

                val isEspeak = engineData.pkgName.lowercase(Locale.ROOT).contains("espeak")
                if (isEspeak) {
                    if (finalRate > 10.0f) finalRate = 10.0f
                } else {
                    if (finalRate > 4.0f) finalRate = 4.0f
                    if (finalRate < 0.1f) finalRate = 0.1f
                }

                targetEngine.setSpeechRate(finalRate)
                targetEngine.setPitch(finalPitch)
                
                val volume = getVolumeCorrection(engineData.pkgName)
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
                
                // eSpeak ဆို 22050, Google ဆို 24000
                val sonicSampleRate = if(isEspeak) 22050 else 24000

                processStreamBuffered(targetEngine, params, chunk.text, callback, UUID.randomUUID().toString(), sonicSampleRate)
            }
        }
        callback.done()
    }

    private fun processStreamBuffered(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String, sonicSampleRate: Int) {
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        
        currentReadFd = readFd

        val reader = executorService.submit {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
             // Header Skipping Logic ကို ဖယ်လိုက်ပါပြီ (အသံပျောက်စေလို့)
             ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                val buffer = ByteArray(4096)
                while (!mIsStopped.get() && !Thread.currentThread().isInterrupted) {
                    val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }
                    if (bytesRead == -1) break 
                    if (bytesRead > 0) {
                        try { 
                            audioQueue.put(buffer.copyOfRange(0, bytesRead)) 
                        } catch (e: InterruptedException) { break }
                    }
                }
                try { audioQueue.put(END_OF_STREAM) } catch (e: InterruptedException) {}
            }
        }

        val processor = executorService.submit {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            
            // Sonic ကို အမြဲ Reset လုပ်ပေးခြင်း (Buffer ရှင်းရန်)
            AudioProcessor.initSonic(sonicSampleRate, 1)

            val localInBuffer = ByteBuffer.allocateDirect(4096)
            val localOutBuffer = outBufferLocal.get()!!
            val localByteArray = outBufferArrayLocal.get()!!

            while (!mIsStopped.get() && !Thread.currentThread().isInterrupted) {
                val data = try { audioQueue.take() } catch (e: InterruptedException) { break }
                if (mIsStopped.get()) break 
                if (data === END_OF_STREAM) break

                localInBuffer.clear()
                localInBuffer.put(data)
                localInBuffer.flip()

                while (localInBuffer.hasRemaining() && !mIsStopped.get()) {
                    val processed = AudioProcessor.processAudio(localInBuffer, if(localInBuffer.hasRemaining()) data.size else 0, localOutBuffer, localOutBuffer.capacity())
                    
                    if (processed > 0) {
                        localOutBuffer.get(localByteArray, 0, processed)
                        sendAudioToSystem(localByteArray, processed, callback)
                        localOutBuffer.clear()
                    } else {
                        break
                    }
                }
            }
             if (!mIsStopped.get()) {
                 AudioProcessor.flush()
             }
        }

        try {
            // Engine ကို စာဖတ်ခိုင်းမယ်
            engine.synthesizeToFile(text, params, writeFd, uuid)
        } catch (e: Exception) { e.printStackTrace() }
        
        // Write Side ကို ပိတ်ပေးမှ Reader က ပြီးပြီဆိုတာ သိမှာ
        try { writeFd.close() } catch (e: Exception) {}

        try {
            processor.get()
        } catch (e: Exception) {}
        
        try { readFd.close() } catch (e: Exception) {}
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

    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return if (l.contains("espeak") || l.contains("shan") || l.contains("myanmar") || l.contains("saomai")) 1.0f else 0.8f
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

