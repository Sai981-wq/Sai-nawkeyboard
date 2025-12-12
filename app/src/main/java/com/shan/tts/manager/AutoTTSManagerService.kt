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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

    // Pipe Tracker
    private val activePipes = ConcurrentHashMap<String, ParcelFileDescriptor>()

    private var currentLocale: Locale = Locale.US
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val TARGET_SAMPLE_RATE = 24000
    private val STREAM_BUFFER_SIZE = 4096

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
                closePipeForUtterance(utteranceId)
            }

            override fun onError(utteranceId: String?) {
                closePipeForUtterance(utteranceId)
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                closePipeForUtterance(utteranceId)
            }
        })
    }

    private fun closePipeForUtterance(utteranceId: String?) {
        utteranceId?.let {
            val pfd = activePipes.remove(it)
            try { pfd?.close() } catch (e: Exception) { }
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

    override fun onStop() {
        isStopped.set(true)
        currentTask?.cancel(true)
        
        activePipes.values.forEach { try { it.close() } catch (e: Exception){} }
        activePipes.clear()
        
        shanEngine?.stop()
        burmeseEngine?.stop()
        englishEngine?.stop()
        
        releaseWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val sysRate = request?.speechRate ?: 100
        val sysPitch = request?.pitch ?: 100

        isStopped.set(true)
        currentTask?.cancel(true)
        
        activePipes.values.forEach { try { it.close() } catch (e: Exception){} }
        activePipes.clear()

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
                    
                    // callback.start() ကို ဒီ function ထဲမှာမှ Header စစ်ပြီး ခေါ်ပါမယ်
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
            val pipe = ParcelFileDescriptor.createPipe()
            readSide = pipe[0]
            writeSide = pipe[1]
            
            activePipes[utteranceId] = writeSide

            val params = Bundle()
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

            // Start Engine (Async)
            engine.synthesizeToFile(text, params, writeSide, utteranceId)

            val inputStream = FileInputStream(readSide.fileDescriptor)
            
            // *** FIX 2: WAIT FOR DATA (Blocking Read) ***
            // Header 44 bytes မပြည့်မချင်း စောင့်ဖတ်မယ်
            // Pipe ထဲ Data မရောက်ခင် Read မိရင် 0 bytes ရမယ်၊ အဲဒါကို Loop ပတ်စောင့်မယ်
            val headerBuffer = ByteArray(44)
            var headerBytesRead = 0
            var retryCount = 0
            
            while (headerBytesRead < 44 && retryCount < 50) { // 5 seconds timeout approx
                if (isStopped.get()) break
                
                if (inputStream.available() > 0) {
                    val count = inputStream.read(headerBuffer, headerBytesRead, 44 - headerBytesRead)
                    if (count > 0) {
                        headerBytesRead += count
                    } else if (count == -1) {
                        break // End of stream unexpected
                    }
                } else {
                    // Pipe is empty, wait a bit
                    Thread.sleep(100)
                    retryCount++
                }
            }
            
            if (headerBytesRead < 44) return false // Engine fail or Stop

            // *** FIX 1: LAZY START ***
            // Header ရပြီ၊ အသံသေချာပြီဆိုမှ Start လုပ်မယ်
            if (!alreadyStarted) {
                callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                callbackStartedNow = true
            }

            // Determine Source Sample Rate
            val sourceSampleRate = getSampleRateFromWav(headerBuffer)

            // Streaming Loop
            val pcmBuffer = ByteArray(STREAM_BUFFER_SIZE)
            var bytesRead: Int

            while (true) {
                if (isStopped.get()) break
                
                // Blocking read (Stream will block until data arrives or pipe closes)
                bytesRead = inputStream.read(pcmBuffer)
                if (bytesRead == -1) break 

                val finalBytes = if (sourceSampleRate != TARGET_SAMPLE_RATE) {
                    AudioResampler.resampleChunk(pcmBuffer, bytesRead, sourceSampleRate, TARGET_SAMPLE_RATE)
                } else {
                    pcmBuffer.copyOfRange(0, bytesRead)
                }

                if (finalBytes.isNotEmpty()) {
                    callback?.audioAvailable(finalBytes, 0, finalBytes.size)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                readSide?.close()
                activePipes.remove(utteranceId)?.close()
            } catch (e: Exception) {}
        }
        
        return callbackStartedNow
    }

    private fun getSampleRateFromWav(header: ByteArray): Int {
        if (header.size < 28) return 16000
        return (header[24].toInt() and 0xFF) or
               ((header[25].toInt() and 0xFF) shl 8) or
               ((header[26].toInt() and 0xFF) shl 16) or
               ((header[27].toInt() and 0xFF) shl 24)
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
        if (currentBuffer.isNotEmpty()) list.add(LangChunk(currentBuffer.toString(), currentLang))
        return list
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
        activePipes.values.forEach { try{it.close()}catch(e:Exception){} }
        executor.shutdownNow()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }
    
    override fun onGetVoices(): List<Voice> { return listOf() }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onGetLanguage(): Array<String> { return arrayOf("eng", "USA", "") }
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

object AudioResampler {
    fun resampleChunk(input: ByteArray, inputLength: Int, inRate: Int, outRate: Int): ByteArray {
        if (inRate == outRate) return input.copyOfRange(0, inputLength)
        val shortBuffer = ByteBuffer.wrap(input, 0, inputLength).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val inputSamples = ShortArray(shortBuffer.remaining())
        shortBuffer.get(inputSamples)
        if (inputSamples.isEmpty()) return ByteArray(0)
        val ratio = inRate.toDouble() / outRate.toDouble()
        val outputLength = (inputSamples.size / ratio).toInt()
        val outputSamples = ShortArray(outputLength)
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
    }
}

