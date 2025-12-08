package com.shan.tts.manager

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.LinkedList
import java.util.Locale

// Data Class
data class TTSChunk(
    val text: String, 
    val engine: TextToSpeech?, 
    val lang: String,
    val sysRate: Int, 
    val sysPitch: Int
)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    private val messageQueue = LinkedList<TTSChunk>()
    
    // Volatile သုံးထားတာက Thread တွေကြားမှာ တိကျစေဖို့ပါ
    @Volatile
    private var isSpeaking = false
    
    private var currentLocale: Locale = Locale.US

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

    private fun setupListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { 
                // onStart မှာ true မလုပ်တော့ပါ (playNextInQueue မှာ လုပ်ပြီးသားမို့လို့)
            }

            override fun onDone(utteranceId: String?) { 
                // ပြီးမှပဲ False ပြန်ပေးပြီး နောက်တစ်ခု ဆွဲထုတ်မယ်
                isSpeaking = false
                playNextInQueue() 
            }

            override fun onError(utteranceId: String?) { 
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
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
        
        // အသစ်လာရင် အကုန်ရပ်မယ်
        stopAll()
        
        parseAndQueue(text, sysRate, sysPitch)
        playNextInQueue()
        
        callback?.done()
    }

    private fun parseAndQueue(text: String, sysRate: Int, sysPitch: Int) {
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
                    addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch)
                    currentLang = detectedLang
                    currentBuffer = StringBuilder("$word ")
                }
            }
            if (currentBuffer.isNotEmpty()) addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch)
        } catch (e: Exception) { }
    }

    private fun addToQueue(lang: String, text: String, sysRate: Int, sysPitch: Int) {
        if (text.isBlank()) return
        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
        messageQueue.add(TTSChunk(text, engine, lang, sysRate, sysPitch))
    }

    @Synchronized
    private fun playNextInQueue() {
        // ၁။ တကယ်လို့ ပြောနေတုန်းဆိုရင် ထပ်မလွှတ်ဘူး (Strict Lock)
        if (isSpeaking) return
        
        if (messageQueue.isEmpty()) return
        
        // Queue ထဲက ဆွဲထုတ်မယ်
        val chunk = messageQueue.poll() ?: return
        val engine = chunk.engine
        val text = chunk.text
        
        if (engine == null) {
            playNextInQueue()
            return
        }

        // ၂။ *** အရေးကြီးဆုံး ပြင်ဆင်ချက် ***
        // Engine ကို speak မခေါ်ခင်ကတည်းက "ပြောနေပြီ" လို့ မှတ်လိုက်မယ်
        // ဒါမှ နောက်ကောင် ဝင်မလာမှာ
        isSpeaking = true

        // Rate & Pitch Config
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        var userRate = 1.0f
        var userPitch = 1.0f

        when (chunk.lang) {
            "SHAN" -> {
                userRate = prefs.getInt("rate_shan", 100) / 100.0f
                userPitch = prefs.getInt("pitch_shan", 100) / 100.0f
            }
            "MYANMAR" -> {
                userRate = prefs.getInt("rate_burmese", 100) / 100.0f
                userPitch = prefs.getInt("pitch_burmese", 100) / 100.0f
            }
            else -> {
                userRate = prefs.getInt("rate_english", 100) / 100.0f
                userPitch = prefs.getInt("pitch_english", 100) / 100.0f
            }
        }

        engine.setSpeechRate((chunk.sysRate / 100.0f) * userRate)
        engine.setPitch((chunk.sysPitch / 100.0f) * userPitch)

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

        // ၃။ Queue Mode ကို ADD သုံးပေမယ့်၊ ကျွန်တော်တို့က Manual Queue လုပ်ထားလို့ ပြဿနာမရှိပါ
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        
        // ၄။ Error တက်ခဲ့ရင်တော့ ပြန်ဖွင့်ပေးရမယ်
        if (result == TextToSpeech.ERROR) {
             isSpeaking = false
             // Retry with plain params
             engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    private fun stopAll() {
        try {
            messageQueue.clear()
            // Stop လုပ်ရင် Flag ကို ချပေးရမယ်
            isSpeaking = false 
            shanEngine?.stop(); burmeseEngine?.stop(); englishEngine?.stop()
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        stopAll(); shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    
    override fun onStop() { stopAll() }

    // Language Settings Section
    override fun onGetVoices(): List<Voice> {
        val voices = ArrayList<Voice>()
        voices.add(Voice("Shan (Myanmar)", Locale("shn", "MM"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("male")))
        voices.add(Voice("Burmese (Myanmar)", Locale("my", "MM"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("male")))
        voices.add(Voice("English (US)", Locale.US, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("female")))
        return voices
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val checkLang = lang ?: return TextToSpeech.LANG_NOT_SUPPORTED
        if (checkLang.contains("shn", true) || checkLang.contains("shan", true)) return TextToSpeech.LANG_COUNTRY_AVAILABLE
        if (checkLang.contains("my", true) || checkLang.contains("mya", true)) return TextToSpeech.LANG_COUNTRY_AVAILABLE
        if (checkLang.contains("en", true) || checkLang.contains("eng", true)) return TextToSpeech.LANG_COUNTRY_AVAILABLE
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val status = onIsLanguageAvailable(lang, country, variant)
        if (status != TextToSpeech.LANG_NOT_SUPPORTED) {
            currentLocale = Locale(lang ?: "en", country ?: "", variant ?: "")
            return status
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return try { arrayOf(currentLocale.isO3Language, currentLocale.isO3Country, "") } catch (e: Exception) { arrayOf("eng", "USA", "") }
    }
}

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

