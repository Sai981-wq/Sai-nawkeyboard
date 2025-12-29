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
import kotlin.concurrent.thread

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkg: String = ""
    private var burmesePkg: String = ""
    private var englishPkg: String = ""

    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "TTS_CONFIG"

    // ပိုက်လိုင်းများကို ထိန်းချုပ်ရန် (Stop လုပ်ရင် ပိတ်ပစ်မယ်)
    @Volatile private var currentReadFd: ParcelFileDescriptor? = null
    @Volatile private var currentWriteFd: ParcelFileDescriptor? = null
    
    // Stop Flag
    @Volatile private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defaultEngine = getDefaultEngineFallback()

            // Engine Setup (ရိုးရှင်းအောင် Package Name ရှာပြီး တန်းချိတ်မယ်)
            shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defaultEngine)
            initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it }

            burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defaultEngine)
            initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }

            englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defaultEngine)
            initTTS(englishPkg, Locale.US) { englishEngine = it }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ★ SIMPLE STOP LOGIC ★
    override fun onStop() {
        isStopped = true
        // Pipe တွေကို ချက်ချင်းပိတ်မယ် (Engine က ရေးမရတော့လို့ ချက်ချင်းရပ်သွားမယ်)
        try { currentReadFd?.close() } catch (e: Exception) {}
        try { currentWriteFd?.close() } catch (e: Exception) {}
        currentReadFd = null
        currentWriteFd = null
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        isStopped = false
        val text = request.charSequenceText.toString()
        
        // ★ 1. RATE CONTROL (အသံမြန်တာကို ထိန်းချုပ်ခြင်း) ★
        // Jieshuo က 500% ပို့လည်း 2.5x (Safe Zone) ထက် ပိုမပေးပါ
        var rate = request.speechRate / 100.0f
        if (rate < 0.1f) rate = 0.1f
        if (rate > 2.5f) rate = 2.5f // MAX CAP

        val pitch = request.pitch / 100.0f

        // ★ 2. ENGINE SELECTION (ရိုးရှင်းသော ရွေးချယ်မှု) ★
        var targetEngine = englishEngine
        var targetPkg = englishPkg
        
        // Check for Shan/Burmese Characters
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

        // ★ 3. HZ SELECTION (Hz မှန်အောင် Scan တန်ဖိုးယူခြင်း) ★
        var hz = prefs.getInt("RATE_$targetPkg", 0)
        if (hz == 0) hz = if (targetPkg.contains("google")) 24000 else 22050

        // Process Audio
        try {
            targetEngine!!.setSpeechRate(rate)
            targetEngine!!.setPitch(pitch)

            callback.start(hz, AudioFormat.ENCODING_PCM_16BIT, 1)
            
            // Simple Chunking to prevent blocking on long text
            val chunks = text.split(Regex("(?<=[\\n.?!])\\s+"))
            
            for (chunk in chunks) {
                if (isStopped) break
                if (chunk.isBlank()) continue
                processChunk(targetEngine!!, chunk, callback)
            }
            
            if (!isStopped) callback.done()

        } catch (e: Exception) { e.printStackTrace() }
    }

    // ★ 4. DIRECT PIPE (အရှင်းဆုံး ပိုက်လိုင်းစနစ်) ★
    private fun processChunk(engine: TextToSpeech, text: String, callback: SynthesisCallback) {
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd = readFd
        currentWriteFd = writeFd

        // Writer Thread (Engine writes to Pipe)
        val writerThread = thread(start = true) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            try {
                // This blocks until done or pipe broken
                engine.synthesizeToFile(text, params, writeFd, uuid)
            } catch (e: Exception) { 
                // Ignore "Broken Pipe" errors on Stop
            } finally {
                try { writeFd.close() } catch (e: Exception) {}
            }
        }

        // Reader Loop (Main Thread reads from Pipe and sends to System)
        val buffer = ByteArray(8192)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                while (!isStopped) {
                    val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }
                    if (bytesRead == -1) break // EOF
                    
                    if (bytesRead > 0) {
                        // Safe Send with Loop (Vnspeak Style)
                        val max = callback.maxBufferSize
                        var offset = 0
                        while (offset < bytesRead && !isStopped) {
                            val chunk = Math.min(bytesRead - offset, max)
                            val ret = callback.audioAvailable(buffer, offset, chunk)
                            if (ret == TextToSpeech.ERROR) {
                                isStopped = true
                                break
                            }
                            offset += chunk
                        }
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            // Cleanup
            try { readFd.close() } catch (e: Exception) {}
            currentReadFd = null
            currentWriteFd = null
            if (writerThread.isAlive) {
                try { engine.stop() } catch (e: Exception) {}
            }
        }
    }

    // --- Helper Functions (Boilerplate) ---
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
        isStopped = true
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

