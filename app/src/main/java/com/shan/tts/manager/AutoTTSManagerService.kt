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
            override fun onError(utteranceId: String?) { 
                sendLog("Engine Error on utterance: $utteranceId")
                isSpeaking = false; playNextInQueue() 
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
        } catch (e: Exception) { 
            sendLog("Init Crash: ${e.message}")
        }
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
                // ဒီနေရာမှာ LanguageUtils ကို အောက်ဆုံးမှာ ပြန်ထည့်ထားလို့ Error မတက်တော့ပါ
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
        } catch (e: Exception) { 
            sendLog("Parse Error: ${e.message}")
        }
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
            sendLog("Engine NULL for text: $text")
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
             // Fallback
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

    private fun sendLog(msg: String) {
        try {
            // LogHistory ရှိမှ ခေါ်မယ် (Error မတက်အောင် Try catch ခံထားသည်)
            LogHistory.add(msg) 
            val intent = Intent("com.shan.tts.ERROR_REPORT")
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e("AutoTTS", "Log Error: $msg")
        }
    }

    override fun onDestroy() {
        stopAll(); shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    
    override fun onStop() { stopAll() }

    // Language Settings
    override fun onGetVoices(): List<Voice> {
        val voices = ArrayList<Voice>()
        voices.add(Voice("Shan Language", Locale("shn"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("male")))
        voices.add(Voice("Burmese Language", Locale("my", "MM"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("male")))
        voices.add(Voice("English Language", Locale.US, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("female")))
        return voices
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        currentLocale = if (lang != null) Locale(lang) else Locale.US
        return TextToSpeech.LANG_COUNTRY_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
}

// *** LANGUAGE UTILS (Included Here to Prevent Unresolved Reference) ***
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

