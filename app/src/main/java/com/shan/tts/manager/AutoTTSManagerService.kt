package com.shan.tts.manager

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    // Thread & Blocking Mechanism
    private lateinit var workThread: HandlerThread
    private lateinit var workHandler: Handler
    
    // Engine ပြောပြီးမပြီး စောင့်ဆိုင်းရန် Variable
    private val waiter = ConditionVariable()
    
    @Volatile
    private var isStopped = false

    private var currentLocale: Locale = Locale.US
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Chunk Size (Engine Error မတက်အောင် Safe Size ထားသည်)
    private val SAFE_CHUNK_SIZE = 1200

    override fun onCreate() {
        super.onCreate()
        
        // Background Thread တစ်ခုဆောက်မည် (Main UI မလေးစေရန်)
        workThread = HandlerThread("TTS_WORKER")
        workThread.start()
        workHandler = Handler(workThread.looper)
        
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
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                // ပြောပြီးပြီဆိုရင် Lock ဖွင့်ပေးလိုက် (နောက်တစ်ကြောင်း ဆက်ပြောလို့ရပြီ)
                waiter.open()
            }

            override fun onError(utteranceId: String?) {
                // Error တက်ရင်လည်း Lock ဖွင့်ပေး (မဖွင့်ရင် စက်ဟန်းနေမယ်)
                waiter.open()
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                waiter.open()
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

    override fun onStop() {
        // ၁။ Stop Flag တင်လိုက်မယ်
        isStopped = true
        
        // ၂။ လက်ရှိပြောနေတဲ့ Engine ကို ရပ်မယ်
        stopEnginesDirectly()
        
        // ၃။ Waiter ကို အတင်းဖွင့်မယ် (Thread Loop ရပ်သွားအောင်)
        waiter.open()
        
        // ၄။ Handler ထဲက Pending Task တွေဖျက်မယ်
        workHandler.removeCallbacksAndMessages(null)
        
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        // အသစ်လာတိုင်း Stop Flag ဖြုတ်မယ်
        isStopped = false
        
        // WakeLock ယူမယ်
        acquireWakeLock()
        
        // TalkBack က အသံဖိုင်ကို Streaming လိုချင်တာမို့ Start လုပ်ပေးရမယ်
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // *** BLOCKING MODE ***
        // ဒီ Loop က စာတစ်ကြောင်းပြီးမှ တစ်ကြောင်းလုပ်မှာမို့ အသံထပ်ခြင်း မရှိနိုင်ပါ
        
        val safeChunks = recursiveSplit(text)
        
        for (chunk in safeChunks) {
            if (isStopped) break // Stop လာရင် Loop ထဲက ထွက်မယ်

            // ရှမ်းစာလား၊ ဗမာလား ခွဲမယ်
            if (LanguageUtils.hasShan(chunk)) {
                processShanMode(chunk, sysRate, sysPitch)
            } else {
                processSmartMode(chunk, sysRate, sysPitch)
            }
        }

        callback?.done()
        releaseWakeLock()
    }

    // *** SYNCHRONOUS PROCESSORS ***
    
    private fun processShanMode(text: String, sysRate: Int, sysPitch: Int) {
        val words = text.split(Regex("\\s+"))
        var currentBuffer = StringBuilder()
        var currentLang = ""

        for (word in words) {
            if (isStopped) return

            val detectedLang = LanguageUtils.detectLanguage(word)
            
            if (currentLang.isEmpty() || currentLang == detectedLang) {
                currentLang = detectedLang
                currentBuffer.append("$word ")
            } else {
                // Buffer ထဲက စာကို Speak လုပ်ပြီး ပြီးတဲ့အထိ စောင့်မယ်
                speakBlocking(currentLang, currentBuffer.toString(), sysRate, sysPitch)
                currentBuffer = StringBuilder("$word ")
                currentLang = detectedLang
            }
        }
        if (currentBuffer.isNotEmpty() && !isStopped) {
            speakBlocking(currentLang, currentBuffer.toString(), sysRate, sysPitch)
        }
    }

    private fun processSmartMode(text: String, sysRate: Int, sysPitch: Int) {
        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (char in text) {
            if (isStopped) return

            // New Line တွေ့ရင် ချက်ချင်း ဖတ်ပစ်မယ် (Code Reading အတွက်)
            if (char == '\n') {
                if (currentBuffer.isNotEmpty()) {
                    speakBlocking(currentLang, currentBuffer.toString(), sysRate, sysPitch)
                    currentBuffer = StringBuilder()
                }
                continue
            }

            if (char.isWhitespace()) {
                currentBuffer.append(char)
                continue
            }

            val charType = LanguageUtils.getCharType(char)

            if (currentBuffer.isEmpty()) {
                currentLang = charType
                currentBuffer.append(char)
                continue
            }

            if (charType == currentLang) {
                currentBuffer.append(char)
            } else {
                // Language ပြောင်းသွားရင် ရှိတာ အရင်ဖတ်၊ ပြီးမှဆက်လုပ်
                speakBlocking(currentLang, currentBuffer.toString(), sysRate, sysPitch)
                currentLang = charType
                currentBuffer = StringBuilder()
                currentBuffer.append(char)
            }
        }
        if (currentBuffer.isNotEmpty() && !isStopped) {
            speakBlocking(currentLang, currentBuffer.toString(), sysRate, sysPitch)
        }
    }

    // *** THE MAGIC FUNCTION ***
    // ဒီ Function က Engine ပြောလို့မပြီးမချင်း ဒီနေရာမှာပဲ ရပ်စောင့်နေမယ် (Block)
    private fun speakBlocking(lang: String, text: String, sysRate: Int, sysPitch: Int) {
        if (text.isBlank() || isStopped) return

        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        } ?: return

        // 1. Waiter ကို ပိတ်လိုက်မယ် (Red Light)
        waiter.close()

        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        var userRate = 1.0f
        var userPitch = 1.0f

        when (lang) {
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

        engine.setSpeechRate((sysRate / 100.0f) * userRate)
        engine.setPitch((sysPitch / 100.0f) * userPitch)

        val params = Bundle()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            params.putParcelable("audioAttributes", audioAttributes)
        }
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        
        val utteranceId = UUID.randomUUID().toString()

        // 2. Speak ခိုင်းမယ် (QUEUE_ADD သုံးလည်း ရတယ်၊ ဘာလို့ဆို တစ်ကြောင်းချင်းမို့လို့)
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        
        // 3. Error တက်ရင် Waiter ပြန်ဖွင့်၊ မတက်ရင် onDone လာတဲ့ထိ စောင့် (Block)
        if (result == TextToSpeech.ERROR) {
            waiter.open()
        } else {
            // ဒီနေရာမှာ Thread က ရပ်ပြီး Engine ပြီးတဲ့အထိ စောင့်နေပါလိမ့်မယ်
            // TalkBack က onStop ခေါ်ရင် waiter.open() ဖြစ်ပြီး ဒီကနေ လွတ်ထွက်သွားမယ်
            waiter.block()
        }
    }

    private fun stopEnginesDirectly() {
        try {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)
            // Silent Flush to kill buffers
            shanEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, "KILLER")
            burmeseEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, "KILLER")
            englishEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, "KILLER")
        } catch (e: Exception) { }
    }

    private fun recursiveSplit(text: String): List<String> {
        if (text.length <= SAFE_CHUNK_SIZE) {
            return listOf(text)
        }
        val splitPoint = findBestSplitPoint(text, SAFE_CHUNK_SIZE)
        val firstPart = text.substring(0, splitPoint)
        val secondPart = text.substring(splitPoint)
        return listOf(firstPart) + recursiveSplit(secondPart)
    }

    private fun findBestSplitPoint(text: String, limit: Int): Int {
        if (text.length <= limit) return text.length
        val safeRegion = text.substring(0, limit)
        val lastNewLine = safeRegion.lastIndexOf('\n')
        if (lastNewLine > limit / 2) return lastNewLine + 1
        val lastPunctuation = safeRegion.indexOfLast { it == '.' || it == '။' || it == '!' || it == '?' }
        if (lastPunctuation > limit / 2) return lastPunctuation + 1
        val lastComma = safeRegion.indexOfLast { it == ',' || it == '၊' || it == ';' }
        if (lastComma > limit / 2) return lastComma + 1
        val lastSpace = safeRegion.lastIndexOf(' ')
        if (lastSpace > limit / 2) return lastSpace + 1
        return limit
    }
    
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onDestroy() {
        isStopped = true
        waiter.open()
        workThread.quit()
        stopEnginesDirectly()
        releaseWakeLock()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    
    // Voices & Language Logic (Same as before)
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

