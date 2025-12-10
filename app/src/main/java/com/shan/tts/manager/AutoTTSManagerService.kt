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
    val sysPitch: Int
)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    // Thread-Safe Queue
    private val messageQueue = java.util.Collections.synchronizedList(LinkedList<TTSChunk>())
    private var currentLocale: Locale = Locale.US
    
    private lateinit var workerThread: HandlerThread
    private lateinit var workHandler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    
    @Volatile
    private var isSpeaking = false
    
    @Volatile
    private var currentUtteranceId: String = ""
    
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
                // Gap 50ms (အသံမထပ်အောင်)
                workHandler.postDelayed({
                    if (utteranceId == currentUtteranceId) {
                        isSpeaking = false
                        processQueue()
                    }
                }, 50) 
            }

            override fun onError(utteranceId: String?) {
                workHandler.post {
                    if (utteranceId == currentUtteranceId) {
                        isSpeaking = false
                        processQueue()
                    }
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
        
        // *** အမှားပြင်ဆင်ချက် ***
        // ဒီနေရာမှာ removeCallbacksAndMessages ကို ဖြုတ်လိုက်ပါပြီ။
        // ဒါမှ Code အရှည်ကြီးတွေ အပိုင်းလိုက်ဝင်လာရင် မဖြတ်ဘဲ ဆက်ဖတ်မှာပါ။
        
        workHandler.post {
            acquireWakeLock()
            
            if (LanguageUtils.hasShan(text)) {
                parseShanMode(text, sysRate, sysPitch)
            } else {
                parseSmartMode(text, sysRate, sysPitch)
            }
            
            processQueue()
        }
        
        callback?.done()
    }

    // *** TalkBack ပွတ်ဆွဲရင် ဒီကောင်အလုပ်လုပ်မယ် ***
    override fun onStop() {
        // ၁။ Handler ထဲက Delay တွေ၊ Pending အလုပ်တွေ အကုန်ချက်ချင်းဖျက်
        workHandler.removeCallbacksAndMessages(null)
        
        // ၂။ Queue ထဲက စာတွေ အကုန်သွန်ပစ် (ချက်ချင်းရှင်း)
        // synchronized block သုံးထားလို့ Thread Safe ဖြစ်တယ်
        synchronized(messageQueue) {
            messageQueue.clear()
        }
        
        // ၃။ အသံထွက်နေတာကို ချက်ချင်းရပ်ဖို့ ထိပ်ဆုံးကနေ ဝင်တိုး
        workHandler.postAtFrontOfQueue {
            forceStopEngines()
            isSpeaking = false
            currentUtteranceId = ""
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

    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int) {
        try {
            val words = text.split(Regex("\\s+"))
            var currentBuffer = StringBuilder()
            var currentLang = ""

            for (word in words) {
                val detectedLang = LanguageUtils.detectLanguage(word)
                if ((currentBuffer.length > MAX_CHAR_LIMIT) || (currentLang.isNotEmpty() && currentLang != detectedLang)) {
                    addToQueue(currentBuffer.toString(), currentLang, sysRate, sysPitch)
                    currentBuffer = StringBuilder()
                    currentLang = detectedLang
                }
                if (currentLang.isEmpty()) currentLang = detectedLang
                currentBuffer.append("$word ")
            }
            if (currentBuffer.isNotEmpty()) addToQueue(currentBuffer.toString(), currentLang, sysRate, sysPitch)
        } catch (e: Exception) { }
    }

    private fun parseSmartMode(text: String, sysRate: Int, sysPitch: Int) {
        if (text.isEmpty()) return

        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (char in text) {
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
        if (currentBuffer.isNotEmpty()) {
            addToQueue(currentBuffer.toString(), currentLang, sysRate, sysPitch)
        }
    }

    private fun addToQueue(text: String, lang: String, sysRate: Int, sysPitch: Int) {
        if (text.isBlank()) return
        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
        synchronized(messageQueue) {
            messageQueue.add(TTSChunk(text, engine, lang, sysRate, sysPitch))
        }
    }

    private fun processQueue() {
        // Strict Lock: ပြောနေတုန်းဆိုရင် မဝင်နဲ့
        if (isSpeaking) return
        
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
        
        if (engine == null) {
            processQueue()
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

        val utteranceId = UUID.randomUUID().toString()
        currentUtteranceId = utteranceId

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
            if (currentUtteranceId == utteranceId && isSpeaking) {
                isSpeaking = false
                processQueue()
            }
        }, timeout) 

        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        
        if (result == TextToSpeech.ERROR) {
             isSpeaking = false
             processQueue()
        }
    }

    private fun forceStopEngines() {
        try {
            shanEngine?.stop()
            burmeseEngine?.stop()
            englishEngine?.stop()
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        forceStopEngines()
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

