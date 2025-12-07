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

// *** Service Class ***
class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    override fun onCreate() {
        super.onCreate()
        sendLog("Service Created. Starting Init...")

        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        // 1. Load Shan Engine
        val shanPkg = prefs.getString("pref_shan_pkg", "com.espeak.ng")
        initializeEngine("SHAN", shanPkg) { tts -> 
            shanEngine = tts
            isShanReady = true 
        }

        // 2. Load Burmese Engine
        val burmesePkg = prefs.getString("pref_burmese_pkg", "com.google.android.tts")
        initializeEngine("BURMESE", burmesePkg) { tts -> 
            burmeseEngine = tts
            isBurmeseReady = true
            burmeseEngine?.language = Locale("my", "MM")
        }

        // 3. Load English Engine
        val englishPkg = prefs.getString("pref_english_pkg", "com.google.android.tts")
        initializeEngine("ENGLISH", englishPkg) { tts -> 
            englishEngine = tts
            isEnglishReady = true
            englishEngine?.language = Locale.US
        }
    }

    private fun initializeEngine(name: String, pkgName: String?, onSuccess: (TextToSpeech) -> Unit) {
        if (pkgName.isNullOrEmpty() || pkgName == packageName) {
            sendLog("$name: Invalid Package ($pkgName)")
            return
        }

        try {
            // Variable to hold TTS instance
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    onSuccess(tempTTS!!)
                    sendLog("$name Engine Ready: $pkgName")
                } else {
                    sendLog("$name Engine Failed to Init!")
                }
            }, pkgName)
        } catch (e: Exception) {
            sendLog("Crash Init $name: ${e.message}")
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        // Log အရှည်ကြီး မဖြစ်အောင် ပိတ်ထားနိုင်သည်
        // sendLog("Text received: $text")

        // TalkBack ကို အလုပ်လုပ်နေကြောင်းပြောခြင်း
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        try {
            // စကားလုံးခွဲခြင်း
            val words = text.split(Regex("\\s+"))

            for (word in words) {
                val lang = LanguageUtils.detectLanguage(word)
                val wordToSpeak = "$word " // အသံမပူးအောင် Space ခံခြင်း

                when (lang) {
                    "SHAN" -> speakSmart(shanEngine, isShanReady, englishEngine, wordToSpeak)
                    "MYANMAR" -> speakSmart(burmeseEngine, isBurmeseReady, englishEngine, wordToSpeak)
                    else -> speakSmart(englishEngine, isEnglishReady, null, wordToSpeak)
                }
            }
        } catch (e: Exception) {
            sendLog("Synthesize Error: ${e.message}")
        }

        // TalkBack ကို ပြီးပြီပြောခြင်း
        callback?.done()
    }

    private fun speakSmart(primary: TextToSpeech?, isReady: Boolean, backup: TextToSpeech?, text: String) {
        val utteranceId = System.currentTimeMillis().toString()
        
        // ၁။ Primary Engine နဲ့ အရင်စမ်းမယ်
        if (isReady && primary != null) {
            // Volume ကို Max တင်မယ် (Stream မရွေးတော့ဘူး၊ Crash သက်သာအောင်)
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            
            val result = primary.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
            
            // Error တက်ရင် Log ပို့မယ်
            if (result == TextToSpeech.ERROR) {
                sendLog("Primary Engine Error talking: $text")
                // Backup နဲ့ ပြန်ဖတ်ခိုင်းမယ်
                if (backup != null) backup.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            }
        } 
        // ၂။ Primary မရရင် Backup (English) နဲ့ ဖတ်မယ်
        else if (backup != null) {
            backup.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    // UI ကို Error လှမ်းပို့တဲ့ Function
    private fun sendLog(msg: String) {
        val intent = Intent("com.shan.tts.ERROR_REPORT")
        intent.putExtra("error_msg", msg)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        // System Log မှာလည်း ကြည့်လို့ရအောင်
        Log.d("ShanAutoTTS", msg)
    }

    override fun onDestroy() {
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    
    override fun onStop() {
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }

    // Android System က Language မေးရင် ဖြေဖို့
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
}

// *** Helper Object (ဖိုင်တစ်ခုတည်းပေါင်းထည့်ထားသည်) ***
object LanguageUtils {
    // ရှမ်းသီးသန့်စာလုံးများ
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

