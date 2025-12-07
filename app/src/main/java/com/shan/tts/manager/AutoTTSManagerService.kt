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
        
        // 1. Shan Engine
        val shanPkg = prefs.getString("pref_shan_pkg", "com.espeak.ng")
        initializeEngine("SHAN", shanPkg) { tts -> 
            shanEngine = tts
            isShanReady = true 
        }

        // 2. Burmese Engine
        val burmesePkg = prefs.getString("pref_burmese_pkg", "com.google.android.tts")
        initializeEngine("BURMESE", burmesePkg) { tts -> 
            burmeseEngine = tts
            isBurmeseReady = true
            burmeseEngine?.language = Locale("my", "MM")
        }

        // 3. English Engine
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
            val words = text.split(Regex("\\s+")) // Space ဖြင့်ခွဲသည်
            
            var currentBuffer = StringBuilder() // စာကြောင်းပေါင်းမည့်ပုံး
            var currentLang = "" // လက်ရှိဘာသာစကား

            for (word in words) {
                // ဒီစကားလုံးက ဘာစာလဲ စစ်မယ်
                val detectedLang = LanguageUtils.detectLanguage(word)

                // ပထမဆုံးစလုံး (သို့) ဘာသာစကား တူနေသေးရင် ပုံးထဲထည့်ပေါင်းမယ်
                if (currentLang.isEmpty() || currentLang == detectedLang) {
                    currentLang = detectedLang
                    currentBuffer.append("$word ")
                } else {
                    // ဘာသာစကား ပြောင်းသွားပြီ (ဥပမာ English ကနေ Shan ဖြစ်သွားပြီ)
                    // ၁။ အရင် စုထားတဲ့ စာတွေကို အရင်ဖတ်မယ်
                    flushAndSpeak(currentLang, currentBuffer.toString())
                    
                    // ၂။ ပုံးကို ရှင်းပြီး အသစ်ပြန်စမယ်
                    currentLang = detectedLang
                    currentBuffer = StringBuilder("$word ")
                }
            }

            // ကျန်နေတဲ့ နောက်ဆုံးစာကြောင်းကို ဖတ်မယ်
            if (currentBuffer.isNotEmpty()) {
                flushAndSpeak(currentLang, currentBuffer.toString())
            }

        } catch (e: Exception) {
            // Error handling
        }

        callback?.done()
    }

    // စုထားတဲ့ စာကြောင်းကို သက်ဆိုင်ရာ Engine ဆီပို့ခြင်း
    private fun flushAndSpeak(lang: String, textToSpeak: String) {
        if (textToSpeak.isBlank()) return

        when (lang) {
            "SHAN" -> speakSmart(shanEngine, isShanReady, englishEngine, textToSpeak)
            "MYANMAR" -> speakSmart(burmeseEngine, isBurmeseReady, englishEngine, textToSpeak)
            else -> speakSmart(englishEngine, isEnglishReady, null, textToSpeak)
        }
    }

    // အသံကျယ်ကျယ်ထွက်အောင် Volume ညှိပေးခြင်း + Error ကာကွယ်ခြင်း
    private fun speakSmart(primary: TextToSpeech?, isReady: Boolean, backup: TextToSpeech?, text: String) {
        val utteranceId = System.currentTimeMillis().toString()
        
        // Primary Engine နဲ့ စမ်းမယ်
        if (isReady && primary != null) {
            
            // ၁။ Accessibility Stream (TalkBack Volume) နဲ့ အရင်စမ်းမယ်
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ACCESSIBILITY)
            
            val result = primary.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
            
            // ၂။ Error တက်ခဲ့ရင် (Eloquence လိုကောင်တွေအတွက်) Params မပါဘဲ ပြန်ဖတ်မယ်
            if (result == TextToSpeech.ERROR) {
                primary.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            }
        } 
        // Backup (English) နဲ့ ဖတ်မယ်
        else if (backup != null) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ACCESSIBILITY)
            
            val result = backup.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
            if (result == TextToSpeech.ERROR) {
                 backup.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            }
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
        // English စာလုံးတစ်လုံးချင်းစီကို သိပ်မစစ်ဆေးတော့ဘဲ Default အနေနဲ့ထားလိုက်မယ်
        
        return "ENGLISH"
    }
}

