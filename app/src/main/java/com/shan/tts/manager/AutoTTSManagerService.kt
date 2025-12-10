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
import java.util.concurrent.atomic.AtomicInteger

// Data Class မှာ sessionID ထပ်ဖြည့်ထားပါတယ်
data class TTSChunk(
    val text: String,
    val engine: TextToSpeech?,
    val lang: String,
    val sysRate: Int,
    val sysPitch: Int,
    val utteranceId: String,
    val sessionId: Int // *** NEW: ဒီစာသားက ဘယ် Session ကလဲ ဆိုတာမှတ်မယ် ***
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
    private var currentUtteranceId: String = ""

    @Volatile
    private var isEngineBusy = false

    // *** KEY FIX: Session ID (ပွတ်ဆွဲတိုင်း ကိန်းဂဏန်းတိုးသွားမယ်) ***
    private val currentSessionId = AtomicInteger(0)

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
            override fun onStart(utteranceId: String?) {
                isEngineBusy = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == currentUtteranceId) {
                    isEngineBusy = false
                    workHandler.post { playNextInQueue() }
                }
            }

            override fun onError(utteranceId: String?) {
                isEngineBusy = false
                workHandler.post { playNextInQueue() }
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

        // TalkBack ကို အလုပ်လုပ်နေပြီလို့ အကြောင်းကြား
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // လက်ရှိ Session ID ကို မှတ်ထားမယ်
        val mySessionId = currentSessionId.get()

        workHandler.post {
            // *** CHECK 1: အလုပ်မစခင် ID စစ်မယ်။ အကယ်၍ ID မတူတော့ရင် (ပွတ်ဆွဲလိုက်ရင်) ဒီအလုပ်ကို ပယ်ဖျက်မယ် ***
            if (mySessionId != currentSessionId.get()) {
                callback?.done()
                return@post
            }

            acquireWakeLock()

            if (text.length < 20) {
                // Short text optimization
                val lang = LanguageUtils.detectLanguage(text)
                addToQueue(text, lang, sysRate, sysPitch, mySessionId)
            } else {
                if (LanguageUtils.hasShan(text)) {
                    parseShanMode(text, sysRate, sysPitch, mySessionId)
                } else {
                    parseSmartMode(text, sysRate, sysPitch, mySessionId)
                }
            }

            // နောက်ဆုံး Play ခါနီးမှာလည်း ID ထပ်စစ်မယ်
            if (mySessionId == currentSessionId.get()) {
                playNextInQueue()
            }
        }
        callback?.done()
    }

    // *** TalkBack Stop: ဒီနေရာက အရေးအကြီးဆုံးပါ ***
    override fun onStop() {
        // ၁။ Session ID ကို တိုးလိုက်မယ်။ ဒါဆိုရင် Queue ထဲမှာ ကျန်ခဲ့တဲ့ (ID အဟောင်းနဲ့) စာတွေ အကုန် Invalid ဖြစ်သွားမယ်
        currentSessionId.incrementAndGet()
        
        // ၂။ Busy Flag ဖြုတ်မယ်
        isEngineBusy = false

        // ၃။ Queue ရှင်းမယ်
        synchronized(messageQueue) {
            messageQueue.clear()
        }
        
        // ၄။ Handler ထဲက Pending Task တွေကို ဖျက်မယ်
        workHandler.removeCallbacksAndMessages(null)

        // ၅။ Engine တွေကို ချက်ချင်းရပ်မယ်
        stopEnginesDirectly()
        
        releaseWakeLock()
    }

    private fun stopEnginesDirectly() {
        try {
            currentUtteranceId = "" 
            if (isShanReady) shanEngine?.stop()
            if (isBurmeseReady) burmeseEngine?.stop()
            if (isEnglishReady) englishEngine?.stop()
        } catch (e: Exception) { }
    }

    // Parse Function တွေမှာ Session ID ထည့်ပေးရမယ်
    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int, sessionId: Int) {
        try {
            val words = text.split(Regex("\\s+"))
            var currentBuffer = StringBuilder()
            var currentLang = ""

            for (word in words) {
                // Loop ပတ်နေတုန်း ပွတ်ဆွဲလိုက်ရင် ID မတူတော့တဲ့အတွက် ထွက်သွားမယ်
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
            if (currentBuffer.isNotEmpty() && sessionId == currentSessionId.get()) {
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
            }
        } catch (e: Exception) { }
    }

    private fun parseSmartMode(text: String, sysRate: Int, sysPitch: Int, sessionId: Int) {
        if (text.isEmpty()) return
        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (char in text) {
            if (sessionId != currentSessionId.get()) return

            if (char.isWhitespace()) {
                currentBuffer.append(char)
                if (currentBuffer.length > MAX_CHAR_LIMIT) {
                    addToQueue(currentBuffer.toString(), currentLang, sysRate, sysPitch, sessionId)
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
                addToQueue(currentBuffer.toString(), currentLang, sysRate, sysPitch, sessionId)
                currentLang = charType
                currentBuffer = StringBuilder()
                currentBuffer.append(char)
            }
        }
        if (currentBuffer.isNotEmpty() && sessionId == currentSessionId.get()) {
            addToQueue(currentBuffer.toString(), currentLang, sysRate, sysPitch, sessionId)
        }
    }

    private fun addToQueue(text: String, lang: String, sysRate: Int, sysPitch: Int, sessionId: Int) {
        // Queue ထဲထည့်ခါနီးမှာ ID အဟောင်းဖြစ်နေရင် မထည့်တော့ဘူး
        if (sessionId != currentSessionId.get() || text.isBlank()) return

        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
        val uId = UUID.randomUUID().toString()
        synchronized(messageQueue) {
            // Chunk ထဲမှာ sessionId ပါ ထည့်မှတ်လိုက်တယ်
            messageQueue.add(TTSChunk(text, engine, lang, sysRate, sysPitch, uId, sessionId))
        }
    }

    @Synchronized
    private fun playNextInQueue() {
        // Stop လုပ်ထားရင် (သို့) ID ပြောင်းသွားရင် မပြောတော့ဘူး
        if (currentSessionId.get() == -1) { // Just a sanity check
            releaseWakeLock()
            return
        }
        
        if (isEngineBusy) {
            return
        }

        var chunk: TTSChunk? = null
        synchronized(messageQueue) {
            if (messageQueue.isNotEmpty()) {
                chunk = messageQueue.removeAt(0)
            }
        }

        if (chunk == null) {
            releaseWakeLock()
            return
        }

        // *** SUPER IMPORTANT FIX ***
        // Queue ထဲက ဆွဲထုတ်လိုက်တဲ့ စာသားဟာ လက်ရှိ Session နဲ့ မတူတော့ရင် (ဆိုလိုတာက ပွတ်ဆွဲပြီးသား စာဟောင်းဆိုရင်)
        // အသံမထွက်ဘဲ ကျော်လိုက်မယ်။
        if (chunk!!.sessionId != currentSessionId.get()) {
            // Recursive call to process next item immediately (flush old items)
            playNextInQueue()
            return
        }

        val engine = chunk!!.engine
        val text = chunk!!.text
        val uId = chunk!!.utteranceId
        currentUtteranceId = uId

        if (engine == null) {
            playNextInQueue()
            return
        }
        
        isEngineBusy = true

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

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            params.putParcelable("audioAttributes", audioAttributes)
        }

        val timeout = (text.length * 200L) + 3000L
        workHandler.postDelayed({
             if (isEngineBusy && currentUtteranceId == uId) {
                 isEngineBusy = false
                 playNextInQueue()
             }
        }, timeout)

        engine.speak(text, TextToSpeech.QUEUE_ADD, params, uId)
    }

    override fun onDestroy() {
        stopEnginesDirectly()
        releaseWakeLock()
        workerThread.quitSafely()
        shanEngine?.setOnUtteranceProgressListener(null)
        burmeseEngine?.setOnUtteranceProgressListener(null)
        englishEngine?.setOnUtteranceProgressListener(null)
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    
    // ... (WakeLock methods)
    private fun acquireWakeLock() { wakeLock?.acquire(60 * 1000L) }
    private fun releaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock?.release() }

    // ... (Language Methods - No Change)
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

