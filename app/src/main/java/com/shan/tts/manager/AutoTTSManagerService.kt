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

    private lateinit var workThread: HandlerThread
    private lateinit var workHandler: Handler
    
    // Engine ပြီးမပြီး စောင့်မည့် တံခါး
    private val waiter = ConditionVariable()
    
    // လက်ရှိပြောနေတဲ့ စာကြောင်း ID
    @Volatile
    private var currentBlockingId: String = ""
    
    @Volatile
    private var currentJobId: String = ""

    private var currentLocale: Locale = Locale.US
    private var wakeLock: PowerManager.WakeLock? = null
    private val SAFE_CHUNK_SIZE = 1200

    override fun onCreate() {
        super.onCreate()
        
        workThread = HandlerThread("TTS_WORKER_THREAD")
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
                // လက်ရှိပြောခိုင်းထားတဲ့ ID ပြီးမှသာ တံခါးဖွင့်မယ်
                if (utteranceId == currentBlockingId) {
                    waiter.open()
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId == currentBlockingId) {
                    waiter.open()
                }
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == currentBlockingId) {
                    waiter.open()
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

    override fun onStop() {
        // Job ID ပြောင်းလိုက်တာနဲ့ Loop က ချက်ချင်းရပ်သွားမယ်
        currentJobId = UUID.randomUUID().toString()
        
        stopEnginesDirectly()
        
        // Waiter ကိုဖွင့်ပေးလိုက်မှ Thread က ရှေ့ဆက်သွားပြီး Loop ထဲကထွက်နိုင်မယ်
        waiter.open()
        
        workHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        val myJobId = UUID.randomUUID().toString()
        currentJobId = myJobId
        
        acquireWakeLock()
        callback?.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        val safeChunks = recursiveSplit(text)
        
        for (chunk in safeChunks) {
            if (currentJobId != myJobId) break

            if (LanguageUtils.hasShan(chunk)) {
                if (!processShanMode(chunk, sysRate, sysPitch, myJobId)) break
            } else {
                if (!processSmartMode(chunk, sysRate, sysPitch, myJobId)) break
            }
        }

        callback?.done()
        releaseWakeLock()
    }

    private fun processShanMode(text: String, sysRate: Int, sysPitch: Int, jobId: String): Boolean {
        val words = text.split(Regex("\\s+"))
        var currentBuffer = StringBuilder()
        var currentLang = ""

        for (word in words) {
            if (currentJobId != jobId) return false

            val detectedLang = LanguageUtils.detectLanguage(word)
            
            if (currentLang.isEmpty() || currentLang == detectedLang) {
                currentLang = detectedLang
                currentBuffer.append("$word ")
            } else {
                if (!speakBlocking(currentLang, currentBuffer.toString(), sysRate, sysPitch, jobId)) return false
                currentBuffer = StringBuilder("$word ")
                currentLang = detectedLang
            }
        }
        if (currentBuffer.isNotEmpty()) {
            return speakBlocking(currentLang, currentBuffer.toString(), sysRate, sysPitch, jobId)
        }
        return true
    }

    private fun processSmartMode(text: String, sysRate: Int, sysPitch: Int, jobId: String): Boolean {
        var currentBuffer = StringBuilder()
        var currentLang = "" 

        for (char in text) {
            if (currentJobId != jobId) return false

            if (char == '\n') {
                if (currentBuffer.isNotEmpty()) {
                    if (!speakBlocking(currentLang, currentBuffer.toString(), sysRate, sysPitch, jobId)) return false
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
                if (!speakBlocking(currentLang, currentBuffer.toString(), sysRate, sysPitch, jobId)) return false
                currentLang = charType
                currentBuffer = StringBuilder()
                currentBuffer.append(char)
            }
        }
        if (currentBuffer.isNotEmpty()) {
            return speakBlocking(currentLang, currentBuffer.toString(), sysRate, sysPitch, jobId)
        }
        return true
    }

    // *** STRICT SERIAL BLOCKING ***
    private fun speakBlocking(lang: String, text: String, sysRate: Int, sysPitch: Int, jobId: String): Boolean {
        if (text.isBlank()) return true
        if (currentJobId != jobId) return false

        val engine = when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        } ?: return true

        // တံခါးပိတ်မည်
        waiter.close()
        
        // Blocking ID အသစ်ထုတ်မည်
        val uniqueID = UUID.randomUUID().toString()
        currentBlockingId = uniqueID

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

        // QUEUE_ADD သုံးသော်လည်း waiter.block() ကြောင့် တစ်ခုပြီးမှတစ်ခုသွားမည်
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, uniqueID)
        
        if (result == TextToSpeech.ERROR) {
            waiter.open()
            return true
        } else {
            // Engine မပြီးမချင်း (onDone မလာမချင်း) ဒီမှာရပ်စောင့်နေမည်
            waiter.block()
            
            // Safety Sleep: အသံအမြီးပြတ်ပြီးမှ နောက်တစ်ခုစဖို့ 50ms နားမည်
            // ဒါက "English ဝင်တိုးခြင်း" ကို ကာကွယ်ပေးသည်
            try { Thread.sleep(50) } catch (e: Exception) {}

            return currentJobId == jobId
        }
    }

    private fun stopEnginesDirectly() {
        try {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)
            // Silent Flush - အသံဟောင်းများကို ချက်ချင်းဖျက်ဆီးခြင်း
            val killerId = "KILLER"
            shanEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, killerId)
            burmeseEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, killerId)
            englishEngine?.speak(" ", TextToSpeech.QUEUE_FLUSH, params, killerId)
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
        currentJobId = ""
        waiter.open()
        workThread.quit()
        stopEnginesDirectly()
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

