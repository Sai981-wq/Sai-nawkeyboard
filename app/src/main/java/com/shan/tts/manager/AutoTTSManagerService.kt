package com.shan.tts.manager

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    private val executor = Executors.newSingleThreadExecutor()
    private val isStopped = AtomicBoolean(false)
    
    @Volatile
    private var currentInputStream: FileInputStream? = null

    private var currentLocale: Locale = Locale.US
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 4KB Buffer
    private val BUFFER_SIZE = 4096

    override fun onCreate() {
        super.onCreate()
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherryTTS:WakeLock")
        wakeLock?.setReferenceCounted(false)

        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        initializeEngine(prefs.getString("pref_shan_pkg", "com.espeak.ng")) { tts -> 
            shanEngine = tts; isShanReady = true
        }
        initializeEngine(prefs.getString("pref_burmese_pkg", "com.google.android.tts")) { tts -> 
            burmeseEngine = tts; isBurmeseReady = true; setupConfig(tts, Locale("my", "MM"))
        }
        initializeEngine(prefs.getString("pref_english_pkg", "com.google.android.tts")) { tts -> 
            englishEngine = tts; isEnglishReady = true; setupConfig(tts, Locale.US)
        }
    }

    private fun setupConfig(tts: TextToSpeech, locale: Locale) {
        tts.language = locale
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
        isStopped.set(true)
        try {
            currentInputStream?.close()
        } catch (e: Exception) {}
        
        shanEngine?.stop()
        burmeseEngine?.stop()
        englishEngine?.stop()
        
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
        // ၁။ System ကလာတဲ့ Rate/Pitch ကို ရယူခြင်း
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        isStopped.set(false)
        acquireWakeLock()

        executor.submit {
            try {
                if (isStopped.get()) return@submit

                val chunks = splitByLanguage(text)

                // Start Audio Stream (16kHz PCM)
                callback?.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)

                for (chunkData in chunks) {
                    if (isStopped.get()) break
                    
                    val engine = getEngineForLang(chunkData.lang) ?: continue
                    
                    // ၂။ User Setting နဲ့ System Setting ကို ပေါင်းစပ်ပြီး ပို့ခြင်း
                    processViaPipe(engine, chunkData.text, chunkData.lang, sysRate, sysPitch, callback)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (!isStopped.get()) {
                    callback?.done()
                }
                releaseWakeLock()
            }
        }
    }

    private fun processViaPipe(
        engine: TextToSpeech, 
        text: String, 
        lang: String,
        sysRate: Int, 
        sysPitch: Int, 
        callback: SynthesisCallback?
    ) {
        var readSide: ParcelFileDescriptor? = null
        var writeSide: ParcelFileDescriptor? = null
        
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            readSide = pipe[0]
            writeSide = pipe[1]
            
            val params = Bundle()
            
            // *** RATE & PITCH CALCULATION (The Fix) ***
            val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            
            // User Preference (Default 100)
            var userRatePref = 100
            var userPitchPref = 100

            when (lang) {
                "SHAN" -> {
                    userRatePref = prefs.getInt("rate_shan", 100)
                    userPitchPref = prefs.getInt("pitch_shan", 100)
                }
                "MYANMAR" -> {
                    userRatePref = prefs.getInt("rate_burmese", 100)
                    userPitchPref = prefs.getInt("pitch_burmese", 100)
                }
                else -> {
                    userRatePref = prefs.getInt("rate_english", 100)
                    userPitchPref = prefs.getInt("pitch_english", 100)
                }
            }

            // Formula: (System / 100) * (User / 100)
            // ဥပမာ: System 100, User 150 (1.5x) => Result 1.5x
            // ဥပမာ: System 200 (TalkBack fast), User 100 => Result 2.0x
            val finalRate = (sysRate / 100.0f) * (userRatePref / 100.0f)
            val finalPitch = (sysPitch / 100.0f) * (userPitchPref / 100.0f)

            engine.setSpeechRate(finalRate)
            engine.setPitch(finalPitch)

            val utteranceId = UUID.randomUUID().toString()
            engine.synthesizeToFile(text, params, writeSide, utteranceId)

            // Close write side immediately so read side gets EOF when engine is done
            writeSide.close()
            writeSide = null 

            val inputStream = FileInputStream(readSide.fileDescriptor)
            currentInputStream = inputStream 
            
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            
            // *** WAV HEADER SKIPPING FIX ***
            // Chunk တစ်ခုချင်းစီအတွက် သီးသန့် Reset လုပ်ရမည်
            var totalBytesForThisChunk = 0
            val headerSize = 44

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isStopped.get()) break

                var offset = 0
                var length = bytesRead

                // Skip the first 44 bytes (WAV Header) ONLY for the start of this chunk
                if (totalBytesForThisChunk < headerSize) {
                    val remainingHeader = headerSize - totalBytesForThisChunk
                    if (bytesRead > remainingHeader) {
                        offset = remainingHeader
                        length = bytesRead - remainingHeader
                        totalBytesForThisChunk += headerSize // Header done
                    } else {
                        totalBytesForThisChunk += bytesRead
                        continue // Skip this small buffer entirely
                    }
                }

                if (length > 0) {
                    callback?.audioAvailable(buffer, offset, length)
                }
            }

        } catch (e: IOException) {
            // Normal during stop
        } finally {
            try {
                currentInputStream?.close()
                currentInputStream = null
                readSide?.close()
                writeSide?.close()
            } catch (e: Exception) {}
        }
    }

    data class LangChunk(val text: String, val lang: String)
    
    private fun splitByLanguage(text: String): List<LangChunk> {
        val list = ArrayList<LangChunk>()
        val words = text.split(Regex("\\s+")) 
        
        var currentBuffer = StringBuilder()
        var currentLang = ""

        for (word in words) {
            val detected = LanguageUtils.detectLanguage(word)
            if (currentLang.isEmpty() || currentLang == detected) {
                currentLang = detected
                currentBuffer.append("$word ")
            } else {
                list.add(LangChunk(currentBuffer.toString(), currentLang))
                currentBuffer = StringBuilder("$word ")
                currentLang = detected
            }
        }
        if (currentBuffer.isNotEmpty()) {
            list.add(LangChunk(currentBuffer.toString(), currentLang))
        }
        return list
    }

    private fun getEngineForLang(lang: String): TextToSpeech? {
        return when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
    }
    
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onDestroy() {
        isStopped.set(true)
        executor.shutdownNow()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }
    
    override fun onGetVoices(): List<Voice> {
        return listOf(
            Voice("Shan (Myanmar)", Locale("shn", "MM"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("male")),
            Voice("Burmese (Myanmar)", Locale("my", "MM"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("male")),
            Voice("English (US)", Locale.US, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, setOf("female"))
        )
    }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE
    }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE
    }
    override fun onGetLanguage(): Array<String> {
        return arrayOf("eng", "USA", "")
    }
}

object LanguageUtils {
    private val SHAN_PATTERN = Regex("[ႉႄႇႈၽၶၺႃၸၼဢၵႁဵႅၢႆႂႊ]")
    private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
    fun hasShan(text: String): Boolean = SHAN_PATTERN.containsMatchIn(text)
    fun detectLanguage(text: CharSequence?): String {
        if (text.isNullOrBlank()) return "ENGLISH"
        val input = text.toString()
        if (SHAN_PATTERN.containsMatchIn(input)) return "SHAN"
        if (MYANMAR_PATTERN.containsMatchIn(input)) return "MYANMAR"
        return "ENGLISH"
    }
}

