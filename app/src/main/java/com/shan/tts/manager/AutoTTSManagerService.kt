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
    private val pipeExecutor = Executors.newCachedThreadPool()
    
    private var currentTask: Future<*>? = null
    private val isStopped = AtomicBoolean(false)
    
    private lateinit var settingsPrefs: SharedPreferences
    
    private val OUTPUT_HZ = 16000
    
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
            
            AudioProcessor.initSonic(16000, 1)
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
                    callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

                for (chunk in rawChunks) {
                    if (isStopped.get()) break
                    
                    var effectiveLang = chunk.lang
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
                    
                    processPipeCpp(engine, params, chunk.text, callback, uuid)
                }
            } catch (e: Exception) { e.printStackTrace()
            } finally { if (!isStopped.get()) callback?.done() }
        }
    }

    private fun stopEverything() {
        isStopped.set(true)
        currentTask?.cancel(true)
        AudioProcessor.flush()
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
        var rS = 50; var pS = 50
        try {
            when(lang) {
                "SHAN" -> { rS = settingsPrefs.getInt("rate_shan", 50); pS = settingsPrefs.getInt("pitch_shan", 50) }
                "MYANMAR" -> { rS = settingsPrefs.getInt("rate_burmese", 50); pS = settingsPrefs.getInt("pitch_burmese", 50) }
                else -> { rS = settingsPrefs.getInt("rate_english", 50); pS = settingsPrefs.getInt("pitch_english", 50) }
            }
        } catch (e: Exception) {}
        var uR = rS / 50.0f; var uP = pS / 50.0f
        if (uR < 0.2f) uR = 0.2f; if (uP < 0.2f) uP = 0.2f
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
            val readerFuture = pipeExecutor.submit {
                var fis: FileInputStream? = null
                try {
                    fis = FileInputStream(fR.fileDescriptor)
                    val headerBuf = ByteArray(44)
                    var headerBytesRead = 0
                    while (headerBytesRead < 44 && !isStopped.get()) {
                        val r = fis.read(headerBuf, headerBytesRead, 44 - headerBytesRead)
                        if (r == -1) break
                        headerBytesRead += r
                    }

                    if (headerBytesRead == 44) {
                        val realSampleRate = ByteBuffer.wrap(headerBuf, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        if (realSampleRate > 0) {
                             AudioProcessor.flush()
                             AudioProcessor.initSonic(realSampleRate, 1) 
                        }

                        val buffer = ByteArray(4096)
                        var leftoverByte: Byte? = null
                        
                        while (!isStopped.get() && !Thread.currentThread().isInterrupted) {
                            var offset = 0
                            if (leftoverByte != null) {
                                buffer[0] = leftoverByte!!
                                offset = 1
                                leftoverByte = null
                            }

                            val read = fis.read(buffer, offset, buffer.size - offset)
                            if (read == -1) break
                            
                            if (read > 0) {
                                var totalLen = read + offset
                                if (totalLen % 2 != 0) {
                                    leftoverByte = buffer[totalLen - 1]
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
        controllerExecutor.shutdownNow(); pipeExecutor.shutdownNow()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
        super.onDestroy()
    }
    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

