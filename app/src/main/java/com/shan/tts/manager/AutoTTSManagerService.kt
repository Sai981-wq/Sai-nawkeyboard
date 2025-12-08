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
        
        initializeEngine(prefs.getString("pref_shan_pkg", "com.espeak.ng")) { tts -> 
            shanEngine = tts
            isShanReady = true 
        }

        initializeEngine(prefs.getString("pref_burmese_pkg", "com.google.android.tts")) { tts -> 
            burmeseEngine = tts
            isBurmeseReady = true
            burmeseEngine?.language = Locale("my", "MM")
        }

        initializeEngine(prefs.getString("pref_english_pkg", "com.google.android.tts")) { tts -> 
            englishEngine = tts
            isEnglishReady = true
            englishEngine?.language = Locale.US
        }
    }

    private fun initializeEngine(pkgName: String?, onSuccess: (TextToSpeech) -> Unit) {
        if (pkgName.isNullOrEmpty() || pkgName == packageName) return
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) onSuccess(tempTTS!!)
            }, pkgName)
        } catch (e: Exception) { }
    }

    // *** အရေးကြီးဆုံး ပြင်ဆင်ချက် (၁) - STOP ***
    // TalkBack က ရပ်ခိုင်းတာနဲ့ ချက်ချင်း အသံတိတ်ပစ်ရမယ်။
    // ဒါမှ YouTube ရောက်ရင် Facebook အသံမထွက်တော့မှာ။
    override fun onStop() {
        try {
            shanEngine?.stop()
            burmeseEngine?.stop()
            englishEngine?.stop()
        } catch (e: Exception) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
        // TalkBack Signal
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        try {
            // အသစ်ဝင်လာပြီဆိုတာနဲ့ အရင်အသံဟောင်းတွေကို အရင်ဖြတ်မယ်
            onStop()

            val words = text.split(Regex("\\s+"))
            var currentBuffer = StringBuilder()
            var currentLang = ""
            
            // စာကြောင်းရဲ့ ပထမဆုံး အပိုင်းဖြစ်ကြောင်း မှတ်သားခြင်း
            var isFirstChunk = true 

            for (word in words) {
                val detectedLang = LanguageUtils.detectLanguage(word)

                if (currentLang.isEmpty() || currentLang == detectedLang) {
                    currentLang = detectedLang
                    currentBuffer.append("$word ")
                } else {
                    // အရင်စုထားတာကို ဖတ်မယ်
                    flushAndSpeak(currentLang, currentBuffer.toString(), isFirstChunk)
                    
                    // ပထမဆုံးအသုတ် ဖတ်ပြီးသွားပြီမို့ False ပြောင်းမယ်
                    if (currentBuffer.isNotEmpty()) isFirstChunk = false
                    
                    // အသစ်ပြန်စမယ်
                    currentLang = detectedLang
                    currentBuffer = StringBuilder("$word ")
                }
            }
            
            if (currentBuffer.isNotEmpty()) {
                flushAndSpeak(currentLang, currentBuffer.toString(), isFirstChunk)
            }

        } catch (e: Exception) { 
            Log.e("AutoTTS", "Error: ${e.message}")
        }

        callback?.done()
    }

    private fun flushAndSpeak(lang: String, textToSpeak: String, isFirstChunk: Boolean) {
        if (textToSpeak.isBlank()) return

        when (lang) {
            "SHAN" -> speakPro(shanEngine, isShanReady, englishEngine, textToSpeak, isFirstChunk)
            "MYANMAR" -> speakPro(burmeseEngine, isBurmeseReady, englishEngine, textToSpeak, isFirstChunk)
            else -> speakPro(englishEngine, isEnglishReady, null, textToSpeak, isFirstChunk)
        }
    }

    private fun speakPro(primary: TextToSpeech?, isReady: Boolean, backup: TextToSpeech?, text: String, isFirstChunk: Boolean) {
        val utteranceId = System.currentTimeMillis().toString()
        val params = Bundle()

        // Audio Attributes (Volume Correction)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            params.putParcelable("audioAttributes", audioAttributes)
        }

        // *** အရေးကြီးဆုံး ပြင်ဆင်ချက် (၂) - QUEUE MODE ***
        // စာကြောင်းအသစ်ရဲ့ ပထမဆုံးအစဆိုရင် QUEUE_FLUSH (အဟောင်းတွေဖျက်၊ ဒါကိုချက်ချင်းဖတ်) သုံးမယ်။
        // နောက်ဆက်တွဲစာသားဆိုရင် QUEUE_ADD (ဆက်ဖတ်) သုံးမယ်။
        val queueMode = if (isFirstChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

        if (isReady && primary != null) {
            val result = primary.speak(text, queueMode, params, utteranceId)
            if (result == TextToSpeech.ERROR) {
                primary.speak(text, queueMode, null, utteranceId)
            }
        } 
        else if (backup != null) {
            backup.speak(text, queueMode, params, utteranceId)
        }
    }

    override fun onDestroy() {
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }

    // System Language Requests
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
}

// Helper Object
object LanguageUtils {
    private val SHAN_PATTERN = Regex("[ႉႄႇႈၽၶၺႃၸၼဢၵႁဵႅၢႆႂႊ]")
    private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
    
    fun detectLanguage(text: CharSequence?): String {
        if (text.isNullOrBlank()) return "ENGLISH"
        val input = text.toString()

        if (SHAN_PATTERN.containsMatchIn(input)) return "SHAN"
        if (MYANMAR_PATTERN.containsMatchIn(input)) return "MYANMAR"
        
        return "ENGLISH"
    }
}

