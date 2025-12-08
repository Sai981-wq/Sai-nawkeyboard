package com.shan.tts.manager

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.UtteranceProgressListener
import android.content.Context
import android.os.Bundle
import java.util.Locale
import java.util.LinkedList
import android.media.AudioAttributes
import android.util.Log

// Data Class to hold text chunks
data class TTSChunk(val text: String, val engine: TextToSpeech?, val lang: String)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    // *** CUSTOM QUEUE SYSTEM ***
    // အသံမထပ်အောင် စာတွေကို ဒီ List ထဲအရင်ထည့်ပြီး တစ်ခုပြီးမှတစ်ခု ထုတ်ဖတ်မယ်
    private val messageQueue = LinkedList<TTSChunk>()
    private var isSpeaking = false

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        initializeEngine(prefs.getString("pref_shan_pkg", "com.espeak.ng")) { tts -> 
            shanEngine = tts; isShanReady = true; setupListener(tts)
        }
        initializeEngine(prefs.getString("pref_burmese_pkg", "com.google.android.tts")) { tts -> 
            burmeseEngine = tts; isBurmeseReady = true; setupListener(tts)
            burmeseEngine?.language = Locale("my", "MM")
        }
        initializeEngine(prefs.getString("pref_english_pkg", "com.google.android.tts")) { tts -> 
            englishEngine = tts; isEnglishReady = true; setupListener(tts)
            englishEngine?.language = Locale.US
        }
    }

    // Engine တိုင်းမှာ Listener တပ်ဆင်ခြင်း (ပြီးမပြီး နားထောင်ဖို့)
    private fun setupListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                // ရှေ့အကောင် ဖတ်ပြီးပြီ၊ နောက်တစ်ကောင် ဆက်ဖတ်!
                isSpeaking = false
                playNextInQueue()
            }

            override fun onError(utteranceId: String?) {
                // Error တက်လည်း နောက်တစ်ကောင် ဆက်ဖတ်
                isSpeaking = false
                playNextInQueue()
            }
        })
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

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // ၁။ အသစ်လာရင် အဟောင်းတွေကို အကုန်ရှင်းပစ်မယ် (Stop All)
        stopAll()
        
        // ၂။ စာသားကို ခွဲပြီး Queue ထဲ အရင်ထည့်မယ် (မဖတ်သေးဘူး)
        parseAndQueue(text)

        // ၃။ ပထမဆုံးအလုံးကို စဖတ်မယ်
        playNextInQueue()

        callback?.done()
    }

    private fun parseAndQueue(text: String) {
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
                    addToQueue(currentLang, currentBuffer.toString())
                    currentLang = detectedLang
                    currentBuffer = StringBuilder("$word ")
                }
            }
            if (currentBuffer.isNotEmpty()) {
                addToQueue(currentLang, currentBuffer.toString())
            }
        } catch (e: Exception) { }
    }

    private fun addToQueue(lang: String, text: String) {
        if (text.isBlank()) return
        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
        messageQueue.add(TTSChunk(text, engine, lang))
    }

    // *** THE SEQUENCER (တစ်ခုပြီးမှ တစ်ခုဖတ်စေသူ) ***
    @Synchronized
    private fun playNextInQueue() {
        if (messageQueue.isEmpty()) return

        // ယူမယ်၊ ဒါပေမယ့် မဖျက်သေးဘူး (Processing လုပ်နေတုန်း)
        val chunk = messageQueue.poll() ?: return

        val engine = chunk.engine
        val text = chunk.text
        
        // Engine မရှိရင် ကျော်ပြီး နောက်တစ်ခုဆက်လုပ်
        if (engine == null) {
            playNextInQueue()
            return
        }

        val utteranceId = System.nanoTime().toString()
        val params = Bundle()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            params.putParcelable("audioAttributes", audioAttributes)
        }
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)

        // Engine ကို ဖတ်ခိုင်းလိုက်ပြီ
        // Listener က ပြီးမှ playNextInQueue() ကို ပြန်ခေါ်လိမ့်မယ်
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        
        // Error တက်ရင် Fallback
        if (result == TextToSpeech.ERROR) {
             engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    private fun stopAll() {
        try {
            messageQueue.clear() // တန်းစီထားတာတွေ ဖျက်မယ်
            shanEngine?.stop()
            burmeseEngine?.stop()
            englishEngine?.stop()
            isSpeaking = false
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        stopAll(); shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    
    override fun onStop() {
        stopAll()
    }

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

