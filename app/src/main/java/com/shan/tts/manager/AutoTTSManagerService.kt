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
    private val queueLock = Any()

    private lateinit var workerThread: HandlerThread
    private lateinit var workHandler: Handler
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var currentUtteranceId: String = ""

    @Volatile
    private var isEngineBusy = false

    @Volatile
    private var currentSessionId: Long = 0L

    private var currentLocale: Locale = Locale.US
    private val SAFE_CHAR_LIMIT = 400

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
                // အရေးကြီး: Stop လုပ်လိုက်ပြီးသား ID အဟောင်းဆိုရင် Busy မလုပ်ပါနဲ့
                if (utteranceId == currentUtteranceId) {
                    isEngineBusy = true
                }
            }

            override fun onDone(utteranceId: String?) {
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
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tempTTS?.let { onSuccess(it) }
                }
            }, pkgName)
        } catch (e: Exception) { }
    }

    // *** FIX: TalkBack Stop ပြဿနာဖြေရှင်းချက် ***
    override fun onStop() {
        // 1. Session ID ကို ချက်ချင်းပြောင်းလိုက်ပါ (Parsing အဟောင်းတွေ ရပ်သွားအောင်)
        synchronized(queueLock) {
            currentSessionId = System.currentTimeMillis()
            messageQueue.clear()
        }
        
        // 2. လက်ရှိ Utterance ID ကို ရှင်းထုတ်ပါ
        currentUtteranceId = ""
        isEngineBusy = false

        // 3. Worker Thread မှာ ကျန်နေတဲ့ Parsing Task တွေကို ဖျက်ပါ
        workHandler.removeCallbacksAndMessages(null)
        
        // 4. အသံထွက်နေတာတွေကို ချက်ချင်းရပ်ပါ
        stopEnginesDirectly()
        
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        // TalkBack လိုမျိုး Accessibility အတွက် Audio Format
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        val mySessionId = synchronized(queueLock) { currentSessionId }

        workHandler.post {
            // အကယ်၍ onStop() ဝင်သွားပြီဆိုရင် ဒီအလုပ်ကို မလုပ်ပါနဲ့တော့
            synchronized(queueLock) {
                if (mySessionId != currentSessionId) {
                    callback?.done()
                    return@synchronized
                }
            }

            acquireWakeLock()

            if (LanguageUtils.hasShan(text)) {
                parseShanMode(text, sysRate, sysPitch, mySessionId)
            } else {
                parseSmartMode(text, sysRate, sysPitch, mySessionId)
            }
            
            processQueue()
        }
        
        callback?.done()
    }

    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int, sessionId: Long) {
        val words = text.split(Regex("\\s+"))
        var currentBuffer = StringBuilder()
        var currentLang = ""

        for (word in words) {
            if (sessionId != currentSessionId) return 

            val detectedLang = LanguageUtils.detectLanguage(word)
            
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

    private fun parseSmartMode(text: String, sysRate: Int, sysPitch: Int, sessionId: Long) {
        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (i in text.indices) {
            if (sessionId != currentSessionId) return
            
            val char = text[i]
            val isSeparator = char.isWhitespace() || char == '\n' || ";.!?".contains(char)
            val isOverLimit = currentBuffer.length >= SAFE_CHAR_LIMIT

            if (isOverLimit && isSeparator) {
                currentBuffer.append(char)
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
                currentBuffer = StringBuilder()
                continue
            }
            
            if (currentBuffer.length >= SAFE_CHAR_LIMIT + 100) {
                 addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
                 currentBuffer = StringBuilder()
            }

            val charType = LanguageUtils.getCharType(char)

            if (currentBuffer.isEmpty()) {
                currentLang = charType
                currentBuffer.append(char)
                continue
            }
            
            if (charType == currentLang || char.isWhitespace()) {
                 currentBuffer.append(char)
            } else {
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
            // အရေးကြီးဆုံး Check: Session မတူတော့ရင် Queue ထဲ မထည့်ပါနဲ့
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

    private fun processQueue() {
        var chunkToPlay: TTSChunk? = null

        synchronized(queueLock) {
            if (messageQueue.isEmpty()) {
                releaseWakeLock()
                return
            }
            
            val candidate = messageQueue.peek()
            // Session မတူရင် Queue တစ်ခုလုံး ရှင်းပစ်ပါ (Stop လုပ်ပြီးသားမို့)
            if (candidate != null && candidate.sessionId != currentSessionId) {
                messageQueue.clear()
                releaseWakeLock()
                return
            }

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
            processQueue()
            return
        }

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
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            params.putParcelable("audioAttributes", audioAttributes)
        }
        
        val timeout = (text.length * 300L) + 4000L 
        
        workHandler.postDelayed({
             // Timeout ဖြစ်ရင် Force Reset လုပ်ပါ၊ ဒါပေမယ့် ID တူမှ လုပ်ပါ
             if (isEngineBusy && currentUtteranceId == uId) {
                 isEngineBusy = false
                 processQueue()
             }
        }, timeout)

        engine.speak(text, TextToSpeech.QUEUE_ADD, params, uId)
    }

    private fun stopEnginesDirectly() {
        try {
            shanEngine?.stop()
            burmeseEngine?.stop()
            englishEngine?.stop()
        } catch (e: Exception) { }
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

