package com.shan.tts.manager

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.content.Context
import android.os.Bundle
import java.util.Locale
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
        sendLog("Service Created.")

        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        // 1. Shan
        val shanPkg = prefs.getString("pref_shan_pkg", "com.espeak.ng")
        initializeEngine("SHAN", shanPkg) { tts -> 
            shanEngine = tts
            isShanReady = true 
        }

        // 2. Burmese
        val burmesePkg = prefs.getString("pref_burmese_pkg", "com.google.android.tts")
        initializeEngine("BURMESE", burmesePkg) { tts -> 
            burmeseEngine = tts
            isBurmeseReady = true
            burmeseEngine?.language = Locale("my", "MM")
        }

        // 3. English
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
                if (status == TextToSpeech.SUCCESS) {
                    onSuccess(tempTTS!!)
                    sendLog("$name Ready: $pkgName")
                } else {
                    sendLog("$name Failed ($pkgName)")
                }
            }, pkgName)
        } catch (e: Exception) {
            sendLog("Crash Init $name")
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
        // TalkBack Signal
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        try {
            // စကားလုံးခွဲခြင်း
            val words = text.split(Regex("\\s+"))

            for (word in words) {
                val lang = LanguageUtils.detectLanguage(word)
                val wordToSpeak = "$word " 

                when (lang) {
                    "SHAN" -> speakSafe(shanEngine, isShanReady, englishEngine, wordToSpeak)
                    "MYANMAR" -> speakSafe(burmeseEngine, isBurmeseReady, englishEngine, wordToSpeak)
                    else -> speakSafe(englishEngine, isEnglishReady, null, wordToSpeak)
                }
            }
        } catch (e: Exception) {
            sendLog("Error: ${e.message}")
        }

        callback?.done()
    }

    // *** အဓိက ပြင်ဆင်ချက် (Safe Mode) ***
    // Bundle/Params လုံးဝ မထည့်တော့ပါ (Null)။ ဒါမှ Engine တိုင်း လက်ခံမှာပါ။
    private fun speakSafe(primary: TextToSpeech?, isReady: Boolean, backup: TextToSpeech?, text: String) {
        val utteranceId = System.currentTimeMillis().toString()
        
        if (isReady && primary != null) {
            // Params နေရာမှာ null ထားလိုက်ပါပြီ။
            val result = primary.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            
            // တကယ်လို့ Error တက်ခဲ့ရင် Backup နဲ့ ဖတ်မယ်
            if (result == TextToSpeech.ERROR && backup != null) {
                sendLog("Primary Failed -> Backup")
                backup.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            }
        } else if (backup != null) {
            backup.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    private fun sendLog(msg: String) {
        // Log အများကြီးမတက်အောင် လိုအပ်မှ ဖွင့်ပါ
        // val intent = Intent("com.shan.tts.ERROR_REPORT")
        // intent.putExtra("error_msg", msg)
        // intent.setPackage(packageName)
        // sendBroadcast(intent)
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
        if (ENGLISH_PATTERN.containsMatchIn(input)) return "ENGLISH"

        return "ENGLISH"
    }
}

