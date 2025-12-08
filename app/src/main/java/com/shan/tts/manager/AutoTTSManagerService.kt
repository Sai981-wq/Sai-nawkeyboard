package com.shan.tts.manager

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.LinkedList
import java.util.Locale

// Data Class
data class TTSChunk(val text: String, val engine: TextToSpeech?, val lang: String)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    private val messageQueue = LinkedList<TTSChunk>()
    private var isSpeaking = false
    
    private var currentLocale: Locale = Locale.US

    override fun onCreate() {
        super.onCreate()
        sendLog("Service Created.")

        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        initializeEngine(prefs.getString("pref_shan_pkg", "com.espeak.ng")) { tts -> 
            shanEngine = tts; isShanReady = true; setupListener(tts)
            sendLog("Shan Engine Ready")
        }
        initializeEngine(prefs.getString("pref_burmese_pkg", "com.google.android.tts")) { tts -> 
            burmeseEngine = tts; isBurmeseReady = true; setupListener(tts)
            burmeseEngine?.language = Locale("my", "MM")
            sendLog("Burmese Engine Ready")
        }
        initializeEngine(prefs.getString("pref_english_pkg", "com.google.android.tts")) { tts -> 
            englishEngine = tts; isEnglishReady = true; setupListener(tts)
            englishEngine?.language = Locale.US
            sendLog("English Engine Ready")
        }
    }

    private fun setupListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isSpeaking = true }
            override fun onDone(utteranceId: String?) { isSpeaking = false; playNextInQueue() }
            override fun onError(utteranceId: String?) { isSpeaking = false; playNextInQueue() }
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
        stopAll()
        parseAndQueue(text)
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
            if (currentBuffer.isNotEmpty()) addToQueue(currentLang, currentBuffer.toString())
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

    @Synchronized
    private fun playNextInQueue() {
        if (messageQueue.isEmpty()) return
        val chunk = messageQueue.poll() ?: return
        val engine = chunk.engine
        val text = chunk.text
        
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

        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
             engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    private fun stopAll() {
        try {
            messageQueue.clear()
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
    
    override fun onStop() { stopAll() }

    // =========================================================================
    // *** SYSTEM SPY MODE (အကုန်လိုက်ထောက်လှမ်းမည့် Log များ) ***
    // =========================================================================

    // System က "မင်းမှာ ဘာ Voice တွေရှိလဲ" လို့မေးရင် ဒီကောင်အလုပ်လုပ်ပါတယ်
    override fun onGetVoices(): List<Voice> {
        sendLog("System asking: onGetVoices()?") // Spy Log
        
        val voices = ArrayList<Voice>()
        voices.add(Voice("Shan (Myanmar)", Locale("shn", "MM"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("male")))
        voices.add(Voice("Burmese (Myanmar)", Locale("my", "MM"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("male")))
        voices.add(Voice("English (US)", Locale.US, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("female")))

        sendLog("Answer: I have ${voices.size} voices.") // Spy Log
        return voices
    }

    // System က "ဒီဘာသာစကား (lang) ကို မင်းရလား" လို့မေးရင် ဒီကောင်အလုပ်လုပ်ပါတယ်
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        // System မေးတာကို Log ထုတ်မယ်
        sendLog("System Check: Lang=$lang, Country=$country") 

        val checkLang = lang ?: return TextToSpeech.LANG_NOT_SUPPORTED

        var result = TextToSpeech.LANG_NOT_SUPPORTED

        // Checking Logic
        if (checkLang.contains("shn", ignoreCase = true) || checkLang.contains("shan", ignoreCase = true)) 
            result = TextToSpeech.LANG_COUNTRY_AVAILABLE
        else if (checkLang.contains("my", ignoreCase = true) || checkLang.contains("mya", ignoreCase = true)) 
            result = TextToSpeech.LANG_COUNTRY_AVAILABLE
        else if (checkLang.contains("en", ignoreCase = true) || checkLang.contains("eng", ignoreCase = true)) 
            result = TextToSpeech.LANG_COUNTRY_AVAILABLE
            
        // ကိုယ်ပြန်ဖြေတာကို Log ထုတ်မယ် (0, 1, 2 ဆို ရတယ် / -1, -2 ဆို မရဘူး)
        sendLog("My Answer for $lang: $result")
        return result
    }

    // System က "ကဲ ဒါဆို ဒီဘာသာစကား (lang) ကို Load လုပ်ကွာ" လို့ခိုင်းရင်
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        sendLog("System Load: $lang-$country") // Spy Log
        
        val status = onIsLanguageAvailable(lang, country, variant)
        if (status == TextToSpeech.LANG_COUNTRY_AVAILABLE || status == TextToSpeech.LANG_AVAILABLE) {
            currentLocale = Locale(lang ?: "en", country ?: "", variant ?: "")
            return status
        }
        
        sendLog("Load Failed for $lang")
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    // System က "မင်းလက်ရှိ ဘာသုံးနေလဲ" မေးရင်
    override fun onGetLanguage(): Array<String> {
        val langArr = try {
            arrayOf(currentLocale.isO3Language, currentLocale.isO3Country, "")
        } catch (e: Exception) {
            arrayOf("eng", "USA", "")
        }
        // sendLog("System asking current lang: ${langArr[0]}")
        return langArr
    }
    
    // Log ပို့ပေးမည့် Function
    private fun sendLog(msg: String) {
        try {
            LogHistory.add(msg) 
            val intent = Intent("com.shan.tts.ERROR_REPORT")
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } catch (e: Exception) { }
    }
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

