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
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

data class SpeechItem(
    val text: String,
    val engine: TextToSpeech?,
    val lang: String,
    val rate: Int,
    val pitch: Int,
    val sessionId: String
)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    private val speechQueue = LinkedBlockingQueue<SpeechItem>()
    private var workerThread: Thread? = null
    private val isRunning = AtomicBoolean(true)
    
    // Global Session ID
    @Volatile private var currentSessionId: String = ""
    
    // *** THE CRITICAL LOCK ***
    // ဒီသော့က Queue ကို ဖျက်ချိန်နဲ့ ထည့်ချိန် တိုက်မနေအောင် ကာကွယ်ပေးတယ်
    private val queueOperationLock = Object()
    
    // Engine Monitor
    private val engineWaitLock = Object()
    @Volatile private var isEngineBusy = false

    private var currentLocale: Locale = Locale.US
    private var wakeLock: PowerManager.WakeLock? = null
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

        startWorker()
    }

    private fun startWorker() {
        workerThread = Thread {
            while (isRunning.get()) {
                try {
                    // Queue ထဲ စာမရောက်မချင်း ဒီမှာ အိပ်နေမယ် (System Resource မစားပါ)
                    val item = speechQueue.take()

                    // Session မတူရင် (Swipe လုပ်ထားတဲ့ အဟောင်းဆိုရင်) ကျော်မယ်
                    if (item.sessionId != currentSessionId) {
                        continue
                    }

                    speakAndWait(item)

                } catch (e: InterruptedException) {
                    // Thread အနှိုးခံရရင် Loop ပြန်ပတ်မယ်
                    continue
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        workerThread?.start()
    }

    private fun speakAndWait(item: SpeechItem) {
        val engine = item.engine ?: return

        synchronized(engineWaitLock) {
            isEngineBusy = true
        }

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
             val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
            params.putParcelable("audioAttributes", audioAttributes)
        }

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

        val utteranceId = item.sessionId + "_" + System.nanoTime()
        
        // QUEUE_ADD သုံးမှ ရှေ့စာနောက်စာ အဆက်အစပ်မပြတ်မှာပါ
        // Thread Blocking သုံးထားလို့ Overlap မဖြစ်ပါဘူး
        engine.speak(item.text, TextToSpeech.QUEUE_ADD, params, utteranceId)

        // Engine ပြီးတဲ့အထိ စောင့်မယ်
        synchronized(engineWaitLock) {
            while (isEngineBusy) {
                try {
                    // စောင့်နေရင်း Swipe လုပ်လိုက်ရင် (Session ပြောင်းသွားရင်) ချက်ချင်းထွက်
                    if (item.sessionId != currentSessionId) {
                        engine.stop()
                        break
                    }
                    engineWaitLock.wait() // onDone လာတဲ့ထိ အိပ်မယ်
                } catch (e: InterruptedException) {
                    engine.stop()
                    break
                }
            }
        }
    }

    private fun setupListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                // Engine ပြီးပြီ၊ Thread ကို နိုးမယ်
                synchronized(engineWaitLock) {
                    isEngineBusy = false
                    engineWaitLock.notifyAll()
                }
            }

            override fun onError(utteranceId: String?) {
                synchronized(engineWaitLock) {
                    isEngineBusy = false
                    engineWaitLock.notifyAll()
                }
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                synchronized(engineWaitLock) {
                    isEngineBusy = false
                    engineWaitLock.notifyAll()
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

    // *** TALKBACK SWIPE ACTION ***
    override fun onStop() {
        // သော့ခတ်ပြီးမှ ရှင်းမယ် (စာအသစ် ထည့်နေတုန်း သွားမဖျက်မိအောင်)
        synchronized(queueOperationLock) {
            // ၁. Session ပြောင်း
            currentSessionId = UUID.randomUUID().toString()
            
            // ၂. Queue ကို ရှင်း (Lag မဖြစ်အောင်)
            speechQueue.clear()
        }
        
        // ၃. Engine တွေကို ရပ် (အသံဟောင်းတွေ တိခနဲပြတ်အောင်)
        shanEngine?.stop()
        burmeseEngine?.stop()
        englishEngine?.stop()
        
        // ၄. Worker Thread ကို နှိုးပြီး လက်ရှိအလုပ်ကနေ ထွက်ခိုင်း
        synchronized(engineWaitLock) {
            isEngineBusy = false
            engineWaitLock.notifyAll()
        }
        workerThread?.interrupt()
        
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val originalText = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        acquireWakeLock()
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        val mySession = UUID.randomUUID().toString()
        
        // သော့ခတ်ပြီးမှ အပြောင်းအလဲလုပ်မယ်
        synchronized(queueOperationLock) {
            currentSessionId = mySession
            // onStop မှာ clear လုပ်ပြီးသားမို့ ဒီမှာ ထပ်မလုပ်ပါ (အသစ်တွေ ပျက်ကုန်မှာစိုးလို့)
        }
        
        // အသစ်မလာခင် အဟောင်းတွေကို ရပ်ခိုင်း (Double Safety)
        workerThread?.interrupt()

        val safeChunks = recursiveSplit(originalText)

        for (chunk in safeChunks) {
            // Parsing လုပ်နေတုန်း Swipe လာရင် ရပ်
            if (currentSessionId != mySession) break
            
            if (LanguageUtils.hasShan(chunk)) {
                parseShanMode(chunk, sysRate, sysPitch, mySession)
            } else {
                parseSmartMode(chunk, sysRate, sysPitch, mySession)
            }
        }

        callback?.done()
    }

    private fun parseShanMode(text: String, rate: Int, pitch: Int, sessionId: String) {
        val words = text.split(Regex("\\s+"))
        var currentBuffer = StringBuilder()
        var currentLang = ""

        for (word in words) {
            if (currentSessionId != sessionId) return

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

    private fun parseSmartMode(text: String, rate: Int, pitch: Int, sessionId: String) {
        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (char in text) {
            if (currentSessionId != sessionId) return

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

    private fun addToQueue(lang: String, text: String, rate: Int, pitch: Int, sessionId: String) {
        if (text.isBlank()) return
        
        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
        
        // သော့ခတ်ပြီးမှ ထည့်မယ် (ဖျက်နေတုန်း လာမထည့်မိအောင်)
        synchronized(queueOperationLock) {
            if (currentSessionId == sessionId) {
                try {
                    speechQueue.put(SpeechItem(text, engine, lang, rate, pitch, sessionId))
                } catch (e: InterruptedException) { }
            }
        }
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
        isRunning.set(false)
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

