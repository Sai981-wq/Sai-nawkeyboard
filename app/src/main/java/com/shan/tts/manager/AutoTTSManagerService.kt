package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
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

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null
    private var shanPkg: String = ""
    private var burmesePkg: String = ""
    private var englishPkg: String = ""

    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "TTS_CONFIG"

    // Thread Lock
    private val lock = Any()
    @Volatile private var isStopped = false
    
    // Pipe Control
    private var currentReadFd: ParcelFileDescriptor? = null
    private var currentWriteFd: ParcelFileDescriptor? = null
    private var writerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defaultEngine = getDefaultEngineFallback()

            shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defaultEngine)
            initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it }

            burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defaultEngine)
            initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }

            englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defaultEngine)
            initTTS(englishPkg, Locale.US) { englishEngine = it }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onStop() {
        synchronized(lock) {
            isStopped = true
            // Stop လုပ်တာနဲ့ Pipe ကို ချက်ချင်း ရိုက်ချိုးမယ် (Deadlock ဖြေရှင်းရန်)
            try { currentReadFd?.close() } catch (e: Exception) {}
            currentReadFd = null
            
            try { currentWriteFd?.close() } catch (e: Exception) {}
            currentWriteFd = null
            
            try { writerThread?.interrupt() } catch (e: Exception) {}
        }
        
        // Engine တွေကိုလည်း Stop ခိုင်းမယ်
        try { shanEngine?.stop() } catch (e: Exception) {}
        try { burmeseEngine?.stop() } catch (e: Exception) {}
        try { englishEngine?.stop() } catch (e: Exception) {}
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        synchronized(lock) { isStopped = false }
        val text = request.charSequenceText.toString()

        // ★ UNLOCKED CONTROLS (ကန့်သတ်ချက်များ ဖြုတ်လိုက်ပြီ) ★
        // TalkBack သို့မဟုတ် Jieshuo က ပို့တဲ့ Rate/Pitch အတိုင်း အတိအကျ သုံးပါမယ်
        // 100 = 1.0x (Normal)
        val rate = request.speechRate / 100.0f
        val pitch = request.pitch / 100.0f

        // Engine Selection logic
        var targetEngine = englishEngine
        var targetPkg = englishPkg
        if (text.any { it.code in 0x1000..0x109F }) {
            if (text.any { it.code in 0x1075..0x108F }) { 
                targetEngine = shanEngine
                targetPkg = shanPkg
            } else {
                targetEngine = burmeseEngine
                targetPkg = burmesePkg
            }
        }
        if (targetEngine == null) { targetEngine = englishEngine; targetPkg = englishPkg }

        // Hz Selection (Default Safe Values)
        var hz = prefs.getInt("RATE_$targetPkg", 0)
        if (hz == 0) hz = if (targetPkg.contains("google")) 24000 else 22050

        try {
            // ★ APPLY SETTINGS INSTANTLY (ချက်ချင်း သက်ရောက်စေရန်) ★
            targetEngine!!.setSpeechRate(rate)
            targetEngine!!.setPitch(pitch)

            // Start Audio
            callback.start(hz, AudioFormat.ENCODING_PCM_16BIT, 1)

            // Create Pipe
            val pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]
            val uuid = UUID.randomUUID().toString()

            synchronized(lock) {
                if (isStopped) {
                    readFd.close()
                    writeFd.close()
                    return
                }
                currentReadFd = readFd
                currentWriteFd = writeFd
            }

            // Writer Thread
            writerThread = Thread {
                val params = Bundle()
                // Volume ကို Bundle ထဲမှာပါ ထည့်ပေးလိုက်တာက ပိုသေချာစေပါတယ်
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                try {
                    targetEngine!!.synthesizeToFile(text, params, writeFd, uuid)
                } catch (e: Exception) {
                    // Stop လုပ်ရင် ဒီမှာ Error တက်ပြီး ရပ်သွားမယ်
                } finally {
                    try { writeFd.close() } catch (e: Exception) {}
                }
            }
            writerThread?.start()

            // Reader Loop
            val buffer = ByteArray(8192)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (!isStopped) {
                        val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }
                        if (bytesRead == -1) break 
                        
                        if (bytesRead > 0) {
                            val max = callback.maxBufferSize
                            var offset = 0
                            while (offset < bytesRead) {
                                if (isStopped) break
                                val chunk = Math.min(bytesRead - offset, max)
                                val ret = callback.audioAvailable(buffer, offset, chunk)
                                if (ret == TextToSpeech.ERROR) {
                                    synchronized(lock) { isStopped = true }
                                    break
                                }
                                offset += chunk
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                synchronized(lock) {
                    try { readFd.close() } catch (e: Exception) {}
                    currentReadFd = null
                    if (writerThread != null && writerThread!!.isAlive) {
                         try { targetEngine!!.stop() } catch (e: Exception) {}
                    }
                }
                if (!isStopped) callback.done()
            }

        } catch (e: Exception) { e.printStackTrace() }
    }

    // --- Helpers ---
    private fun getDefaultEngineFallback(): String {
        return try {
            val tts = TextToSpeech(this, null)
            val p = tts.defaultEngine; tts.shutdown(); p ?: "com.google.android.tts"
        } catch (e: Exception) { "com.google.android.tts" }
    }
    private fun getPkg(key: String, list: List<String>, def: String): String {
        val p = prefs.getString(key, ""); 
        if (!p.isNullOrEmpty() && isInstalled(p)) return p
        for (i in list) if (isInstalled(i)) return i
        return def
    }
    private fun isInstalled(pkg: String): Boolean {
        return try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
    }
    private fun initTTS(pkg: String, loc: Locale, onReady: (TextToSpeech) -> Unit) {
        if (pkg.isEmpty()) return
        try {
            var t: TextToSpeech? = null
            t = TextToSpeech(this, { if (it == TextToSpeech.SUCCESS) onReady(t!!) }, pkg)
        } catch (e: Exception) {}
    }
    
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    
    override fun onDestroy() {
        super.onDestroy()
        onStop()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

