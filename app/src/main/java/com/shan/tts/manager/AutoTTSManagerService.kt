package com.shan.tts.manager

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
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        val shanPkg = prefs.getString("pref_shan_pkg", "com.espeak.ng")
        initializeEngine(shanPkg) { engine -> 
            shanEngine = engine
            isShanReady = true 
        }

        val burmesePkg = prefs.getString("pref_burmese_pkg", "com.google.android.tts")
        initializeEngine(burmesePkg) { engine -> 
            burmeseEngine = engine
            isBurmeseReady = true
            burmeseEngine?.language = Locale("my", "MM")
        }

        val englishPkg = prefs.getString("pref_english_pkg", "com.google.android.tts")
        initializeEngine(englishPkg) { engine -> 
            englishEngine = engine
            isEnglishReady = true
            englishEngine?.language = Locale.US
        }
    }

    private fun initializeEngine(pkgName: String?, onSuccess: (TextToSpeech) -> Unit) {
        if (pkgName.isNullOrEmpty()) return
        try {
            lateinit var temp: TextToSpeech
            temp = TextToSpeech(this, { status -> 
                if (status == TextToSpeech.SUCCESS) onSuccess(temp) 
            }, pkgName)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        val words = text.split(Regex("\\s+"))

        for (word in words) {
            val lang = LanguageUtils.detectLanguage(word)
            val wordToSpeak = "$word "

            when (lang) {
                "SHAN" -> speakWord(shanEngine, isShanReady, wordToSpeak)
                "MYANMAR" -> speakWord(burmeseEngine, isBurmeseReady, wordToSpeak)
                else -> speakWord(englishEngine, isEnglishReady, wordToSpeak)
            }
        }

        callback?.done()
    }

    private fun speakWord(engine: TextToSpeech?, isReady: Boolean, text: String) {
        if (engine != null && isReady) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ACCESSIBILITY)
            engine.speak(text, TextToSpeech.QUEUE_ADD, params, null)
        }
    }

    override fun onDestroy() {
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        super.onDestroy()
    }
    
    override fun onStop() {
        shanEngine?.stop()
        burmeseEngine?.stop()
        englishEngine?.stop()
    }
    
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
}

