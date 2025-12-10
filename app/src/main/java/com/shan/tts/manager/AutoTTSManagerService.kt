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

data class TTSChunk(
    val text: String,
    val engine: TextToSpeech?,
    val lang: String,
    val sysRate: Int,
    val sysPitch: Int,
    val utteranceId: String,
    val sessionId: Long // ID ကို Long နဲ့ ပြောင်းသုံးမယ်
)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    private val messageQueue = LinkedList<TTSChunk>()
    private val queueLock = Any() // *** LOCK OBJECT: ဒါမရှိဘဲ Queue ကို မထိရ ***

    private lateinit var workerThread: HandlerThread
    private lateinit var workHandler: Handler
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var currentUtteranceId: String = ""

    @Volatile
    private var isEngineBusy = false

    @Volatile
    private var currentSessionId: Long = 0L // Session ID

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
                // ID တူမှသာ နောက်တစ်ခုကို ခေါ်မယ် (Old callbacks တွေကို လစ်လျူရှုမယ်)
                if (utteranceId == currentUtteranceId) {
                    isEngineBusy = false
                    workHandler.post { processQueue() }
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId == currentUtteranceId) {
                    isEngineBusy = false
                    workHandler.post { processQueue() }
                }
            }
            
            // onStop ခေါ်လိုက်ရင် တချို့ဖုန်းတွေမှာ onError ဝင်တတ်တယ်
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == currentUtteranceId) {
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

    // *** 1. STOP COMMAND (GOD MODE) ***
    // TalkBack က Stop လုပ်တာနဲ့ အရာအားလုံးကို ချက်ချင်း သတ်ပစ်မယ်
    override fun onStop() {
        synchronized(queueLock) {
            // ၁။ Session ID ပြောင်းလိုက်မယ် (ဒါဆို နောက်ကလိုက်လာတဲ့ စာတွေ အကုန် Invalid ဖြစ်မယ်)
            currentSessionId = System.currentTimeMillis()
            
            // ၂။ Queue ကို ရှင်းမယ်
            messageQueue.clear()
        }
        
        // ၃။ Pending Handler တွေကို ဖျက်မယ်
        workHandler.removeCallbacksAndMessages(null)
        
        // ၄။ ပြောလက်စကို ချက်ချင်းရပ်မယ်
        isEngineBusy = false
        stopEnginesDirectly()
        
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // လက်ရှိ Session ID ကို မှတ်မယ်
        val mySessionId = synchronized(queueLock) { currentSessionId }

        workHandler.post {
            // Thread စrun ချိန်မှာ ID ပြောင်းသွားပြီလား စစ်မယ်
            synchronized(queueLock) {
                if (mySessionId != currentSessionId) {
                    callback?.done()
                    return@synchronized
                }
            }

            acquireWakeLock()

            if (text.length < 20) {
                val lang = LanguageUtils.detectLanguage(text)
                addToQueue(text, lang, sysRate, sysPitch, mySessionId)
            } else {
                if (LanguageUtils.hasShan(text)) {
                    parseShanMode(text, sysRate, sysPitch, mySessionId)
                } else {
                    parseSmartMode(text, sysRate, sysPitch, mySessionId)
                }
            }
            
            // Parsing ပြီးရင် Process လုပ်မယ်
            processQueue()
        }
        callback?.done()
    }

    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int, sessionId: Long) {
        val words = text.split(Regex("\\s+"))
        var currentBuffer = StringBuilder()
        var currentLang = ""

        for (word in words) {
            // *** CRITICAL CHECK ***
            // Loop ပတ်နေတုန်း Stop နှိပ်လိုက်ရင် ချက်ချင်းထွက်မယ်
            if (sessionId != currentSessionId) return 

            val detectedLang = LanguageUtils.detectLanguage(word)
            if ((currentBuffer.length > MAX_CHAR_LIMIT) || (currentLang.isNotEmpty() && currentLang != detectedLang)) {
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

    private fun parseSmartMode(text: String, sysRate: Int, sysPitch: Int, sessionId: Long) {
        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (char in text) {
             // *** CRITICAL CHECK ***
            if (sessionId != currentSessionId) return

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
        if (currentBuffer.isNotEmpty() && sessionId == currentSessionId) {
            addToQueue(currentBuffer.toString(), currentLang, sysRate, sysPitch, sessionId)
        }
    }

    private fun addToQueue(text: String, lang: String, sysRate: Int, sysPitch: Int, sessionId: Long) {
        if (text.isBlank()) return

        // Lock ခံပြီးမှ စစ်မယ်/ထည့်မယ် (Stop နဲ့ မငြိအောင်)
        synchronized(queueLock) {
            if (sessionId != currentSessionId) return // Expired session

            val engine = when (lang) {
                "SHAN" -> if (isShanReady) shanEngine else englishEngine
                "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
                else -> if (isEnglishReady) englishEngine else null
            }
            val uId = UUID.randomUUID().toString()
            messageQueue.add(TTSChunk(text, engine, lang, sysRate, sysPitch, uId, sessionId))
        }
    }

    private fun processQueue() {
        var chunkToPlay: TTSChunk? = null

        synchronized(queueLock) {
            // ၁။ Queue အလွတ်ကြီးဆို ပြန်မယ်
            if (messageQueue.isEmpty()) {
                releaseWakeLock()
                return
            }

            // ၂။ ရှေ့ဆုံးကဟာကို ယူကြည့်မယ် (မထုတ်သေးဘူး)
            val candidate = messageQueue.peek()
            
            // ၃။ Session ID မမှန်ရင် (အဟောင်းကြီးဖြစ်နေရင်) လွှင့်ပစ်မယ်
            if (candidate != null && candidate.sessionId != currentSessionId) {
                messageQueue.clear() // အကုန်ရှင်းပစ်လိုက်မယ်
                releaseWakeLock()
                return
            }

            // ၄။ Engine မအားရင် ပြန်မယ် (onDone က ပြန်ခေါ်ပေးလိမ့်မယ်)
            if (isEngineBusy) return

            // ၅။ အားလုံးအိုကေမှ ထုတ်ယူမယ်
            chunkToPlay = messageQueue.poll()
        }

        if (chunkToPlay == null) return

        val engine = chunkToPlay!!.engine
        val text = chunkToPlay!!.text
        val uId = chunkToPlay!!.utteranceId
        
        // Double Check (Play ခါနီး Stop နှိပ်ခံရရင် မပြောတော့ဘူး)
        if (chunkToPlay!!.sessionId != currentSessionId) return

        if (engine == null) {
            processQueue() // Skip
            return
        }

        isEngineBusy = true
        currentUtteranceId = uId

        // Set Params (Rate/Pitch)
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            params.putParcelable("audioAttributes", audioAttributes)
        }

        // Timeout Watchdog
        workHandler.postDelayed({
             if (isEngineBusy && currentUtteranceId == uId) {
                 isEngineBusy = false
                 processQueue()
             }
        }, (text.length * 200L) + 3000L)

        // Speak
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
    
    // ... (Other standard methods: onDestroy, onGetVoices, LanguageUtils - Keep same as before)
    override fun onDestroy() {
        stopEnginesDirectly()
        releaseWakeLock()
        workerThread.quitSafely()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    private fun acquireWakeLock() { wakeLock?.acquire(60 * 1000L) }
    private fun releaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock?.release() }
    
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
            currentLocale = Locale(cleanLang, country ?: "", variant ?: "") // Corrected
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

