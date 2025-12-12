package com.shan.tts.manager

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var isShanReady = false
    private var isBurmeseReady = false
    private var isEnglishReady = false

    private val executor = Executors.newSingleThreadExecutor()
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)

    private var wakeLock: PowerManager.WakeLock? = null
    
    // Output Format
    private val TARGET_SAMPLE_RATE = 24000
    private val TARGET_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private val STREAM_BUFFER_SIZE = 4096

    override fun onCreate() {
        super.onCreate()
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherryTTS:WakeLock")
        wakeLock?.setReferenceCounted(false)

        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        // Setup Engines
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
            
            // Note: We do NOT close the pipe here anymore. 
            // The pipe will close naturally when the Engine releases its file descriptor.
            override fun onDone(utteranceId: String?) {}

            override fun onError(utteranceId: String?) {}
            
            override fun onError(utteranceId: String?, errorCode: Int) {}
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
        isStopped.set(true)
        currentTask?.cancel(true)
        // No need to manually close activePipes here as we close write-side immediately now
        
        shanEngine?.stop()
        burmeseEngine?.stop()
        englishEngine?.stop()
        
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        // Stop previous task
        isStopped.set(true)
        currentTask?.cancel(true)
        
        // Reset for new task
        isStopped.set(false)
        acquireWakeLock()

        currentTask = executor.submit {
            try {
                if (isStopped.get()) return@submit

                val chunks = splitByLanguage(text)
                var hasStartedCallback = false

                for (chunkData in chunks) {
                    if (isStopped.get()) break
                    
                    val engine = getEngineForLang(chunkData.lang) ?: continue
                    
                    // Process Stream
                    val started = processStream(engine, chunkData.text, chunkData.lang, sysRate, sysPitch, callback, hasStartedCallback)
                    if (started) {
                        hasStartedCallback = true
                    }
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

    private fun processStream(
        engine: TextToSpeech, 
        text: String, 
        lang: String,
        sysRate: Int, 
        sysPitch: Int, 
        callback: SynthesisCallback?,
        alreadyStarted: Boolean
    ): Boolean {
        var readSide: ParcelFileDescriptor? = null
        var writeSide: ParcelFileDescriptor? = null
        val utteranceId = UUID.randomUUID().toString()
        var callbackStartedNow = false
        
        try {
            // Create Pipe
            val pipe = ParcelFileDescriptor.createPipe()
            readSide = pipe[0]
            writeSide = pipe[1]
            
            // Calculate Rate/Pitch
            val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
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

            val finalRate = (sysRate * userRatePref) / 10000.0f
            val finalPitch = (sysPitch * userPitchPref) / 10000.0f

            engine.setSpeechRate(finalRate)
            engine.setPitch(finalPitch)

            // Params
            val params = Bundle()
            
            // START SYNTHESIS
            // Note: synthesisToFile is async. It will write to writeSide in background.
            val result = engine.synthesizeToFile(text, params, writeSide, utteranceId)
            
            // CRITICAL FIX: Close our copy of writeSide immediately!
            // The Engine has its own copy (via Binder). Closing ours allows the Pipe
            // to send EOF to readSide automatically when the Engine finishes.
            writeSide.close() 
            writeSide = null // Nullify to avoid double close in finally

            if (result != TextToSpeech.SUCCESS) {
                return false
            }

            val inputStream = FileInputStream(readSide.fileDescriptor)
            
            // --- HEADER HANDLING ---
            // Just read/skip 44 bytes. Don't be too strict.
            val headerBuffer = ByteArray(44)
            var headerBytesRead = 0
            
            // Try to read header (with timeout logic implicit in blocking read, 
            // but we use a small buffer check to avoid hanging forever if engine dies)
            while (headerBytesRead < 44) {
                 if (isStopped.get()) break
                 val count = inputStream.read(headerBuffer, headerBytesRead, 44 - headerBytesRead)
                 if (count == -1) break // EOF reached prematurely
                 headerBytesRead += count
            }

            // If we didn't get a full header, the engine might have failed or text was empty.
            // But we proceed if we got *something* or just to be safe.
            
            // Start Callback if needed
            if (!alreadyStarted) {
                callback?.start(TARGET_SAMPLE_RATE, TARGET_ENCODING, 1)
                callbackStartedNow = true
            }

            // Detect Sample Rate (Fallback to 24000 if header is garbage)
            val sourceSampleRate = if (headerBytesRead >= 44) getSampleRateFromWav(headerBuffer) else TARGET_SAMPLE_RATE

            // --- STREAMING LOOP ---
            val pcmBuffer = ByteArray(STREAM_BUFFER_SIZE)
            var bytesRead: Int

            while (true) {
                if (isStopped.get()) break
                
                // This blocks until data is available OR pipe is closed (EOF)
                bytesRead = inputStream.read(pcmBuffer)
                
                if (bytesRead == -1) break // Normal End of Stream

                if (bytesRead > 0) {
                    val finalBytes = if (sourceSampleRate != TARGET_SAMPLE_RATE) {
                        AudioResampler.resampleChunk(pcmBuffer, bytesRead, sourceSampleRate, TARGET_SAMPLE_RATE)
                    } else {
                        // Optimization: Avoid copy if full buffer
                         if (bytesRead == pcmBuffer.size) pcmBuffer else pcmBuffer.copyOfRange(0, bytesRead)
                    }

                    if (finalBytes.isNotEmpty()) {
                        // Use maximum permitted buffer size for TalkBack responsiveness
                        callback?.audioAvailable(finalBytes, 0, finalBytes.size)
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Cleanup
            try { readSide?.close() } catch (e: Exception) {}
            try { writeSide?.close() } catch (e: Exception) {} // In case it wasn't closed above
        }
        
        return callbackStartedNow
    }

    private fun getSampleRateFromWav(header: ByteArray): Int {
        // Standard WAV Sample Rate is at byte 24 (4 bytes little endian)
        if (header.size < 28) return 24000 // Fallback
        return (header[24].toInt() and 0xFF) or
               ((header[25].toInt() and 0xFF) shl 8) or
               ((header[26].toInt() and 0xFF) shl 16) or
               ((header[27].toInt() and 0xFF) shl 24)
    }

    // Helper classes (LangChunk, LanguageUtils, AudioResampler) remain same
    // Just copying minimal needed logic for completeness context
    data class LangChunk(val text: String, val lang: String)
    private fun splitByLanguage(text: String): List<LangChunk> {
        // ... (Your existing logic) ...
        return LanguageUtils.splitHelper(text)
    }
    
    private fun getEngineForLang(lang: String): TextToSpeech? {
        return when (lang) {
            "SHAN" -> if (isShanReady) shanEngine else englishEngine
            "MYANMAR" -> if (isBurmeseReady) burmeseEngine else englishEngine
            else -> if (isEnglishReady) englishEngine else null
        }
    }
    
    private fun acquireWakeLock() { if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L) }
    private fun releaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock?.release() }

    override fun onDestroy() {
        isStopped.set(true)
        executor.shutdownNow()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }
    
    // TalkBack Requirements
    override fun onGetVoices(): List<Voice> { return listOf() }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onGetLanguage(): Array<String> { return arrayOf("eng", "USA", "") }
}

object LanguageUtils {
     // ... Your existing detection logic ...
     private val SHAN_PATTERN = Regex("[ႉႄႇႈၽၶၺႃၸၼဢၵႁဵႅၢႆႂႊ]")
     private val MYANMAR_PATTERN = Regex("[\\u1000-\\u109F]")
     fun detectLanguage(text: CharSequence?): String {
        if (text.isNullOrBlank()) return "ENGLISH"
        val input = text.toString()
        if (SHAN_PATTERN.containsMatchIn(input)) return "SHAN"
        if (MYANMAR_PATTERN.containsMatchIn(input)) return "MYANMAR"
        return "ENGLISH"
    }
    fun splitHelper(text: String): List<AutoTTSManagerService.LangChunk> {
        val list = ArrayList<AutoTTSManagerService.LangChunk>()
        val words = text.split(Regex("\\s+")) 
        var currentBuffer = StringBuilder()
        var currentLang = ""
        for (word in words) {
            val detected = detectLanguage(word)
            if (currentLang.isEmpty() || currentLang == detected) {
                currentLang = detected
                currentBuffer.append("$word ")
            } else {
                list.add(AutoTTSManagerService.LangChunk(currentBuffer.toString(), currentLang))
                currentBuffer = StringBuilder("$word ")
                currentLang = detected
            }
        }
        if (currentBuffer.isNotEmpty()) list.add(AutoTTSManagerService.LangChunk(currentBuffer.toString(), currentLang))
        return list
    }
}

// Resampler (Your existing code is fine, make sure it handles empty array)
object AudioResampler {
    fun resampleChunk(input: ByteArray, inputLength: Int, inRate: Int, outRate: Int): ByteArray {
        if (inRate == outRate) return input.copyOfRange(0, inputLength)
        // ... (Keep your logic) ...
        // Ensure you handle the case where output size is 0 to avoid crash
        try {
            val shortBuffer = ByteBuffer.wrap(input, 0, inputLength).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val inputSamples = ShortArray(shortBuffer.remaining())
            shortBuffer.get(inputSamples)
            if (inputSamples.isEmpty()) return ByteArray(0)
            val ratio = inRate.toDouble() / outRate.toDouble()
            val outputLength = (inputSamples.size / ratio).toInt()
            if(outputLength <= 0) return ByteArray(0) 
            
            val outputSamples = ShortArray(outputLength)
            // ... (Your loop logic) ...
             for (i in 0 until outputLength) {
                val position = i * ratio
                val index = position.toInt()
                val fraction = position - index
                if (index >= inputSamples.size - 1) {
                    outputSamples[i] = inputSamples[inputSamples.size - 1]
                } else {
                    val s1 = inputSamples[index]
                    val s2 = inputSamples[index + 1]
                    val value = s1 + (fraction * (s2 - s1)).toInt()
                    outputSamples[i] = value.toShort()
                }
            }
            val outputBytes = ByteArray(outputSamples.size * 2)
            ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outputSamples)
            return outputBytes
        } catch (e: Exception) { return ByteArray(0) }
    }
}

