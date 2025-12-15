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
    
    // We keep track of FDs but we DO NOT close them in onStop anymore to prevent EBADF
    @Volatile private var activeReadFd: ParcelFileDescriptor? = null
    @Volatile private var activeWriteFd: ParcelFileDescriptor? = null
    
    private lateinit var configPrefs: SharedPreferences
    
    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service Created. Initializing engines...")
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
        
        shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
        initEngine(shanPkgName) { shanEngine = it }
        
        burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
        initEngine(burmesePkgName) { 
            burmeseEngine = it
            it.language = Locale("my", "MM")
        }
        
        englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
        initEngine(englishPkgName) { 
            englishEngine = it
            it.language = Locale.US
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
                } else {
                    AppLogger.error("Engine Init Failed: $pkg")
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.error("Exception Init Engine: $pkg", e)
        }
    }

    override fun onStop() { 
        // CRITICAL FIX: Just flag it. Do NOT close FDs here.
        // Closing FDs here causes "EBADF" (Bad File Descriptor) crashes in the reader thread.
        isStopped.set(true)
        currentTask?.cancel(true)
        AppLogger.log("Service Stopped")
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        // AppLogger.log(">>> NEW REQUEST: ${text.take(30)}...")
        
        onStop() // Ensure previous task is cancelled
        isStopped.set(false)

        currentTask = controllerExecutor.submit {
            try {
                if (callback == null) return@submit

                val chunks = LanguageUtils.splitHelper(text) 
                if (chunks.isEmpty()) {
                    callback.done()
                    return@submit
                }
                
                // Determine Session Master Rate
                val firstLang = chunks[0].lang
                val firstPkg = getPkgName(firstLang)
                
                var sessionRate = configPrefs.getInt("RATE_$firstPkg", 0)
                
                // Safety fix for Saomai or Unknown engines
                if (sessionRate < 8000) {
                     val lowerPkg = firstPkg.lowercase(Locale.ROOT)
                     sessionRate = when {
                         lowerPkg.contains("espeak") -> 22050
                         lowerPkg.contains("shan") -> 22050
                         lowerPkg.contains("eloquence") -> 11025
                         lowerPkg.contains("myanmar") -> 24000 // Saomai usually 24k
                         else -> 24000
                     }
                     AppLogger.log("Master rate fallback to $sessionRate Hz for $firstPkg")
                }

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
                        engine.setSpeechRate(rateMultiplier)
                        engine.setPitch(pitchMultiplier)
                        lastEngine = engine
                        lastRate = rateMultiplier
                        lastPitch = pitchMultiplier
                        try { Thread.sleep(5) } catch(e:Exception){}
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
                             else -> 24000
                        }
                    }
                    
                    // AppLogger.log("Processing Chunk $index: [${chunk.lang}] Rate: ${engineRate}Hz -> Master: ${sessionRate}Hz")
                    processDirectPipe(engine, engineRate, params, chunk.text, callback, sessionRate)
                }
            } catch (e: Exception) {
                AppLogger.error("Synthesis Critical Error", e)
            } finally {
                if (!isStopped.get()) {
                    callback?.done()
                    // AppLogger.log("<<< REQUEST DONE")
                }
            }
        }
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
    
    private fun getRateAndPitch(lang: String, request: SynthesisRequest?): Pair<Float, Float> {
        val prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        val sysRate = (request?.speechRate ?: 100) / 100.0f
        val sysPitch = (request?.pitch ?: 100) / 100.0f
        
        var rateSeekbar = 50
        var pitchSeekbar = 50
        
        when(lang) {
            "SHAN" -> {
                rateSeekbar = prefs.getInt("rate_shan", 50)
                pitchSeekbar = prefs.getInt("pitch_shan", 50)
            }
            "MYANMAR" -> {
                rateSeekbar = prefs.getInt("rate_burmese", 50)
                pitchSeekbar = prefs.getInt("pitch_burmese", 50)
            }
            else -> {
                rateSeekbar = prefs.getInt("rate_english", 50)
                pitchSeekbar = prefs.getInt("pitch_english", 50)
            }
        }
        
        var userRate = rateSeekbar / 50.0f
        var userPitch = pitchSeekbar / 50.0f
        
        if (userRate < 0.2f) userRate = 0.2f
        if (userRate > 3.0f) userRate = 3.0f
        if (userPitch < 0.3f) userPitch = 0.3f
        if (userPitch > 2.0f) userPitch = 2.0f
        
        return Pair(sysRate * userRate, sysPitch * userPitch)
    }

    private fun processDirectPipe(engine: TextToSpeech, engineRate: Int, params: Bundle, text: String, callback: SynthesisCallback, sessionRate: Int) {
        val uuid = UUID.randomUUID().toString()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

        var localReadFd: ParcelFileDescriptor? = null
        var localWriteFd: ParcelFileDescriptor? = null

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            localReadFd = pipe[0]
            localWriteFd = pipe[1]
            
            // Assign to globals for reference, but don't depend on them for logic
            activeReadFd = localReadFd
            activeWriteFd = localWriteFd

            val readerFuture = pipeExecutor.submit {
                var fis: FileInputStream? = null
                try {
                    val fd = localReadFd.fileDescriptor
                    fis = FileInputStream(fd)
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    
                    // Loop check isStopped.get() to exit cleanly
                    while (!isStopped.get()) {
                        bytesRead = fis.read(buffer)
                        if (bytesRead == -1) break
                        
                        if (bytesRead > 0) {
                             val finalAudio = if (engineRate != sessionRate) {
                                 TTSUtils.resample(buffer, bytesRead, engineRate, sessionRate)
                             } else {
                                 buffer.copyOfRange(0, bytesRead)
                             }
                             
                             synchronized(callback) {
                                 try {
                                     callback.audioAvailable(finalAudio, 0, finalAudio.size)
                                 } catch (e: Exception) {
                                     // callback might be closed, normal during stop
                                 }
                             }
                        }
                    }
                } catch (e: IOException) {
                    // Ignore "Bad file descriptor" during stop, it's expected
                    if (!isStopped.get()) {
                        AppLogger.error("Pipe Read Error", e)
                    }
                } catch (e: Exception) {
                    AppLogger.error("General Pipe Error", e)
                } finally {
                    try { fis?.close() } catch(e:Exception){}
                    try { localReadFd.close() } catch(e:Exception){}
                }
            }

            // Write side
            if (localWriteFd != null) {
                engine.synthesizeToFile(text, params, localWriteFd, uuid)
            }
            
            // Close write end to signal EOF to reader
            try { localWriteFd?.close() } catch(e:Exception){}
            
            // Wait for reader to finish
            try {
                readerFuture.get()
            } catch (e: Exception) {
                // Interrupted during stop is normal
            }

        } catch (e: Exception) {
            AppLogger.error("Pipe Setup Exception", e)
        } finally {
            // Ensure FDs are closed if something went wrong
            try { localReadFd?.close() } catch (e: Exception) {}
            try { localWriteFd?.close() } catch (e: Exception) {}
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

