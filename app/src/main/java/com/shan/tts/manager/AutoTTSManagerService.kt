package com.shan.tts.manager

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

// Data Model
data class SpeechItem(
    val text: String,
    val engine: TextToSpeech?,
    val lang: String,
    val rate: Int,
    val pitch: Int,
    val sessionId: Long // ID ပါမှ အဟောင်း/အသစ် ခွဲလို့ရမယ်
)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    // Queue (Thread Safe)
    private val speechQueue = LinkedBlockingQueue<SpeechItem>()
    
    // Session Manager (Atomic for thread safety)
    private val currentSessionId = AtomicLong(0)
    
    // Worker Thread
    private var workerThread: Thread? = null
    @Volatile private var isWorkerRunning = true
    
    // Blocking Logic
    private val engineLock = Object()
    @Volatile private var isEngineBusy = false

    private var currentLocale: Locale = Locale.US
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Chunk Size (Engine Error မတက်အောင် လျှော့ထားသည်)
    private val SAFE_CHUNK_SIZE = 800

    override fun onCreate() {
        super.onCreate()
        
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

        // နောက်ကွယ်ကနေ အလုပ်လုပ်မယ့် Thread စမယ်
        startWorkerThread()
    }

    private fun startWorkerThread() {
        workerThread = Thread {
            while (isWorkerRunning) {
                try {
                    // Queue ထဲ စာရောက်လာတဲ့အထိ စောင့်မယ် (CPU မစားဘူး)
                    val item = speechQueue.take()

                    // *** အဓိကသော့ချက် ***
                    // Queue ထဲကစာရဲ့ ID နဲ့ လက်ရှိ ID မတူရင် (Swipe လုပ်ထားရင်) လွှင့်ပစ်မယ်
                    if (item.sessionId != currentSessionId.get()) {
                        continue
                    }

                    // စာဖတ်မယ် (Block လုပ်ပြီး စောင့်မယ်)
                    processSpeechItem(item)

                } catch (e: InterruptedException) {
                    // Thread ကို ရပ်ခိုင်းရင် ထွက်မယ်
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }.apply { start() }
    }

    private fun processSpeechItem(item: SpeechItem) {
        val engine = item.engine ?: return

        // Speaking Flag တင်မယ်
        synchronized(engineLock) { isEngineBusy = true }

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            params.putParcelable("audioAttributes", audioAttributes)
        }

        // User Settings
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        var userRate = 1.0f
        var userPitch = 1.0f
        
        when (item.lang) {
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
        engine.setSpeechRate((item.rate / 100.0f) * userRate)
        engine.setPitch((item.pitch / 100.0f) * userPitch)

        // *** Speak ***
        // QUEUE_ADD သုံးပေမယ့် thread က wait နေမှာမို့ တစ်ခုပြီးမှတစ်ခုထွက်မယ်
        val utteranceId = "${item.sessionId}_${System.nanoTime()}"
        engine.speak(item.text, TextToSpeech.QUEUE_ADD, params, utteranceId)

        // *** Blocking Wait ***
        // Engine ပြီးတဲ့အထိ (သို့) Swipe လုပ်တဲ့အထိ စောင့်မယ်
        synchronized(engineLock) {
            while (isEngineBusy) {
                // စောင့်နေရင်း Session ပြောင်းသွားရင် (Swipe လုပ်ရင်) ချက်ချင်း ရပ်ပြီး ထွက်မယ်
                if (item.sessionId != currentSessionId.get()) {
                    engine.stop()
                    isEngineBusy = false
                    break
                }
                try {
                    engineLock.wait() // onDone လာတဲ့ထိ စောင့်
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        
        // Safety Gap (အသံထပ်ခြင်း ကာကွယ်ရန် အနည်းငယ်နားသည်)
        try { Thread.sleep(15) } catch (e: Exception) {}
    }

    private fun setupListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                // Engine ပြီးပြီဆိုရင် Worker Thread ကို နိုးလိုက်မယ်
                synchronized(engineLock) {
                    isEngineBusy = false
                    engineLock.notifyAll()
                }
            }

            override fun onError(utteranceId: String?) {
                synchronized(engineLock) {
                    isEngineBusy = false
                    engineLock.notifyAll()
                }
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                synchronized(engineLock) {
                    isEngineBusy = false
                    engineLock.notifyAll()
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

    // *** TalkBack SWIPE Event ***
    override fun onStop() {
        // ၁. Session ID တိုးလိုက်မယ် (ဒါဆို Queue ထဲက အဟောင်းတွေ အကုန် Invalid ဖြစ်သွားမယ်)
        currentSessionId.incrementAndGet()
        
        // ၂. Engine တွေကို ချက်ချင်းရပ်ခိုင်းမယ် (Buffer ထဲက အသံတွေ ပျောက်သွားမယ်)
        shanEngine?.stop()
        burmeseEngine?.stop()
        englishEngine?.stop()
        
        // ၃. စောင့်နေတဲ့ Thread ကို လှုပ်နှိုးပြီး လွှတ်ပေးမယ်
        synchronized(engineLock) {
            isEngineBusy = false
            engineLock.notifyAll()
        }
        
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val originalText = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        acquireWakeLock()
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // Session အသစ် တစ်ခု စမယ်
        // onStop နဲ့ ပြိုင်မတိုးအောင် အသစ်ထပ်ယူလိုက်တာ
        val mySession = currentSessionId.incrementAndGet()
        
        // **မှတ်ချက်** : Queue ကို clear() မလုပ်ပါဘူး။ Session ID နဲ့ပဲ စစ်ထုတ်ပါမယ်။
        // ဒါမှ စာအသစ်တွေ မှားအဖျက်ခံရတာမျိုး မဖြစ်တော့မှာပါ။

        val safeChunks = recursiveSplit(originalText)

        for (chunk in safeChunks) {
            // Loop ပတ်နေတုန်း Stop လာရင် ရပ်
            if (currentSessionId.get() != mySession) break
            
            if (LanguageUtils.hasShan(chunk)) {
                parseShanMode(chunk, sysRate, sysPitch, mySession)
            } else {
                parseSmartMode(chunk, sysRate, sysPitch, mySession)
            }
        }

        callback?.done()
    }

    private fun parseShanMode(text: String, rate: Int, pitch: Int, sessionId: Long) {
        val words = text.split(Regex("\\s+"))
        var currentBuffer = StringBuilder()
        var currentLang = ""

        for (word in words) {
            if (currentSessionId.get() != sessionId) return

            val detectedLang = LanguageUtils.detectLanguage(word)
            
            if (currentLang.isEmpty() || currentLang == detectedLang) {
                currentLang = detectedLang
                currentBuffer.append("$word ")
            } else {
                addToQueue(currentLang, currentBuffer.toString(), rate, pitch, sessionId)
                currentBuffer = StringBuilder("$word ")
                currentLang = detectedLang
            }
        }
        if (currentBuffer.isNotEmpty()) {
            addToQueue(currentLang, currentBuffer.toString(), rate, pitch, sessionId)
        }
    }

    private fun parseSmartMode(text: String, rate: Int, pitch: Int, sessionId: Long) {
        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (char in text) {
            if (currentSessionId.get() != sessionId) return

            if (char == '\n') {
                if (currentBuffer.isNotEmpty()) {
                    addToQueue(currentLang, currentBuffer.toString(), rate, pitch, sessionId)
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
                addToQueue(currentLang, currentBuffer.toString(), rate, pitch, sessionId)
                currentLang = charType
                currentBuffer = StringBuilder()
                currentBuffer.append(char)
            }
        }
        if (currentBuffer.isNotEmpty()) {
            addToQueue(currentLang, currentBuffer.toString(), rate, pitch, sessionId)
        }
    }

    private fun addToQueue(lang: String, text: String, rate: Int, pitch: Int, sessionId: Long) {
        if (text.isBlank()) return
        
        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
        
        // Queue ထဲကို ထည့်ရုံပဲထည့်တယ် (Worker Thread က ID စစ်ပြီးမှ ဖတ်မယ်)
        try {
            speechQueue.put(SpeechItem(text, engine, lang, rate, pitch, sessionId))
        } catch (e: InterruptedException) { }
    }

    private fun recursiveSplit(text: String): List<String> {
        if (text.length <= SAFE_CHUNK_SIZE) return listOf(text)
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
        if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onDestroy() {
        isWorkerRunning = false
        workerThread?.interrupt()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        releaseWakeLock()
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

