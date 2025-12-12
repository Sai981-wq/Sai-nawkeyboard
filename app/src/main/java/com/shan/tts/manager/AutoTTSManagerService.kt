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

// Data class for queue
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

    // Blocking Queue - Thread Safe ဖြစ်ပြီး အလိုအလျောက် စောင့်ဆိုင်းပေးသည်
    private val speechQueue = LinkedBlockingQueue<SpeechItem>()
    
    // Background Worker Thread
    private var workerThread: Thread? = null
    
    // Control Flags
    private val isRunning = AtomicBoolean(true)
    private val currentSessionId = StringBuilder() // Use StringBuilder for thread-safe access logic
    
    // Synchronization Lock for Waiting
    private val engineLock = Object()
    private var isEngineSpeaking = false

    private var currentLocale: Locale = Locale.US
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Safe chunk size
    private val SAFE_CHUNK_SIZE = 800

    override fun onCreate() {
        super.onCreate()
        
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

        // Start the Worker Thread
        startWorker()
    }

    // *** The Heart of the System: Worker Thread ***
    private fun startWorker() {
        workerThread = Thread {
            while (isRunning.get()) {
                try {
                    // Queue ထဲမှာ စာမရှိရင် ဒီနေရာမှာ Thread က အိပ်နေမယ် (CPU မစားဘူး)
                    // စာရောက်လာမှ နိုးမယ်
                    val item = speechQueue.take()

                    // Check Session (Swipe လုပ်ထားရင် ကျော်လိုက်မယ်)
                    if (item.sessionId != getCurrentSessionId()) {
                        continue
                    }

                    // Speak and Wait
                    speakAndWait(item)

                } catch (e: InterruptedException) {
                    // Thread interrupted, loop will restart or exit
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        workerThread?.start()
    }

    // *** Speak Block ***
    private fun speakAndWait(item: SpeechItem) {
        val engine = item.engine ?: return

        synchronized(engineLock) {
            isEngineSpeaking = true
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

        // Rate & Pitch Set
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

        // *** CRITICAL ***
        // QUEUE_ADD သုံးမယ် (ဒါပေမဲ့ Thread Block လုပ်ထားလို့ တစ်ခုပြီးမှတစ်ခုထွက်မယ်)
        // QUEUE_FLUSH မသုံးရ (အရှည်ဖတ်ရင် ပြတ်ကုန်မယ်)
        val utteranceId = item.sessionId + "_" + System.nanoTime()
        engine.speak(item.text, TextToSpeech.QUEUE_ADD, params, utteranceId)

        // Wait until onDone is called
        synchronized(engineLock) {
            while (isEngineSpeaking) {
                try {
                    // Session ပြောင်းသွားရင် (Swipe) မစောင့်တော့ဘဲ ထွက်မယ်
                    if (item.sessionId != getCurrentSessionId()) {
                        engine.stop()
                        break
                    }
                    engineLock.wait() // Engine ပြီးတဲ့ထိ ဒီမှာရပ်စောင့်မယ်
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        
        // Safety Gap for overlapping engines
        try { Thread.sleep(20) } catch (e: Exception) {}
    }

    private fun setupListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                synchronized(engineLock) {
                    isEngineSpeaking = false
                    engineLock.notifyAll() // Wake up the worker thread
                }
            }

            override fun onError(utteranceId: String?) {
                synchronized(engineLock) {
                    isEngineSpeaking = false
                    engineLock.notifyAll()
                }
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                synchronized(engineLock) {
                    isEngineSpeaking = false
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

    // *** Session Helpers ***
    private fun updateSessionId(newId: String) {
        synchronized(currentSessionId) {
            currentSessionId.setLength(0)
            currentSessionId.append(newId)
        }
    }

    private fun getCurrentSessionId(): String {
        synchronized(currentSessionId) {
            return currentSessionId.toString()
        }
    }

    // *** On Stop (TalkBack Swipe) ***
    override fun onStop() {
        // ૧. Session ပြောင်း (Queue ထဲက အဟောင်းတွေ အလုပ်မလုပ်တော့ဘူး)
        updateSessionId(UUID.randomUUID().toString())
        
        // ၂. Queue ရှင်း
        speechQueue.clear()
        
        // ၃. လက်ရှိ ပြောနေတာကို ချက်ချင်းရပ် (Wait state ကို ဖျက်)
        synchronized(engineLock) {
            isEngineSpeaking = false
            engineLock.notifyAll()
        }
        
        // ၄. Engine အားလုံးကို Stop
        shanEngine?.stop()
        burmeseEngine?.stop()
        englishEngine?.stop()
        
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val originalText = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        acquireWakeLock()
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // Session အသစ်သတ်မှတ်
        val mySession = UUID.randomUUID().toString()
        updateSessionId(mySession)
        
        // Queue အဟောင်းရှင်း
        speechQueue.clear()
        
        // လက်ရှိပြောနေတာရှိရင် ရပ်ခိုင်း
        synchronized(engineLock) {
            isEngineSpeaking = false
            engineLock.notifyAll()
        }

        // စာခွဲပြီး Queue ထဲ ထည့် (မဖတ်သေးဘူး, ထည့်ရုံပဲ)
        val safeChunks = recursiveSplit(originalText)

        for (chunk in safeChunks) {
            if (getCurrentSessionId() != mySession) break // Stop လာရင် ရပ်
            
            if (LanguageUtils.hasShan(chunk)) {
                parseShanMode(chunk, sysRate, sysPitch, mySession)
            } else {
                parseSmartMode(chunk, sysRate, sysPitch, mySession)
            }
        }

        callback?.done()
    }

    // *** Parsing Logic (Push to Queue Only) ***
    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int, sessionId: String) {
        val words = text.split(Regex("\\s+"))
        var currentBuffer = StringBuilder()
        var currentLang = ""

        for (word in words) {
            if (getCurrentSessionId() != sessionId) return

            val detectedLang = LanguageUtils.detectLanguage(word)
            
            if (currentLang.isEmpty() || currentLang == detectedLang) {
                currentLang = detectedLang
                currentBuffer.append("$word ")
            } else {
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
                currentBuffer = StringBuilder("$word ")
                currentLang = detectedLang
            }
        }
        if (currentBuffer.isNotEmpty()) {
            addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
        }
    }

    private fun parseSmartMode(text: String, sysRate: Int, sysPitch: Int, sessionId: String) {
        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (char in text) {
            if (getCurrentSessionId() != sessionId) return

            if (char == '\n') {
                if (currentBuffer.isNotEmpty()) {
                    addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
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
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
                currentLang = charType
                currentBuffer = StringBuilder()
                currentBuffer.append(char)
            }
        }
        if (currentBuffer.isNotEmpty()) {
            addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
        }
    }

    private fun addToQueue(lang: String, text: String, rate: Int, pitch: Int, sessionId: String) {
        if (text.isBlank()) return
        
        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
        
        // Queue ထဲထည့်ရုံပဲ (Worker Thread က နောက်ကနေ လိုက်ဖတ်မယ်)
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

