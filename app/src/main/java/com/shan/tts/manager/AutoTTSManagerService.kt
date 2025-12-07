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
        sendErrorToUI("Service Started")

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
            sendErrorToUI("Init Error: ${e.message}")
        }
    }

    private fun initializeEngine(name: String, pkgName: String?, onSuccess: (TextToSpeech) -> Unit) {
        if (pkgName.isNullOrEmpty()) {
            sendErrorToUI("$name engine package is empty!")
            return
        }
        
        try {
            lateinit var temp: TextToSpeech
            temp = TextToSpeech(applicationContext, { status -> 
                if (status == TextToSpeech.SUCCESS) {
                    onSuccess(temp)
                    sendErrorToUI("$name Engine Loaded: $pkgName")
                } else {
                    sendErrorToUI("Failed to load $name ($pkgName)")
                }
            }, pkgName)
        } catch (e: Exception) {
            sendErrorToUI("Crash loading $name: ${e.message}")
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
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
            sendErrorToUI("Synthesis Error: ${e.message}")
        }

        callback?.done()
    }

    private fun speakOrFallback(primary: TextToSpeech?, isReady: Boolean, backup: TextToSpeech?, text: String) {
        if (isReady && primary != null) {
            speakWord(primary, text)
        } else {
            // Error မပြတော့ဘဲ အသံထွက်အောင် Backup နဲ့ ဖတ်ခိုင်းခြင်း
            if (backup != null) speakWord(backup, text)
            else sendErrorToUI("No Engine available to speak: $text")
        }
    }

    private fun speakWord(engine: TextToSpeech?, text: String) {
        if (engine != null) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ACCESSIBILITY)
            val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, null)
            
            if (result == TextToSpeech.ERROR) {
                sendErrorToUI("Engine Speak Error")
            }
        }
    }

    // Error များကို Activity သို့ ပို့ပေးသော Function
    private fun sendErrorToUI(msg: String) {
        val intent = Intent("com.shan.tts.ERROR_REPORT")
        intent.putExtra("error_msg", msg)
        intent.setPackage(packageName) // လုံခြုံရေးအတွက် ကိုယ့် App ကိုပဲ ပို့မယ်
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    
    override fun onStop() {
        shanEngine?.stop(); burmeseEngine?.stop(); englishEngine?.stop()
    }
    
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
}

