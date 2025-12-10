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
import java.util.concurrent.atomic.AtomicBoolean

data class TTSChunk(
    val text: String,
    val engine: TextToSpeech?,
    val lang: String,
    val sysRate: Int,
    val sysPitch: Int,
    val utteranceId: String
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

    // *** KEY FIX 1: အင်ဂျင် မအားမချင်း နောက်တစ်ခု မလွှတ်ရန် Flag ***
    @Volatile
    private var isEngineBusy = false

    // *** KEY FIX 2: ချက်ချင်းရပ်တန့်ရန် Flag ***
    private val isInterrupted = AtomicBoolean(false)

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
                // အသံစထွက်ပြီ၊ Busy ဖြစ်နေပြီ
                isEngineBusy = true
            }

            override fun onDone(utteranceId: String?) {
                // *** အသံထပ်ခြင်း ပြဿနာ ဖြေရှင်းချက် ***
                // တစ်ခုပြီးမှ Busy ကို ဖြုတ်မယ်၊ ပြီးမှ နောက်တစ်ခုခေါ်မယ်
                if (utteranceId == currentUtteranceId) {
                    isEngineBusy = false
                    workHandler.post { playNextInQueue() }
                }
            }

            override fun onError(utteranceId: String?) {
                // Error တက်ရင်လည်း Busy ဖြုတ်ပြီး နောက်တစ်ခုသွားမယ်
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

        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // အသစ်ဝင်လာတာနဲ့ Stop Flag အဟောင်းတွေကို ရှင်းမယ်
        isInterrupted.set(false)
        
        workHandler.post {
            if (isInterrupted.get()) {
                callback?.done()
                return@post
            }

            acquireWakeLock()

            // စာတိုလေးတွေဆိုရင် Parsing မလုပ်ဘဲ တိုက်ရိုက်လွှတ် (ပိုမြန်စေရန်)
            if (text.length < 20) {
                 val lang = LanguageUtils.detectLanguage(text)
                 addToQueue(text, lang, sysRate, sysPitch)
            } else {
                if (LanguageUtils.hasShan(text)) {
                    parseShanMode(text, sysRate, sysPitch)
                } else {
                    parseSmartMode(text, sysRate, sysPitch)
                }
            }

            // Parsing ပြီးလို့ အောက်ရောက်လာရင် Play မယ်
            if (!isInterrupted.get()) {
                playNextInQueue()
            }
        }
        callback?.done()
    }

    // *** TalkBack Latency Fix: Stop လုပ်တာနဲ့ အကုန်ဖြတ်ချမယ် ***
    override fun onStop() {
        // ၁။ Loop တွေကို ရပ်ခိုင်းမယ်
        isInterrupted.set(true)
        
        // ၂။ Busy နေတာတွေကို အတင်းဖြုတ်မယ်
        isEngineBusy = false 

        // ၃။ Queue ရှင်းမယ်
        synchronized(messageQueue) {
            messageQueue.clear()
        }
        
        // ၄။ Handler ထဲကဟာတွေ ဖျက်မယ်
        workHandler.removeCallbacksAndMessages(null)

        // ၅။ Engine တွေကို အသံချက်ချင်း ပိတ်ခိုင်းမယ် (Thread မစောင့်ဘဲ Main Thread ကနေ ပိတ်မယ်)
        stopEnginesDirectly()
        
        releaseWakeLock()
    }

    private fun stopEnginesDirectly() {
        try {
            currentUtteranceId = "" 
            // Engine တွေကို stop ခေါ်လိုက်ရင် onDone ပြန်ဝင်လာတတ်တယ်
            // ဒါကြောင့် isEngineBusy = false ကို အပေါ်မှာ ကြိုလုပ်ခဲ့တာပါ
            if (isShanReady) shanEngine?.stop()
            if (isBurmeseReady) burmeseEngine?.stop()
            if (isEnglishReady) englishEngine?.stop()
        } catch (e: Exception) { }
    }

    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int) {
        try {
            val words = text.split(Regex("\\s+"))
            var currentBuffer = StringBuilder()
            var currentLang = ""

            for (word in words) {
                if (isInterrupted.get()) return

                val detectedLang = LanguageUtils.detectLanguage(word)
                if ((currentBuffer.length > MAX_CHAR_LIMIT) || (currentLang.isNotEmpty() && currentLang != detectedLang)) {
                    addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch)
                    currentBuffer = StringBuilder()
                    currentLang = detectedLang
                }
                if (currentLang.isEmpty()) currentLang = detectedLang
                currentBuffer.append("$word ")
            }
            if (currentBuffer.isNotEmpty() && !isInterrupted.get()) {
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch)
            }
        } catch (e: Exception) { }
    }

    private fun parseSmartMode(text: String, sysRate: Int, sysPitch: Int) {
        if (text.isEmpty()) return
        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (char in text) {
            if (isInterrupted.get()) return

            if (char.isWhitespace()) {
                currentBuffer.append(char)
                if (currentBuffer.length > MAX_CHAR_LIMIT) {
                    addToQueue(currentBuffer.toString(), currentLang, sysRate, sysPitch)
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
                addToQueue(currentBuffer.toString(), currentLang, sysRate, sysPitch)
                currentLang = charType
                currentBuffer = StringBuilder()
                currentBuffer.append(char)
            }
        }
        if (currentBuffer.isNotEmpty() && !isInterrupted.get()) {
            addToQueue(currentBuffer.toString(), currentLang, sysRate, sysPitch)
        }
    }

    private fun addToQueue(text: String, lang: String, sysRate: Int, sysPitch: Int) {
        if (isInterrupted.get() || text.isBlank()) return

        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
        val uId = UUID.randomUUID().toString()
        synchronized(messageQueue) {
            messageQueue.add(TTSChunk(text, engine, lang, sysRate, sysPitch, uId))
        }
    }

    @Synchronized
    private fun playNextInQueue() {
        // ၁။ Stop လုပ်ထားရင် မလွှတ်ဘူး
        if (isInterrupted.get()) {
            releaseWakeLock()
            return
        }
        
        // ၂။ *** အသံထပ်ခြင်း ကာကွယ်ရန် ***
        // တခြား Engine တစ်ခုခု ပြောနေသေးရင် စောင့်မယ် (onDone က ပြန်ခေါ်ပေးလိမ့်မယ်)
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

        val engine = chunk!!.engine
        val text = chunk!!.text
        val uId = chunk!!.utteranceId
        currentUtteranceId = uId

        if (engine == null) {
            playNextInQueue() // Skip and play next
            return
        }
        
        // စပြီဆိုတာနဲ့ Busy လို့ မှတ်မယ်
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

        // Watchdog: Engine ကြောင်ပြီး onDone မပြန်ရင် ရှင်းထုတ်ဖို့ (3 စက္ကန့် ပိုပေးမယ်)
        val timeout = (text.length * 200L) + 3000L
        workHandler.postDelayed({
             // အချိန်လွန်တဲ့ထိ Busy ဖြစ်နေတုန်း၊ ID လည်းတူတုန်းဆိုရင် Reset ချမယ်
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

    // ... (Language Support Methods - No Change)
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

