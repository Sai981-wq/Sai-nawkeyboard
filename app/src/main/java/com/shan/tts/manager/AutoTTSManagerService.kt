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

    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""
    
    private val controllerExecutor = Executors.newSingleThreadExecutor()
    // Using CachedThreadPool can cause thread overload if many rapid stops happen.
    // Using SingleThreadExecutor for Pipe ensures sequential processing.
    private val pipeExecutor = Executors.newSingleThreadExecutor()
    
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    
    private lateinit var settingsPrefs: SharedPreferences
    
    // MASTER FIXED RATE
    private val MASTER_HZ = 16000
    
    override fun onCreate() {
        super.onCreate()
        try {
            settingsPrefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            
            shanPkgName = settingsPrefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName) { shanEngine = it }
            
            burmesePkgName = settingsPrefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName) { 
                it.language = Locale("my", "MM")
                burmeseEngine = it 
            }
            
            englishPkgName = settingsPrefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName) { 
                it.language = Locale.US
                englishEngine = it 
            }
            
            // Pre-init to avoid null checks
            AudioProcessor.initSonic(MASTER_HZ, 1)
        } catch (e: Exception) { e.printStackTrace() }
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
                }
            }, pkg)
        } catch (e: Exception) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
        // HARD RESET: Stop everything immediately
        stopEverything()
        isStopped.set(false)

        currentTask = controllerExecutor.submit {
            try {
                if (callback == null) return@submit
                val rawChunks = LanguageUtils.splitHelper(text) 
                if (rawChunks.isEmpty()) {
                    callback.done()
                    return@submit
                }
                
                synchronized(callback) {
                    callback.start(MASTER_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

                for (chunk in rawChunks) {
                    if (isStopped.get()) break
                    
                    var effectiveLang = chunk.lang
                    // Thai/Shan Fix
                    if ((effectiveLang == "ENGLISH" || effectiveLang == "ENG") && containsThaiOrShanChar(chunk.text)) {
                        effectiveLang = "SHAN"
                    }

                    val engine = getEngine(effectiveLang) ?: continue
                    val activePkg = getPkgName(effectiveLang)
                    val (rateMultiplier, pitchMultiplier) = getRateAndPitch(effectiveLang, request)
                    
                    try {
                        engine.setSpeechRate(rateMultiplier)
                        engine.setPitch(pitchMultiplier)
                    } catch (e: Exception) {}

                    val params = Bundle()
                    val balanceVolume = getVolumeCorrection(activePkg)
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, balanceVolume)
                    val uuid = UUID.randomUUID().toString()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

                    AudioProcessor.setConfig(1.0f, 1.0f)
                    
                    // Blocking call to process audio
                    processPipeCpp(engine, params, chunk.text, callback, uuid)
                }
            } catch (e: Exception) { e.printStackTrace()
            } finally { 
                if (!isStopped.get()) callback?.done() 
            }
        }
    }

    private fun stopEverything() {
        isStopped.set(true)
        currentTask?.cancel(true)
        AudioProcessor.flush() // Native reset immediately
    }

    private fun containsThaiOrShanChar(text: String): Boolean {
        for (char in text) {
            val code = char.code
            if (code in 0x0E00..0x0E7F || code in 0x1000..0x109F) return true
        }
        return false
    }
    
    private fun getRateAndPitch(lang: String, request: SynthesisRequest?): Pair<Float, Float> {
        val sysRate = (request?.speechRate ?: 100) / 100.0f
        val sysPitch = (request?.pitch ?: 100) / 100.0f
        // Default multipliers
        var uR = 1.0f
        var uP = 1.0f
        // Logic for prefs can be added here if needed, keeping it simple for now
        return Pair(sysRate * uR, sysPitch * uP)
    }

    private fun getPkgName(lang: String) = when (lang) {
        "SHAN" -> shanPkgName
        "MYANMAR" -> burmesePkgName
        else -> englishPkgName
    }
    
    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return when {
            l.contains("vocalizer") -> 0.85f
            l.contains("eloquence") -> 0.6f
            else -> 1.0f
        }
    }
    
    private fun processPipeCpp(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        var lR: ParcelFileDescriptor? = null; var lW: ParcelFileDescriptor? = null
        try {
            val pipe = ParcelFileDescriptor.createPipe(); lR = pipe[0]; lW = pipe[1]
            val fR = lR
            
            // Using a Future to wait for completion
            val readerFuture = pipeExecutor.submit {
                var fis: FileInputStream? = null
                try {
                    fis = FileInputStream(fR.fileDescriptor)
                    
                    // HEADER DETECTION (CRITICAL for Rate Sync)
                    val headerBuf = ByteArray(44)
                    var headerRead = 0
                    while (headerRead < 44 && !isStopped.get()) {
                        val r = fis.read(headerBuf, headerRead, 44 - headerRead)
                        if (r == -1) break
                        headerRead += r
                    }

                    if (headerRead == 44) {
                        val detectedRate = ByteBuffer.wrap(headerBuf, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        if (detectedRate > 0) {
                            // Ensure Clean State for new Stream
                            AudioProcessor.flush() 
                            AudioProcessor.initSonic(detectedRate, 1)
                        }

                        // AUDIO BODY PROCESSING
                        val buffer = ByteArray(4096)
                        var leftover: Byte? = null
                        
                        while (!isStopped.get() && !Thread.currentThread().isInterrupted) {
                            
                            // Align leftover byte
                            var offset = 0
                            if (leftover != null) {
                                buffer[0] = leftover!!
                                offset = 1
                                leftover = null
                            }

                            val read = fis.read(buffer, offset, buffer.size - offset)
                            if (read == -1) break
                            
                            if (read > 0) {
                                var totalLen = read + offset
                                
                                // Ensure even length
                                if (totalLen % 2 != 0) {
                                    leftover = buffer[totalLen - 1]
                                    totalLen--
                                }
                                
                                if (totalLen > 0) {
                                    val out = AudioProcessor.processAudio(buffer, totalLen)
                                    if (out.isNotEmpty()) {
                                        synchronized(callback) {
                                            try { callback.audioAvailable(out, 0, out.size) } catch (e: Exception) {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: IOException) { 
                } finally { fis?.close(); fR?.close() }
            }
            
            engine.synthesizeToFile(text, params, lW!!, uuid)
            try { lW?.close() } catch(e:Exception){}
            
            // Wait for reader to finish or stop
            try { readerFuture.get() } catch (e: Exception) { }
            
        } catch (e: Exception) { lR?.close(); lW?.close() }
    }
    
    private fun getEngine(lang: String) = when (lang) {
        "SHAN" -> if (shanEngine != null) shanEngine else englishEngine
        "MYANMAR" -> if (burmeseEngine != null) burmeseEngine else englishEngine
        else -> englishEngine
    }

    override fun onStop() { 
        stopEverything()
    }
    
    override fun onDestroy() { 
        stopEverything()
        controllerExecutor.shutdownNow()
        pipeExecutor.shutdownNow()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

