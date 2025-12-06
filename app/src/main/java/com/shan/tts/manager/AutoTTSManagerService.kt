package com.shan.tts.manager

import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.UtteranceProgressListener
import android.content.Context
import android.os.Bundle
import java.util.Locale

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
        val burmesePkg = prefs.getString("pref_burmese_pkg", "com.google.android.tts")
        val englishPkg = prefs.getString("pref_english_pkg", "com.google.android.tts")

        // 1. Shan Engine
        initializeEngine(shanPkg) { engine -> 
            shanEngine = engine
            isShanReady = true
        }

        // 2. Burmese Engine
        initializeEngine(burmesePkg) { engine -> 
            burmeseEngine = engine
            isBurmeseReady = true
            burmeseEngine?.language = Locale("my", "MM")
        }

        // 3. English Engine
        initializeEngine(englishPkg) { engine -> 
            englishEngine = engine
            isEnglishReady = true
            englishEngine?.language = Locale.US
        }
    }

    private fun initializeEngine(pkgName: String?, onSuccess: (TextToSpeech) -> Unit) {
        if (pkgName.isNullOrEmpty()) return
        try {
            var tempEngine: TextToSpeech? = null
            tempEngine = TextToSpeech(this, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    onSuccess(tempEngine!!)
                }
            }, pkgName)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val detectedLang = LanguageUtils.detectLanguage(text)
        val utteranceId = "uid_${System.currentTimeMillis()}"

        // TalkBack Needs Start Signal
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        when (detectedLang) {
            "SHAN" -> {
                if (isShanReady) speakWithEngine(shanEngine, text, utteranceId, callback)
                else speakWithEngine(englishEngine, text, utteranceId, callback)
            }
            "MYANMAR" -> {
                if (isBurmeseReady) speakWithEngine(burmeseEngine, text, utteranceId, callback)
                else speakWithEngine(englishEngine, text, utteranceId, callback)
            }
            else -> {
                if (isEnglishReady) speakWithEngine(englishEngine, text, utteranceId, callback)
                else callback?.done()
            }
        }
    }

    private fun speakWithEngine(engine: TextToSpeech?, text: String, utId: String, callback: SynthesisCallback?) {
        if (engine == null) {
            callback?.done()
            return
        }

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utId)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            
            override fun onDone(utteranceId: String?) {
                callback?.done()
            }
            
            override fun onError(utteranceId: String?) {
                callback?.done()
            }
        })

        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utId)
    }

    override fun onDestroy() {
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        super.onDestroy()
    }
    
    // Standard implementations
    override fun onStop() {
        shanEngine?.stop()
        burmeseEngine?.stop()
        englishEngine?.stop()
    }
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
}
