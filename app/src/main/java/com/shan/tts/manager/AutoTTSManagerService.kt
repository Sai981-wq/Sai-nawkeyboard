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

    // Single Thread Executor for Streaming
    private val executor = Executors.newSingleThreadExecutor()
    
    // Control Flags
    private val isStopped = AtomicBoolean(false)
    
    // Current Pipe to close on stop
    @Volatile
    private var currentReadFd: ParcelFileDescriptor? = null

    private var currentLocale: Locale = Locale.US
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Buffer Size for Streaming (4KB is standard for Low Latency)
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

    // *** THE INSTANT KILLER ***
    override fun onStop() {
        isStopped.set(true)
        
        // 1. Close the Pipe (Reading stops immediately)
        try {
            currentReadFd?.close()
            currentReadFd = null
        } catch (e: Exception) {}
        
        // 2. Stop Engines (Stop Writing)
        shanEngine?.stop()
        burmeseEngine?.stop()
        englishEngine?.stop()
        
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        // Reset Stop Flag
        isStopped.set(false)
        acquireWakeLock()

        // Background Streaming
        executor.submit {
            try {
                // 1. Determine Language & Engine
                if (isStopped.get()) return@submit

                // Simple Chunking is NOT needed for Pipe mode usually, 
                // but we split by Language to switch engines.
                val chunks = splitByLanguage(text)

                // Start Audio Stream (16kHz, 16bit PCM)
                // Note: Most engines output 24kHz or 16kHz. 16000 is safer standard.
                callback?.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)

                for (chunkData in chunks) {
                    if (isStopped.get()) break
                    
                    val engine = getEngineForLang(chunkData.lang) ?: continue
                    
                    // 2. Stream Process
                    processViaPipe(engine, chunkData.text, sysRate, sysPitch, callback)
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
        rate: Int, 
        pitch: Int, 
        callback: SynthesisCallback?
    ) {
        // Create a Pipe (Read Side / Write Side)
        val pipe: Array<ParcelFileDescriptor>
        try {
            pipe = ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            return
        }

        val readSide = pipe[0]
        val writeSide = pipe[1]
        
        currentReadFd = readSide

        // Configure Engine
        val params = Bundle()
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        // (Rate/Pitch calculation logic same as before...)
        engine.setSpeechRate(rate / 100.0f) 
        engine.setPitch(pitch / 100.0f)

        // *** MAGIC: Tell Engine to write to our Pipe instead of Speaker ***
        val utteranceId = UUID.randomUUID().toString()
        engine.synthesizeToFile(text, params, writeSide, utteranceId)

        // Read from Pipe and Feed to Callback
        val inputStream = FileInputStream(readSide.fileDescriptor)
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        
        // Skip WAV Header (44 bytes) to avoid "Click" sound
        // synthesizeToFile produces a WAV container.
        var headerSkipped = false
        val headerSize = 44

        try {
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isStopped.get()) break

                var offset = 0
                var length = bytesRead

                if (!headerSkipped) {
                    if (bytesRead > headerSize) {
                        offset = headerSize
                        length = bytesRead - headerSize
                        headerSkipped = true
                    } else {
                        // Buffer too small, just consume it (rare)
                        continue 
                    }
                }

                // *** Feed Audio to TalkBack ***
                // getMaxSpeechInputLength check is bypassed because we stream bytes
                callback?.audioAvailable(buffer, offset, length)
            }
        } catch (e: IOException) {
            // Pipe closed or error
        } finally {
            try {
                inputStream.close()
                readSide.close()
                writeSide.close() // Important to close write side too
            } catch (e: Exception) {}
        }
    }

    // Helper: Split text by language
    data class LangChunk(val text: String, val lang: String)
    
    private fun splitByLanguage(text: String): List<LangChunk> {
        val list = ArrayList<LangChunk>()
        val words = text.split(Regex("\\s+")) // Split by space (or improve with Regex)
        
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
        currentReadFd?.close()
        executor.shutdownNow()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }
    
    // GetVoices and Language Check (Same as before)
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

