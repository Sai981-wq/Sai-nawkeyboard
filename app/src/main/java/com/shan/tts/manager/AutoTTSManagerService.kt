package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

data class LangChunk(val text: String, val lang: String)

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""
    
    private val controllerExecutor = Executors.newSingleThreadExecutor()
    private val pipeExecutor = Executors.newCachedThreadPool()
    
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    private val stateLock = Any()
    
    private lateinit var configPrefs: SharedPreferences
    private lateinit var settingsPrefs: SharedPreferences
    
    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service Created.")
        
        try {
            settingsPrefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            
            shanPkgName = settingsPrefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName) { shanEngine = it }
            
            burmesePkgName = settingsPrefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName) { 
                burmeseEngine = it
                it.language = Locale("my", "MM")
            }
            
            englishPkgName = settingsPrefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName) { 
                englishEngine = it
                it.language = Locale.US
            }
        } catch (e: Exception) {
            AppLogger.error("CRITICAL: Error during onCreate", e)
        }
    }

    private fun initEngine(pkg: String?, onSuccess: (TextToSpeech) -> Unit) {
        if (pkg.isNullOrEmpty()) return
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    onSuccess(tempTTS!!)
                    tempTTS?.setOnUtteranceProgressListener(object : UtteranceProgressListener(){
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {}
                        override fun onError(id: String?) {}
                    })
                    AppLogger.log("Engine Ready: $pkg")
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.error("Exception Init Engine: $pkg", e)
        }
    }

    override fun onStop() {
        synchronized(stateLock) {
            isStopped.set(true)
            currentTask?.cancel(true)
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
        synchronized(stateLock) {
            isStopped.set(false)
            currentTask?.cancel(true)
        }

        currentTask = controllerExecutor.submit {
            try {
                if (callback == null) return@submit

                val chunks = LanguageUtils.splitHelper(text) 
                if (chunks.isEmpty()) {
                    callback.done()
                    return@submit
                }
                
                // Fixed Master Rate 24000 Hz
                val sessionRate = 24000 
                AppLogger.log("Processing ${chunks.size} chunks at $sessionRate Hz")

                synchronized(callback) {
                    callback.start(sessionRate, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

                var lastEngine: TextToSpeech? = null
                var lastRate = -1.0f
                var lastPitch = -1.0f

                for ((index, chunk) in chunks.withIndex()) {
                    if (isStopped.get()) break
                    
                    val engine = getEngine(chunk.lang) ?: continue
                    val activePkg = getPkgName(chunk.lang)
                    
                    val (rateMultiplier, pitchMultiplier) = getRateAndPitch(chunk.lang, request)
                    
                    if (engine !== lastEngine || rateMultiplier != lastRate || pitchMultiplier != lastPitch) {
                        try {
                            engine.setSpeechRate(rateMultiplier)
                            engine.setPitch(pitchMultiplier)
                        } catch (e: Exception) {}
                        
                        lastEngine = engine
                        lastRate = rateMultiplier
                        lastPitch = pitchMultiplier
                    }
                    
                    val params = Bundle()
                    val balanceVolume = getVolumeCorrection(activePkg)
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, balanceVolume)

                    var engineRate = configPrefs.getInt("RATE_$activePkg", 0)
                    if (engineRate < 8000) {
                         val lowerPkg = activePkg.lowercase(Locale.ROOT)
                         engineRate = when {
                             lowerPkg.contains("espeak") || lowerPkg.contains("shan") -> 22050
                             lowerPkg.contains("eloquence") -> 11025
                             lowerPkg.contains("myanmar") -> 16000
                             else -> 24000
                         }
                    }

                    processPipeWithResample(engine, engineRate, sessionRate, params, chunk.text, callback)
                }
            } catch (e: Exception) {
                AppLogger.error("Synthesis Loop Error", e)
            } finally {
                if (!isStopped.get()) {
                    callback?.done()
                }
            }
        }
    }
    
    private fun getRateAndPitch(lang: String, request: SynthesisRequest?): Pair<Float, Float> {
        val sysRate = (request?.speechRate ?: 100) / 100.0f
        val sysPitch = (request?.pitch ?: 100) / 100.0f
        
        var rateSeekbar = 50
        var pitchSeekbar = 50
        
        try {
            when(lang) {
                "SHAN" -> {
                    rateSeekbar = settingsPrefs.getInt("rate_shan", 50)
                    pitchSeekbar = settingsPrefs.getInt("pitch_shan", 50)
                }
                "MYANMAR" -> {
                    rateSeekbar = settingsPrefs.getInt("rate_burmese", 50)
                    pitchSeekbar = settingsPrefs.getInt("pitch_burmese", 50)
                }
                else -> {
                    rateSeekbar = settingsPrefs.getInt("rate_english", 50)
                    pitchSeekbar = settingsPrefs.getInt("pitch_english", 50)
                }
            }
        } catch (e: Exception) {}
        
        var userRate = rateSeekbar / 50.0f
        var userPitch = pitchSeekbar / 50.0f
        
        if (userRate < 0.2f) userRate = 0.2f
        if (userPitch < 0.2f) userPitch = 0.2f
        
        return Pair(sysRate * userRate, sysPitch * userPitch)
    }

    private fun getPkgName(lang: String): String {
        return when (lang) {
            "SHAN" -> shanPkgName
            "MYANMAR" -> burmesePkgName
            else -> englishPkgName
        }
    }
    
    private fun getVolumeCorrection(pkgName: String): Float {
        val lower = pkgName.lowercase(Locale.ROOT)
        if (lower.contains("espeak") || lower.contains("shan")) return 0.6f 
        if (lower.contains("vocalizer")) return 0.85f
        if (lower.contains("eloquence")) return 0.5f
        return 1.0f 
    }
    
    private fun processPipeWithResample(engine: TextToSpeech, engineRate: Int, sessionRate: Int, params: Bundle, text: String, callback: SynthesisCallback) {
        val uuid = UUID.randomUUID().toString()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

        var localReadFd: ParcelFileDescriptor? = null
        var localWriteFd: ParcelFileDescriptor? = null

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            localReadFd = pipe[0]
            localWriteFd = pipe[1]
            
            val finalReadFd = localReadFd
            
            val readerFuture = pipeExecutor.submit {
                var fis: FileInputStream? = null
                try {
                    val fd = finalReadFd.fileDescriptor
                    fis = FileInputStream(fd)
                    val buffer = ByteArray(8192) // Increased buffer size
                    var bytesRead: Int
                    
                    // --- CRITICAL FIX: BYTE ALIGNMENT STORAGE ---
                    var leftoverByte: Byte? = null
                    
                    while (!isStopped.get()) {
                        // Check if we have a leftover byte from previous loop
                        var offset = 0
                        if (leftoverByte != null) {
                            buffer[0] = leftoverByte!!
                            leftoverByte = null
                            offset = 1
                        }

                        // Read into buffer starting from offset
                        val readAmount = fis.read(buffer, offset, buffer.size - offset)
                        if (readAmount == -1) break
                        
                        var totalBytes = readAmount + offset
                        
                        // Check if total bytes is Odd (Bad for PCM-16)
                        if (totalBytes > 0) {
                            if (totalBytes % 2 != 0) {
                                // Save the last byte for next time
                                leftoverByte = buffer[totalBytes - 1]
                                totalBytes -= 1
                            }

                            if (totalBytes > 0) {
                                 val finalAudio = if (engineRate != sessionRate) {
                                     TTSUtils.resample(buffer, totalBytes, engineRate, sessionRate)
                                 } else {
                                     buffer.copyOfRange(0, totalBytes)
                                 }
                                 
                                 synchronized(callback) {
                                     try {
                                         callback.audioAvailable(finalAudio, 0, finalAudio.size)
                                     } catch (e: Exception) { }
                                 }
                            }
                        }
                    }
                } catch (e: IOException) {
                } catch (e: Exception) {
                    AppLogger.error("Pipe Error", e)
                } finally {
                    try { fis?.close() } catch(e:Exception){}
                    try { finalReadFd.close() } catch(e:Exception){}
                }
            }

            if (localWriteFd != null) {
                engine.synthesizeToFile(text, params, localWriteFd, uuid)
            }
            
            try { localWriteFd?.close() } catch(e:Exception){}
            
            try {
                readerFuture.get()
            } catch (e: Exception) { }

        } catch (e: Exception) {
            AppLogger.error("Pipe Setup Exception", e)
            try { localReadFd?.close() } catch(e:Exception){}
            try { localWriteFd?.close() } catch(e:Exception){}
        }
    }
    
    private fun getEngine(lang: String): TextToSpeech? {
        return when (lang) {
            "SHAN" -> if (shanEngine != null) shanEngine else englishEngine
            "MYANMAR" -> if (burmeseEngine != null) burmeseEngine else englishEngine
            else -> englishEngine
        }
    }

    override fun onDestroy() { 
        AppLogger.log("Service Destroyed.")
        isStopped.set(true)
        controllerExecutor.shutdownNow()
        pipeExecutor.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        super.onDestroy()
    }
    
    override fun onGetVoices(): List<Voice> { return listOf() }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onGetLanguage(): Array<String> { return arrayOf("eng", "USA", "") }
}

