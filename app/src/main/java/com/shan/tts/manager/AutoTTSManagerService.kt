package com.shan.tts.manager

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.LinkedList
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class TTSChunk(
    val text: String, 
    val engine: TextToSpeech?, 
    val lang: String,
    val sysRate: Int, 
    val sysPitch: Int,
    val sessionId: Long // Chunk တိုင်းမှာ ID ပါရမယ်
)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    private val messageQueue = java.util.Collections.synchronizedList(LinkedList<TTSChunk>())
    private var currentLocale: Locale = Locale.US
    
    private lateinit var workerThread: HandlerThread
    private lateinit var workHandler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    
    @Volatile
    private var isSpeaking = false
    
    // *** SESSION ID SYSTEM (The Professional Fix) ***
    // AtomicLong ကိုသုံးတာက Thread တွေကြားမှာ တိကျမှုရှိအောင်လို့ပါ
    private val currentSessionId = AtomicLong(0)
    
    private val MAX_CHAR_LIMIT = 500

    override fun onCreate() {
        super.onCreate()
        
        workerThread = HandlerThread("CherryTTSWorker", Process.THREAD_PRIORITY_AUDIO)
        workerThread.start()
        workHandler = Handler(workerThread.looper)
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherryTTS:WakeLock")
        wakeLock?.setReferenceCounted(false)

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
            override fun onStart(utteranceId: String?) { }
            
            override fun onDone(utteranceId: String?) {
                // Zero Latency: ချက်ချင်းဆက်ဖတ်မယ်
                workHandler.post {
                    isSpeaking = false
                    playNextInQueue()
                }
            }

            override fun onError(utteranceId: String?) {
                workHandler.post {
                    isSpeaking = false
                    playNextInQueue()
                }
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
        
        // ၁။ စာအသစ်ဝင်လာရင် Session ID အသစ်ထုတ်ပေးလိုက်မယ်
        // ဒီအချိန်ကစပြီး ID အဟောင်းနဲ့ run နေတဲ့အလုပ်မှန်သမျှ အလိုလိုရပ်သွားမယ်
        val mySessionId = currentSessionId.incrementAndGet()
        
        workHandler.post {
            // အလုပ်မစခင် ID စစ်မယ်
            if (mySessionId != currentSessionId.get()) return@post

            acquireWakeLock()
            
            // Parsing လုပ်တဲ့နေရာမှာ ID ထည့်ပေးလိုက်မယ်
            if (LanguageUtils.hasShan(text)) {
                parseShanMode(text, sysRate, sysPitch, mySessionId)
            } else {
                parseSmartMode(text, sysRate, sysPitch, mySessionId)
            }
            
            playNextInQueue()
        }
        
        callback?.done()
    }

    // *** TalkBack ပွတ်ဆွဲရင် ဒီကောင်ကိုခေါ်တယ် ***
    override fun onStop() {
        // ၁။ Session ID ကို တိုးလိုက်တာနဲ့ အလုပ်ဟောင်းတွေအားလုံး "Invalid" ဖြစ်သွားမယ်
        currentSessionId.incrementAndGet()
        
        // ၂။ Queue ကိုရှင်းမယ်
        synchronized(messageQueue) {
            messageQueue.clear()
        }
        
        // ၃။ အသံထွက်နေတာကို ချက်ချင်းရပ်မယ်
        workHandler.removeCallbacksAndMessages(null)
        workHandler.postAtFrontOfQueue {
            stopAllEnginesInternal()
        }
    }

    private fun acquireWakeLock() {
        wakeLock?.acquire(60 * 1000L)
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) { }
    }

    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int, sessionId: Long) {
        try {
            val words = text.split(Regex("\\s+"))
            var currentBuffer = StringBuilder()
            var currentLang = ""

            for (word in words) {
                // Loop ပတ်နေရင်း ID ပြောင်းသွားရင် (ပွတ်ဆွဲလိုက်ရင်) ချက်ချင်းရပ်မယ်
                if (sessionId != currentSessionId.get()) return

                val detectedLang = LanguageUtils.detectLanguage(word)
                if ((currentBuffer.length > MAX_CHAR_LIMIT) || (currentLang.isNotEmpty() && currentLang != detectedLang)) {
                    addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
                    currentBuffer = StringBuilder()
                    currentLang = detectedLang
                }
                if (currentLang.isEmpty()) currentLang = detectedLang
                currentBuffer.append("$word ")
            }
            if (currentBuffer.isNotEmpty()) addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
        } catch (e: Exception) { }
    }

    private fun parseSmartMode(text: String, sysRate: Int, sysPitch: Int, sessionId: Long) {
        if (text.isEmpty()) return

        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (char in text) {
            // Loop ပတ်နေရင်း ID ပြောင်းသွားရင် ချက်ချင်းရပ်မယ်
            if (sessionId != currentSessionId.get()) return

            if (char.isWhitespace()) {
                currentBuffer.append(char)
                if (currentBuffer.length > MAX_CHAR_LIMIT) {
                    addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
                    currentBuffer = StringBuilder()
                }
                continue
            }

            val charType = LanguageUtils.getCharType(char)

            if (currentBuffer.isEmpty()) {
                currentLang = charType
                currentBuffer.append(char)
                continue
            }

            if (charType == currentLang && currentBuffer.length < MAX_CHAR_LIMIT) {
                currentBuffer.append(char)
            } else {
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
                currentLang = charType
                currentBuffer = StringBuilder()
                currentBuffer.append(char)
            }
        }
        if (currentBuffer.isNotEmpty()) {
            addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
        }
    }

    private fun addToQueue(lang: String, text: String, sysRate: Int, sysPitch: Int, sessionId: Long) {
        // Queue ထဲမထည့်ခင် ID စစ်မယ်
        if (sessionId != currentSessionId.get()) return
        if (text.isBlank()) return

        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
        synchronized(messageQueue) {
            messageQueue.add(TTSChunk(text, engine, lang, sysRate, sysPitch, sessionId))
        }
    }

    @Synchronized
    private fun playNextInQueue() {
        if (isSpeaking) return
        
        var chunk: TTSChunk? = null
        synchronized(messageQueue) {
            if (messageQueue.isNotEmpty()) {
                // ထိပ်ဆုံးက အကောင်ကို ယူကြည့်မယ် (မထုတ်သေးဘူး)
                val peekChunk = messageQueue[0]
                
                // *** အရေးကြီးဆုံး ID စစ်ဆေးချက် ***
                // Queue ထဲက ကောင်ရဲ့ ID က လက်ရှိ ID နဲ့ မတူတော့ရင် (အဟောင်းဖြစ်နေရင်)
                // အဲ့ဒီအကောင်ကို လွှင့်ပစ်လိုက်မယ် (Skip)
                if (peekChunk.sessionId != currentSessionId.get()) {
                    messageQueue.removeAt(0)
                    playNextInQueue() // နောက်တစ်ကောင် ဆက်ကြည့်
                    return
                }
                
                // ID မှန်မှ Queue ထဲက ထုတ်မယ်
                chunk = messageQueue.removeAt(0)
            }
        }

        if (chunk == null) {
            releaseWakeLock()
            return
        }
        
        val engine = chunk!!.engine
        val text = chunk!!.text
        
        if (engine == null) {
            playNextInQueue()
            return
        }

        isSpeaking = true

        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        var userRate = 1.0f
        var userPitch = 1.0f

        when (chunk!!.lang) {
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

        engine.setSpeechRate((chunk!!.sysRate / 100.0f) * userRate)
        engine.setPitch((chunk!!.sysPitch / 100.0f) * userPitch)

        // UtteranceID ကို Watchdog အတွက် သုံးမယ်
        val utteranceId = UUID.randomUUID().toString()

        val params = Bundle()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            params.putParcelable("audioAttributes", audioAttributes)
        }
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)

        // Watchdog Timer (Engine ကြောင်ရင် ဖြတ်ချဖို့)
        val timeout = (text.length * 200L) + 4000L
        workHandler.postDelayed({
            if (isSpeaking) { // Speaking Flag မပြုတ်သေးရင်
                isSpeaking = false
                playNextInQueue()
            }
        }, utteranceId, timeout) 

        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        
        if (result == TextToSpeech.ERROR) {
             workHandler.removeCallbacksAndMessages(utteranceId)
             isSpeaking = false
             engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    private fun stopAllEnginesInternal() {
        try {
            isSpeaking = false 
            releaseWakeLock()
            shanEngine?.stop(); burmeseEngine?.stop(); englishEngine?.stop()
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        stopAllEnginesInternal()
        releaseWakeLock()
        workerThread.quitSafely()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
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
    
    fun hasShan(text: String): Boolean {
        return SHAN_PATTERN.containsMatchIn(text)
    }
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

