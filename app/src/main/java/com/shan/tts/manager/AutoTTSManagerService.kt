package com.shan.tts.manager

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

// Data Class for Queue Items
data class TTSChunk(
    val text: String,
    val engine: TextToSpeech?,
    val lang: String,
    val sysRate: Int,
    val sysPitch: Int,
    val sessionId: String,
    val isFirstChunk: Boolean
)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    // Main Queue System
    private val messageQueue = LinkedList<TTSChunk>()
    private val queueLock = Any()
    
    // Session Control (To prevent overlap)
    @Volatile
    private var currentSessionId: String = ""
    
    @Volatile
    private var isEngineSpeaking = false

    private var currentLocale: Locale = Locale.US
    private val mainHandler = Handler(Looper.getMainLooper())
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
                // Check if this audio belongs to current session
                if (utteranceId?.startsWith(currentSessionId) == true) {
                    isEngineSpeaking = true
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith(currentSessionId) == true) {
                    isEngineSpeaking = false
                    // Main Thread ပေါ်တင်ပြီး နောက်တစ်ခုဆက်ခေါ် (Chain Reaction)
                    mainHandler.post { processNextQueueItem() }
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId?.startsWith(currentSessionId) == true) {
                    isEngineSpeaking = false
                    mainHandler.post { processNextQueueItem() }
                }
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId?.startsWith(currentSessionId) == true) {
                    isEngineSpeaking = false
                    mainHandler.post { processNextQueueItem() }
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
        // ၁။ Session ပြောင်းလိုက်တာနဲ့ Queue ထဲကအကောင်တွေ အကုန် Dead သွားမယ်
        val newSession = UUID.randomUUID().toString()
        currentSessionId = newSession
        
        // ၂။ Queue ကို ရှင်းမယ်
        synchronized(queueLock) {
            messageQueue.clear()
        }
        isEngineSpeaking = false
        
        // ၃။ *** THE SILENCE KILLER ***
        // Engine အားလုံးကို အသံတိတ် (Silence) နဲ့ Flush လုပ်ပစ်မယ်
        // ဒါမှ TalkBack ပွတ်လိုက်တာနဲ့ အသံဟောင်းတွေ တိခနဲ ပြတ်မယ်
        killAllAudio()

        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val originalText = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        acquireWakeLock()
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        // Session အသစ်စမယ်
        val mySession = UUID.randomUUID().toString()
        currentSessionId = mySession
        
        synchronized(queueLock) {
            messageQueue.clear()
        }
        isEngineSpeaking = false

        // စာအရှည်ကြီးကို ခွဲမယ်
        val safeChunks = recursiveSplit(originalText)
        
        var isFirstChunkGlobal = true

        for (chunk in safeChunks) {
            if (currentSessionId != mySession) break
            
            if (LanguageUtils.hasShan(chunk)) {
                parseShanMode(chunk, sysRate, sysPitch, mySession, isFirstChunkGlobal)
            } else {
                parseSmartMode(chunk, sysRate, sysPitch, mySession, isFirstChunkGlobal)
            }
            isFirstChunkGlobal = false
        }

        // စတင် မောင်းနှင်မယ်
        processNextQueueItem()
        
        callback?.done()
    }

    private fun processNextQueueItem() {
        var chunkToPlay: TTSChunk? = null

        synchronized(queueLock) {
            if (messageQueue.isEmpty()) {
                releaseWakeLock()
                return
            }
            
            // Queue ထိပ်ဆုံးက အကောင်ကို စစ်ဆေး
            val candidate = messageQueue.peek()
            
            // အကယ်၍ Session မတူတော့ရင် (Swipe လုပ်ခံလိုက်ရရင်) လွှင့်ပစ်မယ်
            if (candidate != null && candidate.sessionId != currentSessionId) {
                messageQueue.clear()
                return
            }
            
            // *** Wait Logic ***
            // ပထမဆုံး Chunk မဟုတ်ဘူး၊ ပြီးတော့ Engine ကလည်း ပြောနေတုန်းဆိုရင်
            // ဆက်မလုပ်ဘဲ ပြန်ထွက် (onDone က နောက်တစ်ခေါက် ပြန်ခေါ်ပေးလိမ့်မယ်)
            if (candidate != null && !candidate.isFirstChunk && isEngineSpeaking) {
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
        
        isEngineSpeaking = true

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

        val utteranceId = currentSessionId + "_" + UUID.randomUUID().toString()

        // *** FLUSH vs ADD Strategy ***
        // ပထမဆုံး Chunk ဖြစ်ရင် QUEUE_FLUSH (အဟောင်းတွေကို သေချာပေါက် သတ်မယ်)
        // နောက် Chunk တွေဆိုရင် QUEUE_ADD (အေးဆေး ဆက်ပြောမယ်)
        val queueMode = if (chunkToPlay!!.isFirstChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        
        val result = engine.speak(text, queueMode, params, utteranceId)
        
        if (result == TextToSpeech.ERROR) {
             isEngineSpeaking = false
             processNextQueueItem()
        }
    }

    private fun killAllAudio() {
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)
        val killerId = "KILLER_" + System.currentTimeMillis()
        
        // Engine အားလုံးကို အသံတိတ်စာ တစ်လုံးစီ ပို့ပြီး Flush လုပ်ခိုင်းသည်
        try {
            shanEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, killerId)
            burmeseEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, killerId)
            englishEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, killerId)
        } catch (e: Exception) {}
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

    private fun parseShanMode(text: String, sysRate: Int, sysPitch: Int, sessionId: String, isGlobalFirst: Boolean) {
        try {
            val words = text.split(Regex("\\s+"))
            var currentBuffer = StringBuilder()
            var currentLang = ""
            var hasSentFirst = false

            for (word in words) {
                if (currentSessionId != sessionId) return

                val detectedLang = LanguageUtils.detectLanguage(word)
                
                if (currentLang.isEmpty() || currentLang == detectedLang) {
                    currentLang = detectedLang
                    currentBuffer.append("$word ")
                } else {
                    // ပထမဆုံး အပိုင်းဖြစ်မှသာ isGlobalFirst က true
                    // Loop အတွင်း ပထမဆုံးပို့တဲ့ဟာက true, နောက်ပိုင်းဟာတွေ false
                    val flushMode = isGlobalFirst && !hasSentFirst
                    addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId, flushMode)
                    hasSentFirst = true
                    
                    currentBuffer = StringBuilder("$word ")
                    currentLang = detectedLang
                }
            }
            if (currentBuffer.isNotEmpty()) {
                val flushMode = isGlobalFirst && !hasSentFirst
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId, flushMode)
            }
        } catch (e: Exception) { }
    }

    private fun parseSmartMode(text: String, sysRate: Int, sysPitch: Int, sessionId: String, isGlobalFirst: Boolean) {
        if (text.isEmpty()) return

        var currentBuffer = StringBuilder()
        var currentLang = "" 
        var hasSentFirst = false

        for (char in text) {
            if (currentSessionId != sessionId) return

            if (char == '\n') {
                if (currentBuffer.isNotEmpty()) {
                    val flushMode = isGlobalFirst && !hasSentFirst
                    addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId, flushMode)
                    hasSentFirst = true
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
                val flushMode = isGlobalFirst && !hasSentFirst
                addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId, flushMode)
                hasSentFirst = true
                
                currentLang = charType
                currentBuffer = StringBuilder()
                currentBuffer.append(char)
            }
        }
        if (currentBuffer.isNotEmpty()) {
            val flushMode = isGlobalFirst && !hasSentFirst
            addToQueue(currentLang, currentBuffer.toString(), sysRate, sysPitch, sessionId, flushMode)
        }
    }

    private fun addToQueue(lang: String, text: String, sysRate: Int, sysPitch: Int, sessionId: String, isFirst: Boolean) {
        if (text.isBlank()) return
        if (currentSessionId != sessionId) return
        
        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
        
        synchronized(queueLock) {
            messageQueue.add(TTSChunk(text, engine, lang, sysRate, sysPitch, sessionId, isFirst))
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
        isEngineSpeaking = false
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

