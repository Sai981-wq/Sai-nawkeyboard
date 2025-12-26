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
    
    // Engine Pointer များ
    private val currentReadFd = AtomicReference<ParcelFileDescriptor?>(null)
    private val currentWriteFd = AtomicReference<ParcelFileDescriptor?>(null)
    private var currentActiveEngine: TextToSpeech? = null

    private val SYSTEM_OUTPUT_RATE = 24000
    // Buffer ကို အသေးဆုံး ထားမယ် (Typing Latency လုံးဝမရှိအောင်)
    private val BUFFER_SIZE = 4096 

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

        mIsStopped.set(false) // Reset Flag

        val text = request.charSequenceText.toString()
        val sysRate = request.speechRate / 100f
        val sysPitch = request.pitch / 100f

        val chunks = TTSUtils.splitHelper(text)

        try {
            callback.start(SYSTEM_OUTPUT_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)

            // runBlocking သည် အလုပ်အားလုံး မပြီးမချင်း Main Thread ကို စောင့်ခိုင်းထားသည်
            // ဒါမှ စာဆုံးခါနီး အသံပြတ်သွားတာမျိုး မဖြစ်မှာပါ
            runBlocking {
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
                        
                        var engineInputRate = prefs.getInt("RATE_$targetPkg", 24000)
                        if (engineInputRate < 8000) engineInputRate = 24000

                        val audioProcessor = try {
                            AudioProcessor(engineInputRate, 1)
                        } catch (e: Throwable) {
                            continue
                        }

                        try {
                            targetEngine.setSpeechRate(sysRate)
                            targetEngine.setPitch(sysPitch)
                            
                            // Streaming Process ကို ခေါ်မည်
                            processStreamSynchronized(targetEngine, targetPkg, chunk.text, callback, audioProcessor)
                        } finally {
                            audioProcessor.release()
                        }
                    }
                }
                
                // အားလုံးပြီးမှ Done ခေါ်မည်
                if (!mIsStopped.get()) {
                    callback.done()
                }
            }
        } catch (e: Exception) {
            // Error handling
        }
    }

    private suspend fun processStreamSynchronized(
        engine: TextToSpeech, 
        pkgName: String, 
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

        // Engine အလုပ်ပြီးမပြီး စောင့်ရန် Latch
        val engineDoneLatch = CountDownLatch(1)

        // 1. READER JOB (အသံဖတ်ပြီး System ကိုပို့)
        val readerJob = launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            val inputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val outputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE * 2).order(ByteOrder.LITTLE_ENDIAN) // Output Buffer needs to be larger
            val outputArray = ByteArray(BUFFER_SIZE * 2)

            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (isActive) {
                        // Pipe ထဲက Data လာမလာ စောင့်ဖတ်မယ်
                        val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }

                        if (bytesRead == -1) break // End of Stream (EOF)

                        if (bytesRead > 0) {
                            inputBuffer.clear()
                            inputBuffer.put(buffer, 0, bytesRead)
                            inputBuffer.flip()

                            // ★ Typing Fix: Data ဝင်လာတာနဲ့ ချက်ချင်း Process လုပ်မယ်
                            var processed = audioProcessor.process(inputBuffer, bytesRead, outputBuffer, outputBuffer.capacity())

                            while (processed > 0 && !mIsStopped.get()) {
                                outputBuffer.get(outputArray, 0, processed)
                                sendAudioToSystem(outputArray, processed, callback)
                                outputBuffer.clear()
                                processed = audioProcessor.process(inputBuffer, 0, outputBuffer, outputBuffer.capacity())
                            }
                        }
                    }
                    
                    // ★ Important Flush: Loop ပြီးသွားရင် Sonic ထဲမှာ ကျန်တာရှိသမျှ အကုန်ညှစ်ထုတ်မယ်
                    // ဒါမှ 'က', 'ခ' လို စာတိုလေးတွေ အသံထွက်မှာပါ
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

        // 2. WRITER JOB (Engine ကို စာရေးခိုင်း)
        val writerJob = launch(Dispatchers.IO) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) { 
                    engineDoneLatch.countDown()
                }
                override fun onError(id: String) { 
                    engineDoneLatch.countDown()
                }
            })
            
            try {
                // Engine ကို File Descriptor ထဲ ရေးခိုင်းမယ်
                engine.synthesizeToFile(text, params, writeFd, uuid)
                // Engine ပြီးတဲ့အထိ စောင့်မယ် (Writer မပိတ်ခင်)
                engineDoneLatch.await()
            } catch (e: Exception) {
            } finally {
                // Writer ပိတ်မှ Reader က EOF (-1) ရပြီး Loop ထွက်မှာ
                closeQuietly(writeFd)
                currentWriteFd.set(null)
            }
        }

        try {
            // ★ Critical: Writer ရော Reader ရော ပြီးတဲ့အထိ ဒီ Function က မထွက်ဘူး
            // ဒါကြောင့် စာမဆုံးခင် အသံပြတ်သွားတဲ့ ပြဿနာ (Cutoff) လုံးဝ မရှိတော့ဘူး
            joinAll(writerJob, readerJob)
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

