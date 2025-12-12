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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    // Single Thread Executor - အလုပ်တစ်ခုပြီးမှ တစ်ခုလုပ်မည့် စနစ် (Lag မဖြစ်ပါ)
    private val executor = Executors.newSingleThreadExecutor()
    
    // Engine Synchronization
    private val speechLock = ReentrantLock()
    private val speechFinishedCondition = speechLock.newCondition()
    
    // Session Control
    @Volatile private var currentSessionId: String = ""
    @Volatile private var isInterrupted = false

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
    }

    private fun setupListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                signalSpeechFinished()
            }

            override fun onError(utteranceId: String?) {
                signalSpeechFinished()
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                signalSpeechFinished()
            }
        })
    }
    
    private fun signalSpeechFinished() {
        speechLock.lock()
        try {
            speechFinishedCondition.signalAll()
        } finally {
            speechLock.unlock()
        }
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

    // *** TalkBack SWIPE Action ***
    override fun onStop() {
        // ၁. Session ပြောင်း (Loop ထဲက အလုပ်တွေ ရပ်သွားမယ်)
        currentSessionId = UUID.randomUUID().toString()
        isInterrupted = true
        
        // ၂. Engine တွေကို ချက်ချင်းရပ်
        stopAllEngines()
        
        // ၃. စောင့်နေတဲ့ Thread ကို လှုပ်နှိုး (Deadlock ဖြေရှင်းခြင်း)
        signalSpeechFinished()
        
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        acquireWakeLock()
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // Session အသစ်
        val mySession = UUID.randomUUID().toString()
        currentSessionId = mySession
        isInterrupted = false

        // Background Thread ပေါ်တင်ပေးလိုက်မယ် (Main UI မလေးအောင်)
        executor.submit {
            if (currentSessionId != mySession) return@submit

            val safeChunks = recursiveSplit(text)
            var isFirstChunk = true

            for (chunk in safeChunks) {
                // Loop ပတ်တိုင်း Stop လုပ်ခံရလား စစ်မယ်
                if (currentSessionId != mySession || isInterrupted) break

                if (LanguageUtils.hasShan(chunk)) {
                    processSpeech(chunk, "SHAN", sysRate, sysPitch, mySession, isFirstChunk)
                } else {
                    processSpeech(chunk, "SMART", sysRate, sysPitch, mySession, isFirstChunk)
                }
                
                isFirstChunk = false
            }
            
            // အားလုံးပြီးမှ Done (Thread မသေခင် Done မလုပ်ရ)
            if (currentSessionId == mySession) {
                callback?.done()
            }
            releaseWakeLock()
        }
    }

    private fun processSpeech(text: String, mode: String, rate: Int, pitch: Int, sessionId: String, isFirstChunk: Boolean) {
        if (mode == "SHAN") {
            val words = text.split(Regex("\\s+"))
            var currentBuffer = StringBuilder()
            var currentLang = ""
            var firstItemInLoop = true

            for (word in words) {
                if (currentSessionId != sessionId) return

                val detectedLang = LanguageUtils.detectLanguage(word)
                if (currentLang.isEmpty() || currentLang == detectedLang) {
                    currentLang = detectedLang
                    currentBuffer.append("$word ")
                } else {
                    val useFlush = isFirstChunk && firstItemInLoop
                    speakAndWait(currentLang, currentBuffer.toString(), rate, pitch, sessionId, useFlush)
                    firstItemInLoop = false
                    currentBuffer = StringBuilder("$word ")
                    currentLang = detectedLang
                }
            }
            if (currentBuffer.isNotEmpty()) {
                val useFlush = isFirstChunk && firstItemInLoop
                speakAndWait(currentLang, currentBuffer.toString(), rate, pitch, sessionId, useFlush)
            }
        } else {
            // SMART MODE
            var currentBuffer = StringBuilder()
            var currentLang = "" 
            var firstItemInLoop = true

            for (char in text) {
                if (currentSessionId != sessionId) return

                if (char == '\n') {
                    if (currentBuffer.isNotEmpty()) {
                        val useFlush = isFirstChunk && firstItemInLoop
                        speakAndWait(currentLang, currentBuffer.toString(), rate, pitch, sessionId, useFlush)
                        firstItemInLoop = false
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
                    val useFlush = isFirstChunk && firstItemInLoop
                    speakAndWait(currentLang, currentBuffer.toString(), rate, pitch, sessionId, useFlush)
                    firstItemInLoop = false
                    currentLang = charType
                    currentBuffer = StringBuilder()
                    currentBuffer.append(char)
                }
            }
            if (currentBuffer.isNotEmpty()) {
                val useFlush = isFirstChunk && firstItemInLoop
                speakAndWait(currentLang, currentBuffer.toString(), rate, pitch, sessionId, useFlush)
            }
        }
    }

    private fun speakAndWait(lang: String, text: String, rate: Int, pitch: Int, sessionId: String, useFlush: Boolean) {
        if (text.isBlank() || currentSessionId != sessionId) return

        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        } ?: return

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            params.putParcelable("audioAttributes", audioAttributes)
        }

        // Apply User Settings
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
        engine.setSpeechRate((rate / 100.0f) * userRate)
        engine.setPitch((pitch / 100.0f) * userPitch)

        val utteranceId = "${sessionId}_${System.nanoTime()}"
        
        // *** CRITICAL LOGIC ***
        // ပထမဆုံး စာသားဆိုရင် QUEUE_FLUSH (အဟောင်းဖျက်)
        // နောက်စာသားဆိုရင် QUEUE_ADD (ဆက်တိုက်ဖတ်)
        val queueMode = if (useFlush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        
        // Lock ကို အရင်ယူမယ်
        speechLock.lock()
        try {
            val result = engine.speak(text, queueMode, params, utteranceId)
            
            if (result == TextToSpeech.SUCCESS) {
                // Engine ပြီးတဲ့အထိ ဒီနေရာမှာ ရပ်စောင့်မယ်
                // 15 Second Timeout ထည့်ထားတယ် (အသံမထွက်ဘဲ ဟန်းနေတာမျိုး မဖြစ်ရအောင်)
                speechFinishedCondition.await(15, TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            // onStop က နိုးလိုက်ရင် ဒီကိုရောက်မယ်
        } finally {
            speechLock.unlock()
        }
        
        // Engine Switching Safety Gap
        try { Thread.sleep(20) } catch (e: Exception) {}
    }

    private fun stopAllEngines() {
        try {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)
            shanEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, "KILLER")
            burmeseEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, "KILLER")
            englishEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, "KILLER")
        } catch (e: Exception) { }
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
        isInterrupted = true
        executor.shutdownNow()
        stopAllEngines()
        releaseWakeLock()
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

