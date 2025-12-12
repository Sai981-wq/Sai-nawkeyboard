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
import java.util.LinkedList
import java.util.Locale
import java.util.UUID

data class TTSChunk(
    val text: String,
    val engine: TextToSpeech?,
    val lang: String,
    val sysRate: Int,
    val sysPitch: Int,
    val sessionId: String 
)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    private val messageQueue = LinkedList<TTSChunk>()
    private val queueLock = Any()
    
    @Volatile
    private var currentSessionId: String = UUID.randomUUID().toString()
    
    @Volatile
    private var isSpeaking = false

    private var currentLocale: Locale = Locale.US
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val SAFE_CHUNK_SIZE = 1200

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
            override fun onStart(utteranceId: String?) {
                // လက်ရှိ Session နဲ့ ID တူမှသာ Speaking လို့ သတ်မှတ်
                if (utteranceId?.startsWith(currentSessionId) == true) {
                    isSpeaking = true
                }
            }

            override fun onDone(utteranceId: String?) {
                // လက်ရှိ Session နဲ့ တူမှသာ နောက်တစ်ခုကို ဆက်ဖတ်
                if (utteranceId?.startsWith(currentSessionId) == true) {
                    isSpeaking = false
                    processNextQueueItem()
                }
            }

            override fun onError(utteranceId: String?) {
                // Error တက်ရင်လည်း နောက်တစ်ခု ဆက်သွား
                if (utteranceId?.startsWith(currentSessionId) == true) {
                    isSpeaking = false
                    processNextQueueItem()
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

    override fun onStop() {
        // ၁။ Session အသစ်ပြောင်း (အဟောင်းတွေ အကုန် Invalid ဖြစ်သွားမယ်)
        currentSessionId = UUID.randomUUID().toString()
        
        // ၂။ Queue ကို ရှင်းမယ်
        synchronized(queueLock) {
            messageQueue.clear()
        }
        isSpeaking = false
        
        // ၃။ Engine တွေကို Silent Flush လုပ်ပြီး အသံဟောင်းတွေကို ချက်ချင်းဖြတ်မယ်
        // ဒါက TalkBack ပွတ်ဆွဲချိန် အသံထပ်ခြင်းကို ကာကွယ်ပေးတဲ့ အဓိကအချက်
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)
        val muteId = "SILENCE_KILLER"
        
        try {
            shanEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, muteId)
            burmeseEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, muteId)
            englishEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, muteId)
        } catch (e: Exception) {}

        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val originalText = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        acquireWakeLock()
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // Request အသစ်လာတိုင်း Session အသစ်ယူ (onStop နဲ့ မတိုက်မိအောင်)
        val mySession = UUID.randomUUID().toString()
        currentSessionId = mySession
        
        synchronized(queueLock) {
            messageQueue.clear()
        }
        isSpeaking = false

        val safeChunks = recursiveSplit(originalText)

        for (chunk in safeChunks) {
            if (currentSessionId != mySession) break
            
            if (LanguageUtils.hasShan(chunk)) {
                parseShanMode(chunk, sysRate, sysPitch, mySession)
            } else {
                parseSmartMode(chunk, sysRate, sysPitch, mySession)
            }
        }

        processNextQueueItem()
        callback?.done()
    }

    private fun processNextQueueItem() {
        if (isSpeaking) return

        var chunkToPlay: TTSChunk? = null

        synchronized(queueLock) {
            if (messageQueue.isEmpty()) {
                releaseWakeLock()
                return
            }
            
            val candidate = messageQueue.peek()
            
            // *** Session Check ***
            // Queue ထဲက စာရဲ့ Session ID က လက်ရှိ ID နဲ့ မတူတော့ရင် (Swipe လုပ်ထားရင်)
            // လုံးဝ မဖတ်ဘဲ လွှင့်ပစ်မယ် (Overlap မဖြစ်တော့ဘူး)
            if (candidate != null && candidate.sessionId != currentSessionId) {
                messageQueue.poll() // Remove junk
                // Recursively clear junk until valid or empty
                processNextQueueItem() 
                return
            }
            
            chunkToPlay = messageQueue.poll()
        }

        if (chunkToPlay == null) return

        val engine = chunkToPlay!!.engine
        val text = chunkToPlay!!.text

        if (engine == null) {
            processNextQueueItem()
            return
        }
        
        isSpeaking = true

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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            params.putParcelable("audioAttributes", audioAttributes)
        }
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)

        // Utterance ID မှာ Session ID ပါ ထည့်ပေးလိုက်တယ် (Listener မှာ စစ်ဖို့)
        val utteranceId = currentSessionId + "_" + UUID.randomUUID().toString()

        // onStop() မှာ Flush လုပ်ပြီးသားမို့ ဒီမှာ QUEUE_ADD သုံးမှ အဆင်ပြေမယ်
        // ဒါမှ စာအရှည်ကြီးတွေ ဆက်တိုက်ထွက်မယ်
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        
        if (result == TextToSpeech.ERROR) {
             isSpeaking = false
             processNextQueueItem()
        }
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

    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int, sessionId: String) {
        try {
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
                    addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
                    currentBuffer = StringBuilder("$word ")
                    currentLang = detectedLang
                }
            }
            if (currentBuffer.isNotEmpty()) {
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId)
            }
        } catch (e: Exception) { }
    }

    private fun parseSmartMode(text: String, sysRate: Int, sysPitch: Int, sessionId: String) {
        if (text.isEmpty()) return

        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (char in text) {
            if (currentSessionId != sessionId) return

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

    private fun addToQueue(lang: String, text: String, sysRate: Int, sysPitch: Int, sessionId: String) {
        if (text.isBlank()) return
        if (currentSessionId != sessionId) return
        
        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
        
        synchronized(queueLock) {
            messageQueue.add(TTSChunk(text, engine, lang, sysRate, sysPitch, sessionId))
        }
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
        try {
            shanEngine?.shutdown()
            burmeseEngine?.shutdown()
            englishEngine?.shutdown()
        } catch (e: Exception) {}
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

