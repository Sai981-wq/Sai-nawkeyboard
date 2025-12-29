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

    private val mIsStopped = AtomicBoolean(false)
    
    // Executor for background processing (Prevents Deadlock)
    private val writerExecutor = Executors.newSingleThreadExecutor()
    private var currentTask: Future<*>? = null

    private val currentReadFd = AtomicReference<ParcelFileDescriptor?>(null)
    private val currentWriteFd = AtomicReference<ParcelFileDescriptor?>(null)
    private var currentActiveEngine: TextToSpeech? = null

    private val BUFFER_SIZE = 8192 

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
        currentTask?.cancel(true)
        closeQuietly(currentWriteFd.getAndSet(null))
        closeQuietly(currentReadFd.getAndSet(null))
        try { currentActiveEngine?.stop() } catch (e: Exception) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        mIsStopped.set(false)
        val fullText = request.charSequenceText.toString()
        
        var rateVal = request.speechRate
        var pitchVal = request.pitch
        
        if (rateVal < 10) rateVal = 10
        if (pitchVal < 10) pitchVal = 10

        // ★ SPEED LIMITER (အဓိက ပြင်ဆင်ချက်) ★
        // Jieshuo က 500 (5x) ပို့ရင်တောင် Engine ကို 2.5x ထက် ပိုမပေးပါဘူး
        // ဒါမှ အသံမပျက်ဘဲ နားထောင်ကောင်းမှာပါ
        var rawRate = rateVal / 100.0f
        if (rawRate > 2.5f) rawRate = 2.5f 
        
        val enginePitch = pitchVal / 100.0f

        // Split text to prevent Deadlock on long sentences
        val sentenceChunks = fullText.split(Regex("(?<=[\\n.?!])\\s+"))

        var targetEngine = englishEngine
        var targetPkg = englishPkgName

        if (fullText.any { it.code in 0x1000..0x109F }) {
             if (fullText.any { it.code in 0x1075..0x108F }) { 
                 targetEngine = shanEngine
                 targetPkg = shanPkgName
             } else {
                 targetEngine = burmeseEngine
                 targetPkg = burmesePkgName
             }
        }
        if (targetEngine == null) {
            targetEngine = englishEngine
            targetPkg = englishPkgName
        }

        currentActiveEngine = targetEngine

        // Use scanned rate or fallback
        var outputRate = prefs.getInt("RATE_$targetPkg", 0)
        if (outputRate == 0) {
            outputRate = if (targetPkg.contains("google")) 24000 else 22050
        }

        try {
            targetEngine!!.setSpeechRate(rawRate)
            targetEngine!!.setPitch(enginePitch)

            callback.start(outputRate, AudioFormat.ENCODING_PCM_16BIT, 1)

            // Process chunks in background thread
            processWithExecutor(targetEngine!!, sentenceChunks, callback)
            
            if (!mIsStopped.get()) {
                callback.done()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processWithExecutor(
        engine: TextToSpeech, 
        sentences: List<String>, 
        callback: SynthesisCallback
    ) {
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd.set(readFd)
        currentWriteFd.set(writeFd)

        // Background Writer
        currentTask = writerExecutor.submit {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            try {
                for (sentence in sentences) {
                    if (Thread.currentThread().isInterrupted) break
                    if (sentence.isBlank()) continue
                    
                    // Synthesize chunk by chunk
                    engine.synthesizeToFile(sentence, params, writeFd, uuid)
                }
            } catch (e: Exception) {
            } finally {
                closeQuietly(writeFd)
                currentWriteFd.set(null)
            }
        }

        // Main Reader Loop
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                while (!mIsStopped.get()) {
                    val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }
                    if (bytesRead == -1) break 
                    
                    if (bytesRead > 0) {
                        sendToSystemSafely(buffer, bytesRead, callback)
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            closeQuietly(readFd)
            currentReadFd.set(null)
            currentTask?.cancel(true)
        }
    }

    private fun sendToSystemSafely(buffer: ByteArray, length: Int, callback: SynthesisCallback) {
        if (mIsStopped.get() || length <= 0) return
        
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        
        while (offset < length) {
            if (mIsStopped.get()) break 
            val remaining = length - offset
            val chunk = min(remaining, maxBufferSize)
            
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

    override fun onDestroy() {
        super.onDestroy()
        mIsStopped.set(true)
        writerExecutor.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
    }
}

