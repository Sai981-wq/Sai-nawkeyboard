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

// *** DATA CLASS ***
// Session ID ပါ ထည့်မှတ်ထားမယ် (ဒါမှ ပွတ်ဆွဲလိုက်ရင် ဘယ်ကောင်က အဟောင်းလဲ ခွဲလို့ရမှာ)
data class TTSChunk(
    val text: String,
    val engine: TextToSpeech?,
    val lang: String,
    val sysRate: Int,
    val sysPitch: Int,
    val utteranceId: String,
    val sessionId: Long
)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    // Queue နှင့် Lock
    private val messageQueue = LinkedList<TTSChunk>()
    private val queueLock = Any() // Thread တွေ မတိုက်မိအောင် Lock ခတ်မယ်

    private lateinit var workerThread: HandlerThread
    private lateinit var workHandler: Handler
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var currentUtteranceId: String = ""

    @Volatile
    private var isEngineBusy = false // အင်ဂျင် မအားရင် နောက်တစ်ခု မလွှတ်ဘူး

    @Volatile
    private var currentSessionId: Long = 0L // ပွတ်ဆွဲတိုင်း ဒီနံပါတ် ပြောင်းမယ်

    // *** FIX: စာလုံးရေ ၄၀၀ ကျော်ရင် အတင်းဖြတ်မယ် (Android TTS Limit ရှောင်ရန်) ***
    private val SAFE_CHAR_LIMIT = 400

    override fun onCreate() {
        super.onCreate()
        // Audio Priority ပေးထားခြင်းဖြင့် စက်လေးနေရင်တောင် အသံမထစ်အောင် ကူညီပေးပါမယ်
        workerThread = HandlerThread("CherryTTSWorker", Process.THREAD_PRIORITY_AUDIO)
        workerThread.start()
        workHandler = Handler(workerThread.looper)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherryTTS:WakeLock")
        wakeLock?.setReferenceCounted(false)

        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)

        // Initialize Engines
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
                // အသံတစ်ခုပြီးမှ နောက်တစ်ခုကို Process လုပ်မယ် (အသံမထပ်အောင်)
                if (utteranceId == currentUtteranceId) {
                    isEngineBusy = false
                    workHandler.post { processQueue() }
                }
            }

            override fun onError(utteranceId: String?) {
                handleError(utteranceId)
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                handleError(utteranceId)
            }

            fun handleError(id: String?) {
                if (id == currentUtteranceId) {
                    isEngineBusy = false
                    workHandler.post { processQueue() }
                }
            }
        })
    }

    private fun initializeEngine(pkgName: String?, onSuccess: (TextToSpeech) -> Unit) {
        if (pkgName.isNullOrEmpty() || pkgName == packageName) return
        try {
            TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) onSuccess(it)
            }, pkgName)
        } catch (e: Exception) { }
    }

    // *** TALKBACK SWIPE HANDLER (အရေးအကြီးဆုံးနေရာ) ***
    override fun onStop() {
        // ၁။ Lock ခတ်ပြီး Session ID ပြောင်းမယ် (ဒါဆို Queue ထဲက အဟောင်းတွေ အကုန် Invalid ဖြစ်သွားမယ်)
        synchronized(queueLock) {
            currentSessionId = System.currentTimeMillis()
            messageQueue.clear()
        }
        
        // ၂။ Pending Task တွေ ဖျက်မယ်
        workHandler.removeCallbacksAndMessages(null)
        
        // ၃။ အင်ဂျင်တွေကို ချက်ချင်း အသံပိတ်ခိုင်းမယ်
        isEngineBusy = false
        stopEnginesDirectly()
        
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        // TalkBack ကို အလုပ်လုပ်နေပြီလို့ အကြောင်းကြား
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // လက်ရှိ Session ID ကို မှတ်မယ်
        val mySessionId = synchronized(queueLock) { currentSessionId }

        workHandler.post {
            // Thread စrun ချိန်မှာ ID ပြောင်းသွားပြီလား (ပွတ်ဆွဲခံလိုက်ရလား) စစ်မယ်
            synchronized(queueLock) {
                if (mySessionId != currentSessionId) {
                    callback?.done()
                    return@synchronized
                }
            }

            acquireWakeLock()

            // *** Long Text Optimization ***
            if (LanguageUtils.hasShan(text)) {
                parseShanMode(text, sysRate, sysPitch, mySessionId)
            } else {
                parseSmartMode(text, sysRate, sysPitch, mySessionId)
            }
            
            // Parsing ပြီးရင် Process လုပ်မယ်
            processQueue()
        }
        
        callback?.done()
    }

    // ၁။ ရှမ်းစာ Parsing
    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int, sessionId: Long) {
        val words = text.split(Regex("\\s+"))
        var currentBuffer = StringBuilder()
        var currentLang = ""

        for (word in words) {
            // Loop ပတ်နေတုန်း Stop နှိပ်ခံရရင် ချက်ချင်းထွက်မယ်
            if (sessionId != currentSessionId) return 

            val detectedLang = LanguageUtils.detectLanguage(word)
            
            // Limit ကျော်ရင် (သို့) ဘာသာစကားပြောင်းရင် Queue ထဲထည့်မယ်
            if ((currentBuffer.length + word.length > SAFE_CHAR_LIMIT) || (currentLang.isNotEmpty() && currentLang != detectedLang)) {
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
                currentBuffer = StringBuilder()
                currentLang = detectedLang
            }
            if (currentLang.isEmpty()) currentLang = detectedLang
            currentBuffer.append("$word ")
        }
        if (currentBuffer.isNotEmpty() && sessionId == currentSessionId) {
            addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
        }
    }

    // ၂။ ဗမာ/အင်္ဂလိပ် Parsing (Long Text Fix ပါဝင်သည်)
    private fun parseSmartMode(text: String, sysRate: Int, sysPitch: Int, sessionId: Long) {
        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (i in text.indices) {
            if (sessionId != currentSessionId) return
            
            val char = text[i]
            
            // *** CHUNKING LOGIC FOR LONG TEXT ***
            val isSeparator = char.isWhitespace() || char == '\n' || ";.!?".contains(char)
            val isOverLimit = currentBuffer.length >= SAFE_CHAR_LIMIT

            if (isOverLimit && isSeparator) {
                // Limit လည်းကျော်ပြီ၊ ဖြတ်လို့ကောင်းတဲ့နေရာလည်း ရောက်ပြီဆိုရင် ဖြတ်ထည့်မယ်
                currentBuffer.append(char)
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
                currentBuffer = StringBuilder()
                continue
            }
            
            // Limit အရမ်းကျော်နေပြီ (ဥပမာ Link တွေ Code တွေ) ဆိုရင် အတင်းဖြတ်မယ်
            if (currentBuffer.length >= SAFE_CHAR_LIMIT + 100) {
                 addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
                 currentBuffer = StringBuilder()
            }

            // Normal Character Type Detection
            val charType = LanguageUtils.getCharType(char)

            if (currentBuffer.isEmpty()) {
                currentLang = charType
                currentBuffer.append(char)
                continue
            }
            
            if (charType == currentLang || char.isWhitespace()) {
                 currentBuffer.append(char)
            } else {
                // ဘာသာစကားပြောင်းရင် ဖြတ်မယ်
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
                currentLang = charType
                currentBuffer = StringBuilder()
                currentBuffer.append(char)
            }
        }
        
        if (currentBuffer.isNotEmpty() && sessionId == currentSessionId) {
            addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
        }
    }

    private fun addToQueue(lang: String, text: String, sysRate: Int, sysPitch: Int, sessionId: Long) {
        if (text.isBlank()) return

        synchronized(queueLock) {
            // နောက်ဆုံးအဆင့် စစ်ဆေးခြင်း
            if (sessionId != currentSessionId) return

            val engine = when (lang) {
                "SHAN" -> if (isShanReady) shanEngine else englishEngine
                "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
                else -> if (isEnglishReady) englishEngine else null
            }
            val uId = UUID.randomUUID().toString()
            messageQueue.add(TTSChunk(text, engine, lang, sysRate, sysPitch, uId, sessionId))
        }
    }

    // *** QUEUE PROCESSOR (အသံထပ်ခြင်းကို ကာကွယ်မည့်နေရာ) ***
    private fun processQueue() {
        var chunkToPlay: TTSChunk? = null

        synchronized(queueLock) {
            if (messageQueue.isEmpty()) {
                releaseWakeLock()
                return
            }
            
            // Check Session Validity (စာဟောင်းဆို လွှင့်ပစ်မယ်)
            val candidate = messageQueue.peek()
            if (candidate != null && candidate.sessionId != currentSessionId) {
                messageQueue.clear()
                releaseWakeLock()
                return
            }

            // အင်ဂျင် မအားရင် (ရှေ့ကအသံ မပြီးသေးရင်) မလွှတ်ဘူး
            if (isEngineBusy) return
            
            chunkToPlay = messageQueue.poll()
        }

        if (chunkToPlay == null) return
        
        // Double Check
        if (chunkToPlay!!.sessionId != currentSessionId) return

        val engine = chunkToPlay!!.engine
        val text = chunkToPlay!!.text
        val uId = chunkToPlay!!.utteranceId
        
        if (engine == null) {
            processQueue() // Engine မရှိရင် ကျော်မယ်
            return
        }

        // စပြောပြီ (Busy Flag ထောင်မယ်)
        isEngineBusy = true
        currentUtteranceId = uId

        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        var userRate = 1.0f
        var userPitch = 1.0f

        when (chunkToPlay!!.lang) {
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

        engine.setSpeechRate((chunkToPlay!!.sysRate / 100.0f) * userRate)
        engine.setPitch((chunkToPlay!!.sysPitch / 100.0f) * userPitch)

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        
        // *** FIX: Build Error Solution included ***
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            params.putParcelable("audioAttributes", audioAttributes)
        }
        
        // Watchdog Timeout: အင်ဂျင်ကြောင်သွားရင် ပြန်ကယ်ဖို့ (စာရှည်ရင် အချိန်ပိုပေးမယ်)
        val timeout = (text.length * 300L) + 4000L 
        
        workHandler.postDelayed({
             if (isEngineBusy && currentUtteranceId == uId) {
                 isEngineBusy = false
                 processQueue() // Force proceed
             }
        }, timeout)

        engine.speak(text, TextToSpeech.QUEUE_ADD, params, uId)
    }

    private fun stopEnginesDirectly() {
        try {
            currentUtteranceId = "" 
            shanEngine?.stop()
            burmeseEngine?.stop()
            englishEngine?.stop()
        } catch (e: Exception) { }
    }
    
    override fun onDestroy() {
        stopEnginesDirectly()
        releaseWakeLock()
        workerThread.quitSafely()
        // Listeners Null လုပ်ခြင်းက Crash ကာကွယ်ပေးသည်
        shanEngine?.setOnUtteranceProgressListener(null)
        burmeseEngine?.setOnUtteranceProgressListener(null)
        englishEngine?.setOnUtteranceProgressListener(null)
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    
    private fun acquireWakeLock() { wakeLock?.acquire(60 * 1000L) }
    private fun releaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock?.release() }
    
    // ... Voices / Language Methods ...
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

