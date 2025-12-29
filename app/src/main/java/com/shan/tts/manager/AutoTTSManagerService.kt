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
    
    private var shanPkg = ""
    private var burmesePkg = ""
    private var englishPkg = ""

    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "TTS_CONFIG"

    // Stop Flag (ရိုးရိုး Flag ပဲ သုံးပါတော့မယ်)
    @Volatile private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defEngine = getDefaultEngineFallback()

            shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defEngine)
            initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it }

            burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defEngine)
            initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }

            englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defEngine)
            initTTS(englishPkg, Locale.US) { englishEngine = it }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ★ CLEAN STOP (ရိုက်ချိုးစရာမလို၊ Flag တင်ပြီး Engine ရပ်ရုံပဲ) ★
    override fun onStop() {
        isStopped = true
        try { shanEngine?.stop() } catch (e: Exception) {}
        try { burmeseEngine?.stop() } catch (e: Exception) {}
        try { englishEngine?.stop() } catch (e: Exception) {}
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        isStopped = false
        val text = request.charSequenceText.toString()

        // 1. Get Rate/Pitch from System (TalkBack/Jieshuo)
        val rate = request.speechRate / 100.0f
        val pitch = request.pitch / 100.0f

        // 2. Split Text using TTSUtils (Deadlock မဖြစ်စေတဲ့ အဓိကသော့ချက်)
        // စာကို အပိုင်းသေးလေးတွေ ခွဲလိုက်လို့ Engine က ခဏလေးနဲ့ ရေးပြီးသွားမယ်
        val chunks = TTSUtils.splitHelper(text)
        if (chunks.isEmpty()) {
            callback.done()
            return
        }

        // 3. Determine Hz from the first chunk's language
        val firstLang = chunks[0].lang
        var targetPkg = when (firstLang) {
            "SHAN" -> shanPkg
            "MYANMAR" -> burmesePkg
            else -> englishPkg
        }
        
        var hz = prefs.getInt("RATE_$targetPkg", 0)
        if (hz == 0) hz = if (targetPkg.contains("google")) 24000 else 22050

        try {
            // Start Audio
            callback.start(hz, AudioFormat.ENCODING_PCM_16BIT, 1)

            val pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]
            val uuid = UUID.randomUUID().toString()

            // ★ WRITER THREAD ★
            val writerThread = Thread {
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                
                try {
                    for (chunk in chunks) {
                        // Clean Check: ရပ်ခိုင်းရင် Loop ထဲက ထွက်မယ် (Pipe ချိုးစရာမလို)
                        if (isStopped) break

                        val engine = when (chunk.lang) {
                            "SHAN" -> shanEngine
                            "MYANMAR" -> burmeseEngine
                            else -> englishEngine
                        } ?: englishEngine

                        engine?.setSpeechRate(rate)
                        engine?.setPitch(pitch)

                        // Write to Pipe
                        // Chunk သေးလို့ ဒီအဆင့်က မြန်မြန်ပြီးတယ်၊ Block မဖြစ်ဘူး
                        engine?.synthesizeToFile(chunk.text, params, writeFd, uuid)
                    }
                } catch (e: Exception) {
                    // Ignore errors
                } finally {
                    // Writing ပြီးရင် Pipe ပိတ်ပေးရမယ်
                    try { writeFd.close() } catch (e: Exception) {}
                }
            }
            writerThread.start()

            // ★ READER LOOP ★
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
                // Ensure writer stops if reader exits early
                if (writerThread.isAlive) {
                    try { shanEngine?.stop() } catch (e: Exception) {}
                    try { burmeseEngine?.stop() } catch (e: Exception) {}
                    try { englishEngine?.stop() } catch (e: Exception) {}
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

