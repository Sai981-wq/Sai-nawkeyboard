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
    
    private val executor = Executors.newSingleThreadExecutor()
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    // Config
    private val TARGET_SAMPLE_RATE = 24000

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service", "Service Created")
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherryTTS:WakeLock")
        wakeLock?.setReferenceCounted(false)
        
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        initEngine("Shan", prefs.getString("pref_shan_pkg", "com.espeak.ng")) { shanEngine = it }
        initEngine("Burmese", prefs.getString("pref_burmese_pkg", "com.google.android.tts")) { 
            burmeseEngine = it
            it.language = Locale("my", "MM")
        }
        initEngine("English", prefs.getString("pref_english_pkg", "com.google.android.tts")) { 
            englishEngine = it
            it.language = Locale.US
        }
    }

    private fun initEngine(name: String, pkg: String?, onSuccess: (TextToSpeech) -> Unit) {
        AppLogger.log("Init", "Initializing $name with package: $pkg")
        if (pkg.isNullOrEmpty()) return
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    AppLogger.log("Init", "$name initialized SUCCESS")
                    onSuccess(tempTTS!!)
                    tempTTS?.setOnUtteranceProgressListener(object : UtteranceProgressListener(){
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {}
                        override fun onError(id: String?) {}
                    })
                } else {
                    AppLogger.log("Init", "$name initialized FAILED code: $status")
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.log("InitError", "Crash on $name: ${e.message}")
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        AppLogger.log("Synth", "Request received. Text length: ${text.length}")

        isStopped.set(true)
        currentTask?.cancel(true)
        isStopped.set(false)
        
        if (wakeLock?.isHeld == false) wakeLock?.acquire(60000)

        currentTask = executor.submit {
            try {
                AppLogger.log("Worker", "Processing started...")
                val chunks = splitByLanguage(text) 
                var hasStartedCallback = false

                for (chunk in chunks) {
                    if (isStopped.get()) break
                    AppLogger.log("Worker", "Processing chunk: [${chunk.lang}] ${chunk.text}")

                    val engine = getEngine(chunk.lang)
                    if (engine == null) {
                        AppLogger.log("Worker", "Engine is NULL for ${chunk.lang}")
                        continue
                    }

                    val started = processStream(engine, chunk.text, callback, hasStartedCallback)
                    if (started) hasStartedCallback = true
                }
                AppLogger.log("Worker", "All chunks finished.")
            } catch (e: Exception) {
                AppLogger.log("WorkerError", "Exception: ${e.message}")
                e.printStackTrace()
            } finally {
                if (!isStopped.get()) callback?.done()
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }

    private fun processStream(engine: TextToSpeech, text: String, callback: SynthesisCallback?, alreadyStarted: Boolean): Boolean {
        var didStart = false
        val uuid = UUID.randomUUID().toString()
        var readSide: ParcelFileDescriptor? = null
        var writeSide: ParcelFileDescriptor? = null

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            readSide = pipe[0]
            writeSide = pipe[1]

            val params = Bundle()
            val result = engine.synthesizeToFile(text, params, writeSide, uuid)
            
            // Important: Close write side immediately
            writeSide.close()
            writeSide = null 

            if (result != TextToSpeech.SUCCESS) return false

            val inputStream = FileInputStream(readSide.fileDescriptor)
            val headerBuffer = ByteArray(44)
            var headerBytesRead = 0
            
            // Header Reading (Blocking)
            while (headerBytesRead < 44) {
                 val c = inputStream.read(headerBuffer, headerBytesRead, 44 - headerBytesRead)
                 if (c == -1) break
                 headerBytesRead += c
            }
            AppLogger.log("Pipe", "Header read bytes: $headerBytesRead")

            if (headerBytesRead > 0) {
                 if (!alreadyStarted) {
                     callback?.start(TARGET_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                     didStart = true
                 }
                 
                 // Get Source Rate
                 val sourceRate = if (headerBytesRead >= 44) getSampleRateFromWav(headerBuffer) else TARGET_SAMPLE_RATE
                 
                 val pcmBuffer = ByteArray(4096)
                 while (true) {
                     if (isStopped.get()) break
                     val bytesRead = inputStream.read(pcmBuffer)
                     if (bytesRead == -1) break
                     
                     if (bytesRead > 0) {
                         // Resample if needed
                         val finalBytes = if (sourceRate != TARGET_SAMPLE_RATE) {
                             AudioResampler.resampleChunk(pcmBuffer, bytesRead, sourceRate, TARGET_SAMPLE_RATE)
                         } else {
                             if (bytesRead == pcmBuffer.size) pcmBuffer else pcmBuffer.copyOfRange(0, bytesRead)
                         }
                         
                         if (finalBytes.isNotEmpty()) {
                            callback?.audioAvailable(finalBytes, 0, finalBytes.size)
                         }
                     }
                 }
            }

        } catch (e: Exception) {
            AppLogger.log("PipeError", "Stream Failed: ${e.message}")
        } finally {
            try { readSide?.close() } catch (e: Exception) {}
            try { writeSide?.close() } catch (e: Exception) {}
        }
        return didStart
    }
    
    private fun getSampleRateFromWav(header: ByteArray): Int {
        if (header.size < 28) return 24000
        return (header[24].toInt() and 0xFF) or
               ((header[25].toInt() and 0xFF) shl 8) or
               ((header[26].toInt() and 0xFF) shl 16) or
               ((header[27].toInt() and 0xFF) shl 24)
    }

    private fun getEngine(lang: String): TextToSpeech? {
        return when (lang) {
            "SHAN" -> if (shanEngine != null) shanEngine else englishEngine
            "MYANMAR" -> if (burmeseEngine != null) burmeseEngine else englishEngine
            else -> englishEngine
        }
    }
    
    private fun splitByLanguage(text: String): List<LangChunk> {
         return LanguageUtils.splitHelper(text)
    }
    
    data class LangChunk(val text: String, val lang: String)

    override fun onStop() {
        AppLogger.log("Service", "onStop called")
        isStopped.set(true)
    }
    
    override fun onDestroy() {
        AppLogger.log("Service", "onDestroy called")
        isStopped.set(true)
        executor.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onGetVoices(): List<Voice> { return listOf() }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onGetLanguage(): Array<String> { return arrayOf("eng", "USA", "") }
}

// ================= HELPER OBJECTS =================

object AppLogger {
    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val dateFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(java.util.Date())
        val entry = "$timestamp [$tag] $message"
        android.util.Log.d(tag, message)
        logs.add(0, entry)
        if (logs.size > 500) logs.removeAt(logs.size - 1)
    }

    fun getAllLogs(): String = logs.joinToString("\n\n")
    fun clear() { logs.clear() }
}

object LanguageUtils {
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

object AudioResampler {
    fun resampleChunk(input: ByteArray, inputLength: Int, inRate: Int, outRate: Int): ByteArray {
        if (inRate == outRate) return input.copyOfRange(0, inputLength)
        
        try {
            val shortBuffer = ByteBuffer.wrap(input, 0, inputLength).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val inputSamples = ShortArray(shortBuffer.remaining())
            shortBuffer.get(inputSamples)
            
            if (inputSamples.isEmpty()) return ByteArray(0)
            
            val ratio = inRate.toDouble() / outRate.toDouble()
            val outputLength = (inputSamples.size / ratio).toInt()
            
            if(outputLength <= 0) return ByteArray(0) 
            
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
            
        } catch (e: Exception) { return ByteArray(0) }
    }
}

