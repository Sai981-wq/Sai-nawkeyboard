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
import kotlinx.coroutines.*
import java.io.IOException
import java.util.Locale
import java.util.UUID
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

    // ★ REMOVED FIXED RATE: 24000Hz အသေကို ဖြုတ်လိုက်ပါပြီ
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

        val engineRate = rateVal / 100.0f
        val enginePitch = pitchVal / 100.0f

        // Determine Target Engine & Package
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

        // ★ DYNAMIC HZ SELECTION ( Hz ပြဿနာ ဖြေရှင်းချက် ) ★
        // Engine Scan ဖတ်ထားတဲ့ Rate ကို ယူသုံးပါမယ်
        var nativeRate = prefs.getInt("RATE_$targetPkg", 0)
        
        // Scan မဖတ်ရသေးရင် Default ခန့်မှန်းမယ်
        if (nativeRate == 0) {
            nativeRate = if (targetPkg.contains("google")) 24000 else 22050
        }

        try {
            targetEngine!!.setSpeechRate(engineRate)
            targetEngine!!.setPitch(enginePitch)

            // ★ CRITICAL: Pass the REAL Native Rate to System ★
            // Engine က 22050 ထုတ်ရင် System ကို 22050 လို့ပြောမှ အသံမှန်ပါမယ်
            callback.start(nativeRate, AudioFormat.ENCODING_PCM_16BIT, 1)

            processDirectly(targetEngine!!, fullText, callback)
            
            if (!mIsStopped.get()) {
                callback.done()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processDirectly(
        engine: TextToSpeech, 
        text: String, 
        callback: SynthesisCallback
    ) {
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd.set(readFd)
        currentWriteFd.set(writeFd)

        val writerThread = Thread {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            try {
                engine.synthesizeToFile(text, params, writeFd, uuid)
            } catch (e: Exception) {
            } finally {
                closeQuietly(writeFd)
                currentWriteFd.set(null)
            }
        }
        writerThread.start()

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
            if (writerThread.isAlive) {
                try { engine.stop() } catch (e: Exception) {}
            }
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
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
    }
}

