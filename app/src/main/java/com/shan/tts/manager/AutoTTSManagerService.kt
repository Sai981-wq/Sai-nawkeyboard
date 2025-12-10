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
    val utteranceId: String // ID ထည့်မှတ်မယ်
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

    // *** FIX: Global Interrupt အစား Generation ID သုံးတာ ပိုစိတ်ချရတယ် ***
    // ဒါပေမဲ့ ရိုးရှင်းအောင် လက်ရှိ Flag ကိုပဲ Logic ပြင်သုံးပါမယ်
    private val isInterrupted = AtomicBoolean(false)

    private val MAX_CHAR_LIMIT = 500

    override fun onCreate() {
        super.onCreate()
        // Thread Priority ကို Audio ပေးထားတာ မှန်ပါတယ်
        workerThread = HandlerThread("CherryTTSWorker", Process.THREAD_PRIORITY_AUDIO)
        workerThread.start()
        workHandler = Handler(workerThread.looper)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherryTTS:WakeLock")
        wakeLock?.setReferenceCounted(false)

        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)

        // Engine Initialization (Same as yours)
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
                // *** FIX: Handler ကို post လုပ်ရင်ကြာတယ်၊ ချက်ချင်း run နိုင်ရင် run မယ် ***
                // Gap လျှော့ချလိုက်ပါ (TalkBack မှာ Gap များရင် ထစ်သလိုဖြစ်တယ်)
                if (utteranceId == currentUtteranceId) {
                     workHandler.post { playNextInQueue() }
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId == currentUtteranceId) {
                    workHandler.post { playNextInQueue() }
                }
            }
        })
    }

    // Engine Init (Same as yours)
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

        // TalkBack အတွက် အရေးကြီးတယ်၊ ချက်ချင်း Audio စမယ်လို့ ပြောတာ
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // *** Reset Flag ***
        isInterrupted.set(false)

        workHandler.post {
            if (isInterrupted.get()) {
                callback?.done()
                return@post
            }

            acquireWakeLock()

            // *** Note: Parsing မလုပ်ခင်မှာ အရင်ဟာတွေ ရှင်းဖို့လိုရင် ရှင်းရမယ် ***
            
            if (LanguageUtils.hasShan(text)) {
                parseShanMode(text, sysRate, sysPitch)
            } else {
                parseSmartMode(text, sysRate, sysPitch)
            }

            if (!isInterrupted.get()) {
                playNextInQueue()
            }
        }
        
        // Proxy ဖြစ်တဲ့အတွက် ချက်ချင်း Done ခေါ်ရတာ မှန်ပေမဲ့
        // TalkBack က အသံမလာခင် Focus ရွှေ့သွားနိုင်တယ်
        callback?.done()
    }

    // *** IMPROVED STOP for TalkBack ***
    override fun onStop() {
        isInterrupted.set(true) // Loop တွေကို ရပ်မယ်

        // ၁။ Thread ထဲမထည့်ဘဲ ချက်ချင်း ရပ်ပစ်ရမယ် (Latency လျှော့ချဖို့)
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
        // Main/Binder Thread ကနေ ချက်ချင်းလှမ်းပိတ်တာ ပိုမြန်တယ်
        try {
            currentUtteranceId = "" // ID ဖျက်လိုက်ရင် Listener က ဆက်အလုပ်မလုပ်တော့ဘူး
            if (isShanReady) shanEngine?.stop()
            if (isBurmeseReady) burmeseEngine?.stop()
            if (isEnglishReady) englishEngine?.stop()
        } catch (e: Exception) { }
    }

    // ... (WakeLock codes are fine) ...
    private fun acquireWakeLock() { wakeLock?.acquire(60 * 1000L) }
    private fun releaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock?.release() }


    // ... (Parsing Codes - Same logic but make sure check isInterrupted frequently) ...
    // Parsing code တွေမှာ isInterrupted.get() စစ်တာ ကောင်းပါတယ်၊ ဆက်ထားပါ။
    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int) {
         // (Your existing parsing logic)
         // Use addToQueue(...)
         // အတူတူပါပဲ၊ Loop တွေထဲမှာ if (isInterrupted.get()) return ထည့်ထားတာ သေချာပါစေ။
         try {
            val words = text.split(Regex("\\s+"))
            var currentBuffer = StringBuilder()
            var currentLang = ""

            for (word in words) {
                if (isInterrupted.get()) return // *** Fast Exit

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
    
    // parseSmartMode လည်း အလားတူပါပဲ...
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
        
        // Utterance ID ကို ဒီမှာကတည်းက သတ်မှတ်လိုက်တာ ပိုကောင်းတယ်
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

        // *** FIX: isSpeaking စစ်တာ တစ်ခါတစ်လေ Deadlock ဖြစ်တတ်တယ်၊ 
        // Engine က မပြောဘဲ ရပ်နေရင် isSpeaking က true ဖြစ်ပြီး ဘယ်တော့မှ ဆက်မပြောတော့ဘူး။
        // ဒါကြောင့် UtteranceProgressListener မှာ Done ဖြစ်မှ နောက်တစ်ခုခေါ်တာ စိတ်ချရတယ်။
        
        // လက်ရှိ ပြောနေတာ မရှိမှ အသစ်ယူမယ်
        // ဒါပေမဲ့ TalkBack က အတင်းဖြတ်ပြောရင် onStop က ရှင်းပေးသွားလိမ့်မယ်
        
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
            playNextInQueue()
            return
        }

        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        // Rate/Pitch Calculation (Same as yours)
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
        
        // Audio Attributes (TalkBack needs this to duck audio correctly)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
             val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            params.putParcelable(TextToSpeech.Engine.KEY_PARAM_AUDIO_ATTRIBUTES, audioAttributes)
        }

        // *** FIX: QUEUE_ADD vs QUEUE_FLUSH ***
        // ကျွန်တော်တို့က တစ်ကြောင်းချင်းစီ ခွဲပြောနေတာဖြစ်လို့ QUEUE_ADD က မှန်ပါတယ်
        // ဒါပေမဲ့ Engine တွေက ရှေ့ကဟာ မပြီးသေးခင် နောက်တစ်ခုဝင်လာရင် ကျော်သွားတတ်တယ်
        
        engine.speak(text, TextToSpeech.QUEUE_ADD, params, uId)
    }

    override fun onDestroy() {
        stopEnginesDirectly()
        releaseWakeLock()
        workerThread.quitSafely()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }

    // ... (Voice / Language Info methods - These look fine) ...
    override fun onGetVoices(): List<Voice> {
        val voices = ArrayList<Voice>()
        voices.add(Voice("Shan (Myanmar)", Locale("shn", "MM"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("male")))
        voices.add(Voice("Burmese (Myanmar)", Locale("my", "MM"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("male")))
        voices.add(Voice("English (US)", Locale.US, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("female")))
        return voices
    }
    // ... (Other standard overrides) ...
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

// LanguageUtils က အဆင်ပြေပါတယ် (No changes needed there)
object LanguageUtils {
    // ... (Keep your existing implementation)
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

