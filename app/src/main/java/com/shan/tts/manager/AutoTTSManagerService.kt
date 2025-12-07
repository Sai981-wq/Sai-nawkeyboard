package com.shan.tts.manager

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.content.Context
import android.os.Bundle
import java.util.Locale
import android.media.AudioAttributes
import android.util.Log

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
        
        // 1. Load Shan
        val shanPkg = prefs.getString("pref_shan_pkg", "com.espeak.ng")
        initializeEngine("SHAN", shanPkg) { tts -> 
            shanEngine = tts
            isShanReady = true 
        }

        // 2. Load Burmese
        val burmesePkg = prefs.getString("pref_burmese_pkg", "com.google.android.tts")
        initializeEngine("BURMESE", burmesePkg) { tts -> 
            burmeseEngine = tts
            isBurmeseReady = true
            burmeseEngine?.language = Locale("my", "MM")
        }

        // 3. Load English
        val englishPkg = prefs.getString("pref_english_pkg", "com.google.android.tts")
        initializeEngine("ENGLISH", englishPkg) { tts -> 
            englishEngine = tts
            isEnglishReady = true
            englishEngine?.language = Locale.US
        }
    }

    private fun initializeEngine(name: String, pkgName: String?, onSuccess: (TextToSpeech) -> Unit) {
        if (pkgName.isNullOrEmpty() || pkgName == packageName) return
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) onSuccess(tempTTS!!)
            }, pkgName)
        } catch (e: Exception) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
        // TalkBack Signal
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        try {
            val words = text.split(Regex("\\s+"))
            var currentBuffer = StringBuilder()
            var currentLang = ""

            for (word in words) {
                val detectedLang = LanguageUtils.detectLanguage(word)

                if (currentLang.isEmpty() || currentLang == detectedLang) {
                    currentLang = detectedLang
                    currentBuffer.append("$word ")
                } else {
                    flushAndSpeak(currentLang, currentBuffer.toString())
                    currentLang = detectedLang
                    currentBuffer = StringBuilder("$word ")
                }
            }
            if (currentBuffer.isNotEmpty()) {
                flushAndSpeak(currentLang, currentBuffer.toString())
            }

        } catch (e: Exception) { 
            Log.e("AutoTTS", "Error: ${e.message}")
        }

        callback?.done()
    }

    private fun flushAndSpeak(lang: String, textToSpeak: String) {
        if (textToSpeak.isBlank()) return

        when (lang) {
            "SHAN" -> speakPro(shanEngine, isShanReady, englishEngine, textToSpeak)
            "MYANMAR" -> speakPro(burmeseEngine, isBurmeseReady, englishEngine, textToSpeak)
            else -> speakPro(englishEngine, isEnglishReady, null, textToSpeak)
        }
    }

    private fun speakPro(primary: TextToSpeech?, isReady: Boolean, backup: TextToSpeech?, text: String) {
        
        val utteranceId = System.currentTimeMillis().toString()
        val params = Bundle()
        
        // *** FIX FOR BUILD ERROR ***
        // KEY_PARAM_AUDIO_ATTRIBUTES အစား "audioAttributes" စာသားကို တိုက်ရိုက်သုံးပါသည်
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            params.putParcelable("audioAttributes", audioAttributes)
        }

        if (isReady && primary != null) {
            val result = primary.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
            
            if (result == TextToSpeech.ERROR) {
                // Fallback (Plain speak)
                primary.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            }
        } 
        else if (backup != null) {
            backup.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        }
    }

    override fun onDestroy() {
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    
    override fun onStop() {
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
}

// Helper Object
object LanguageUtils {
    private val SHAN_PATTERN = Regex("[ႉႄႇႈၽၶၺႃၸၼဢၵႁဵႅၢႆႂႊ]")
    private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
    private val ENGLISH_PATTERN = Regex("[a-zA-Z]")

    fun detectLanguage(text: CharSequence?): String {
        if (text.isNullOrBlank()) return "ENGLISH"
        val input = text.toString()

        if (SHAN_PATTERN.containsMatchIn(input)) return "SHAN"
        if (MYANMAR_PATTERN.containsMatchIn(input)) return "MYANMAR"
        
        return "ENGLISH"
    }
}

