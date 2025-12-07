package com.shan.tts.manager

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.content.Context
import android.os.Bundle
import java.util.Locale
import android.media.AudioManager

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    override fun onCreate() {
        super.onCreate()
        sendErrorToUI("Service Created. Loading Engines...")

        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        try {
            val shanPkg = prefs.getString("pref_shan_pkg", "com.espeak.ng")
            initializeEngine("Shan", shanPkg) { engine -> shanEngine = engine; isShanReady = true }

            val burmesePkg = prefs.getString("pref_burmese_pkg", "com.google.android.tts")
            initializeEngine("Burmese", burmesePkg) { engine -> 
                burmeseEngine = engine; isBurmeseReady = true; burmeseEngine?.language = Locale("my", "MM") 
            }

            val englishPkg = prefs.getString("pref_english_pkg", "com.google.android.tts")
            initializeEngine("English", englishPkg) { engine -> 
                englishEngine = engine; isEnglishReady = true; englishEngine?.language = Locale.US 
            }
        } catch (e: Exception) {
            sendErrorToUI("Init Crash: ${e.message}")
        }
    }

    private fun initializeEngine(name: String, pkgName: String?, onSuccess: (TextToSpeech) -> Unit) {
        if (pkgName.isNullOrEmpty() || pkgName == packageName) return
        
        try {
            lateinit var temp: TextToSpeech
            temp = TextToSpeech(applicationContext, { status -> 
                if (status == TextToSpeech.SUCCESS) {
                    onSuccess(temp)
                    sendErrorToUI("$name Ready ($pkgName)")
                } else {
                    sendErrorToUI("$name Failed to Init")
                }
            }, pkgName)
        } catch (e: Exception) {
             // Ignore
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
        // Debug: စာဝင်လာမလာ သိရအောင် Log ထုတ်ကြည့်မယ်
        // sendErrorToUI("Reading: $text") 

        // TalkBack Stream
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        try {
            val words = text.split(Regex("\\s+"))
            for (word in words) {
                val lang = LanguageUtils.detectLanguage(word)
                val wordToSpeak = "$word "

                when (lang) {
                    "SHAN" -> speakOrFallback(shanEngine, isShanReady, englishEngine, wordToSpeak)
                    "MYANMAR" -> speakOrFallback(burmeseEngine, isBurmeseReady, englishEngine, wordToSpeak)
                    else -> speakWord(englishEngine, wordToSpeak)
                }
            }
        } catch (e: Exception) {
             sendErrorToUI("Synthesize Error: ${e.message}")
        }

        callback?.done()
    }

    private fun speakOrFallback(primary: TextToSpeech?, isReady: Boolean, backup: TextToSpeech?, text: String) {
        if (isReady && primary != null) {
            speakWord(primary, text)
        } else {
            if (backup != null) speakWord(backup, text)
            else sendErrorToUI("Engine Not Ready for: $text")
        }
    }

    private fun speakWord(engine: TextToSpeech?, text: String) {
        if (engine == null) return

        val utteranceId = System.currentTimeMillis().toString()

        // ၁။ အသံကျယ်ကျယ် (Accessibility Stream) နဲ့ အရင်စမ်းမယ်
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ACCESSIBILITY)

        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)

        // ၂။ Error တက်ရင် Params မပါဘဲ ပြန်ဖတ်မယ် (Smart Retry)
        if (result == TextToSpeech.ERROR) {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    private fun sendErrorToUI(msg: String) {
        val intent = Intent("com.shan.tts.ERROR_REPORT")
        intent.putExtra("error_msg", msg)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    
    override fun onStop() {
        shanEngine?.stop(); burmeseEngine?.stop(); englishEngine?.stop()
    }

    // *** အဓိက ပြင်ဆင်ချက် (Language Fix) ***
    // Android System က "မင်းဒီဘာသာစကားရလား" မေးရင် "အကုန်ရတယ်" လို့ ဖြေမှ စာပို့မှာပါ
    
    override fun onGetLanguage(): Array<String> {
        return arrayOf("eng", "USA", "") // Default Language
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        // ဘာလာမေးမေး Yes (Available) လို့ ဖြေမယ်
        return TextToSpeech.LANG_COUNTRY_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        // ဘာလာလာ လက်ခံမယ်
        return TextToSpeech.LANG_COUNTRY_AVAILABLE
    }
}

