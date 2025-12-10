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

// Data Class for TTS Queue
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

    // Atomic Flag for fast interruption
    private val isInterrupted = AtomicBoolean(false)

    private val MAX_CHAR_LIMIT = 500

    override fun onCreate() {
        super.onCreate()
        
        // Priority Audio ပေးထားခြင်းဖြင့် စက်လေးနေရင်တောင် အသံမထစ်အောင် ကူညီပေးပါမယ်
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
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                // အသံတစ်ခုပြီးရင် နောက်တစ်ခုကို ချက်ချင်းခေါ်မယ်
                if (utteranceId == currentUtteranceId) {
                    workHandler.post { playNextInQueue() }
                }
            }

            override fun onError(utteranceId: String?) {
                // Error တက်ခဲ့ရင်လည်း ရပ်မနေဘဲ နောက်တစ်ခုဆက်သွားမယ်
                if (utteranceId == currentUtteranceId) {
                    workHandler.post { playNextInQueue() }
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

        // TalkBack အတွက် အရေးကြီး: Audio စမယ်လို့ ကြေညာခြင်း
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // အသစ်ဝင်လာရင် Interrupt Flag ကို Reset ချမယ်
        isInterrupted.set(false)

        workHandler.post {
            // Queue မရောက်ခင် ပွတ်ဆွဲခံလိုက်ရရင် ချက်ချင်းရပ်မယ်
            if (isInterrupted.get()) {
                callback?.done()
                return@post
            }

            acquireWakeLock()

            // *** Performance Tip: စာတိုလေးတွေဆိုရင် Parsing မလုပ်ဘဲ တန်းလွှတ်မယ် ***
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

    // *** TalkBack Stop: ချက်ချင်းရပ်တန့်စေမည့် စနစ် ***
    override fun onStop() {
        isInterrupted.set(true)

        // ၁။ Engine တွေကို ချက်ချင်းရပ်မယ် (Main Thread ကနေ တိုက်ရိုက်)
        stopEnginesDirectly()

        // ၂။ Queue ကို ရှင်းမယ်
        synchronized(messageQueue) {
            messageQueue.clear()
        }

        // ၃။ Handler ထဲက Pending Task တွေကို ဖျက်မယ်
        workHandler.removeCallbacksAndMessages(null)
        
        releaseWakeLock()
    }

    private fun stopEnginesDirectly() {
        try {
            currentUtteranceId = "" // ID ဖျက်လိုက်ရင် Listener ဆက်အလုပ်မလုပ်တော့ဘူး
            if (isShanReady) shanEngine?.stop()
            if (isBurmeseReady) burmeseEngine?.stop()
            if (isEnglishReady) englishEngine?.stop()
        } catch (e: Exception) { }
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

    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int) {
        try {
            val words = text.split(Regex("\\s+"))
            var currentBuffer = StringBuilder()
            var currentLang = ""

            for (word in words) {
                // *** Loop Breaker: ပွတ်ဆွဲရင် ချက်ချင်းထွက် ***
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
            // *** Loop Breaker ***
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
        
        // Utterance ID ကို ဒီနေရာမှာ သတ်မှတ်လိုက်တယ်
        val uId = UUID.randomUUID().toString()

        synchronized(messageQueue) {
            messageQueue.add(TTSChunk(text, engine, lang, sysRate, sysPitch, uId))
        }
    }

    @Synchronized
    private fun playNextInQueue() {
        if (isInterrupted.get()) {
            releaseWakeLock()
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
            // Fallback: Engine မရှိရင် ကျော်လိုက်မယ် (သို့) English နဲ့ဖတ်ချင်ရင် ဒီမှာပြင်နိုင်တယ်
            playNextInQueue()
            return
        }

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

        // *** FIX: Build Error Solution ***
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            // String Literal "audioAttributes" ကို သုံးခြင်းဖြင့် Error ဖြေရှင်းထားသည်
            params.putParcelable("audioAttributes", audioAttributes)
        }

        engine.speak(text, TextToSpeech.QUEUE_ADD, params, uId)
    }

    override fun onDestroy() {
        // Stop Everything
        stopEnginesDirectly()
        releaseWakeLock()
        workerThread.quitSafely()
        
        // Clean up listeners
        shanEngine?.setOnUtteranceProgressListener(null)
        burmeseEngine?.setOnUtteranceProgressListener(null)
        englishEngine?.setOnUtteranceProgressListener(null)

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

