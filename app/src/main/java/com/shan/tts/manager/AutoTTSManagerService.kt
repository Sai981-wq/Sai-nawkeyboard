package com.shan.tts.manager

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.LinkedList
import java.util.Locale

data class TTSChunk(val text: String, val engine: TextToSpeech?, val lang: String, val sysRate: Int, val sysPitch: Int)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    private val messageQueue = LinkedList<TTSChunk>()
    
    @Volatile
    private var isSpeaking = false
    
    @Volatile
    private var stopRequested = false 
    
    private var currentLocale: Locale = Locale.US
    
    // စာလုံးရေ ၄၀၀ ပြည့်မှ ဖြတ်မယ် (ဒါမှ အသံမပြတ်ဘဲ ဆက်သွားမယ်)
    private val MAX_CHAR_LIMIT = 400 

    override fun onCreate() {
        super.onCreate()
        // WakeLock မပါပါ (ဖုန်းမပူအောင်)

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
                if (utteranceId == "SILENCE_KILLER") return
                isSpeaking = true 
            }
            
            override fun onDone(utteranceId: String?) { 
                if (utteranceId == "SILENCE_KILLER") return 
                isSpeaking = false 
                Handler(Looper.getMainLooper()).post { playNextInQueue() }
            }
            
            override fun onError(utteranceId: String?) { 
                isSpeaking = false 
                Handler(Looper.getMainLooper()).post { playNextInQueue() }
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
        
        stopAll() 
        stopRequested = false

        if (LanguageUtils.hasShan(text)) {
            parseShanMode(text, sysRate, sysPitch)
        } else {
            parseSmartMode(text, sysRate, sysPitch)
        }
        
        playNextInQueue()
        callback?.done()
    }

    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int) {
        try {
            // ရှမ်းစာကို Word by Word ခွဲပေမယ့် ချက်ချင်းမဖတ်ဘဲ Buffer ထဲစုမယ်
            val words = text.split(Regex("\\s+"))
            var currentBuffer = StringBuilder()
            var currentLang = ""

            for (word in words) {
                if (stopRequested) return 

                val detectedLang = LanguageUtils.detectLanguage(word)
                
                // Buffer 400 ကျော်မှသာ ဖြတ်ပြီး Queue ထဲထည့်မယ်
                if (currentBuffer.length > MAX_CHAR_LIMIT) {
                    addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch)
                    currentBuffer = StringBuilder()
                    currentLang = detectedLang 
                }

                if (currentLang.isEmpty() || currentLang == detectedLang) {
                    currentLang = detectedLang
                    currentBuffer.append("$word ")
                } else {
                    addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch)
                    currentBuffer = StringBuilder("$word ")
                    currentLang = detectedLang
                }
            }
            if (currentBuffer.isNotEmpty() && !stopRequested) {
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch)
            }
        } catch (e: Exception) { }
    }

    private fun parseSmartMode(text: String, sysRate: Int, sysPitch: Int) {
        if (text.isEmpty()) return

        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (char in text) {
            if (stopRequested) return 

            // *** အဓိက ပြင်ဆင်ချက် ***
            // New Line (\n) တွေ့တိုင်း မဖြတ်တော့ပါ။ 
            // Buffer ပြည့်ခါနီး (400 char) ဖြစ်ပြီး၊ ဖြတ်လို့ကောင်းတဲ့နေရာ (Space/Newline) ရောက်မှ ဖြတ်ပါမယ်။
            val isSeparator = char.isWhitespace() || ";.!?".contains(char)
            
            if (currentBuffer.length >= MAX_CHAR_LIMIT && isSeparator) {
                 // 400 လုံးပြည့်ပြီ၊ ဒီနေရာမှာ ဖြတ်လိုက်တော့
                 addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch)
                 currentBuffer = StringBuilder()
            }

            if (char.isWhitespace()) {
                currentBuffer.append(char)
                continue
            }

            val charType = LanguageUtils.getCharType(char)

            if (currentBuffer.isEmpty()) {
                currentLang = charType
                currentBuffer.append(char)
                continue
            }

            if (charType == currentLang) {
                currentBuffer.append(char)
            } else {
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch)
                currentLang = charType
                currentBuffer = StringBuilder()
                currentBuffer.append(char)
            }
        }
        if (currentBuffer.isNotEmpty() && !stopRequested) {
            addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch)
        }
    }

    private fun addToQueue(lang: String, text: String, sysRate: Int, sysPitch: Int) {
        if (text.isBlank()) return
        if (stopRequested) return 

        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
        messageQueue.add(TTSChunk(text, engine, lang, sysRate, sysPitch))
    }

    @Synchronized
    private fun playNextInQueue() {
        if (stopRequested) return
        if (isSpeaking) return
        if (messageQueue.isEmpty()) return
        
        val chunk = messageQueue.poll() ?: return
        val engine = chunk.engine
        val text = chunk.text
        
        if (engine == null) {
            playNextInQueue()
            return
        }
        
        isSpeaking = true

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

        val finalRate = (chunk.sysRate / 100.0f) * userRate
        val finalPitch = (chunk.sysPitch / 100.0f) * userPitch

        engine.setSpeechRate(finalRate)
        engine.setPitch(finalPitch)

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

        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
             isSpeaking = false
             playNextInQueue()
        }
    }

    private fun stopAll() {
        try {
            stopRequested = true 
            synchronized(messageQueue) { messageQueue.clear() }
            isSpeaking = false 
            
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f) 
            
            shanEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, "SILENCE_KILLER")
            burmeseEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, "SILENCE_KILLER")
            englishEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, "SILENCE_KILLER")

        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        stopAll()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    
    override fun onStop() { 
        stopAll() 
    }
    
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
            var cleanLang = lang ?: "en"
            if (cleanLang.equals("mya", true)) cleanLang = "my"
            if (cleanLang.equals("shn", true)) cleanLang = "shn"
            currentLocale = Locale(cleanLang, country ?: "", variant ?: "")
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
    fun hasShan(text: String): Boolean = SHAN_PATTERN.containsMatchIn(text)
    fun getCharType(char: Char): String {
        val text = char.toString()
        if (SHAN_PATTERN.containsMatchIn(text)) return "SHAN"
        if (MYANMAR_PATTERN.containsMatchIn(text)) return "MYANMAR"
        return "ENGLISH"
    }
    fun detectLanguage(text: CharSequence?): String {
        if (text.isNullOrBlank()) return "ENGLISH"
        val input = text.toString()
        if (SHAN_PATTERN.containsMatchIn(input)) return "SHAN"
        if (MYANMAR_PATTERN.containsMatchIn(input)) return "MYANMAR"
        return "ENGLISH"
    }
}

