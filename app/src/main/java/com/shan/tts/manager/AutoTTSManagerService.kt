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
import android.util.Log // Added native Log for direct Logcat
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

// Utility for Debug Logging
object DebugLog {
    private const val TAG = "CherryTTS_Debug"
    fun d(msg: String) {
        Log.d(TAG, msg)
        // Also log to AppLogger if needed
        // AppLogger.log(msg)
    }
    fun e(msg: String, e: Throwable?) {
        Log.e(TAG, msg, e)
        AppLogger.error(msg, e)
    }
}

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
    
    @Volatile private var sonicHandle: Long = 0
    
    private lateinit var configPrefs: SharedPreferences
    private lateinit var settingsPrefs: SharedPreferences
    
    override fun onCreate() {
        super.onCreate()
        DebugLog.d("=== SERVICE CREATED ===")
        
        try {
            settingsPrefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            
            shanPkgName = settingsPrefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            DebugLog.d("Initializing Shan Engine: $shanPkgName")
            initEngine(shanPkgName) { shanEngine = it }
            
            burmesePkgName = settingsPrefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            DebugLog.d("Initializing Burmese Engine: $burmesePkgName")
            initEngine(burmesePkgName) { 
                burmeseEngine = it
                it.language = Locale("my", "MM")
            }
            
            englishPkgName = settingsPrefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            DebugLog.d("Initializing English Engine: $englishPkgName")
            initEngine(englishPkgName) { 
                englishEngine = it
                it.language = Locale.US
            }
        } catch (e: Exception) {
            DebugLog.e("Error in onCreate", e)
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
                    DebugLog.d("Engine BOUND Successfully: $pkg")
                } else {
                    DebugLog.d("Engine FAILED to Bind: $pkg (Status: $status)")
                }
            }, pkg)
        } catch (e: Exception) {
            DebugLog.e("Exception Init Engine: $pkg", e)
        }
    }

    override fun onStop() {
        DebugLog.d(">>> STOP REQUEST RECEIVED <<<")
        isStopped.set(true)
        currentTask?.cancel(true)
        synchronized(this) {
            if (sonicHandle != 0L) {
                AudioProcessor.flush(sonicHandle)
            }
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        DebugLog.d(">>> START SYNTHESIS: '${text.take(20)}...' [Length: ${text.length}]")
        
        isStopped.set(false)
        currentTask?.cancel(true)

        currentTask = controllerExecutor.submit {
            try {
                if (callback == null) return@submit

                val chunks = LanguageUtils.splitHelper(text) 
                DebugLog.d("Text split into ${chunks.size} chunks.")

                if (chunks.isEmpty()) {
                    callback.done()
                    return@submit
                }
                
                synchronized(callback) {
                    // Always 24000 for output
                    callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

                var lastEngine: TextToSpeech? = null
                var lastRateMultiplier = -1.0f
                var lastPitchMultiplier = -1.0f
                var currentSonicRate = -1

                for ((index, chunk) in chunks.withIndex()) {
                    if (isStopped.get() || Thread.currentThread().isInterrupted) {
                        DebugLog.d("Synthesis Interrupted at chunk $index")
                        break
                    }
                    
                    val engine = getEngine(chunk.lang)
                    val activePkg = getPkgName(chunk.lang)
                    
                    if (engine == null) {
                        DebugLog.d("SKIP Chunk $index: No engine found for ${chunk.lang}")
                        continue
                    }

                    DebugLog.d("--- Processing Chunk $index [${chunk.lang}] ---")
                    DebugLog.d("    Engine: $activePkg")
                    DebugLog.d("    Text: '${chunk.text}'")

                    val (rateMultiplier, pitchMultiplier) = getRateAndPitch(chunk.lang, request)
                    
                    if (engine !== lastEngine || rateMultiplier != lastRateMultiplier || pitchMultiplier != lastPitchMultiplier) {
                        try {
                            engine.setSpeechRate(rateMultiplier)
                            engine.setPitch(pitchMultiplier)
                            DebugLog.d("    Applied Params: Rate=$rateMultiplier, Pitch=$pitchMultiplier")
                        } catch (e: Exception) {
                            DebugLog.e("    Failed to set params", e)
                        }
                        lastEngine = engine
                        lastRateMultiplier = rateMultiplier
                        lastPitchMultiplier = pitchMultiplier
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
                         DebugLog.d("    Rate Not Found. Fallback to: $engineRate Hz")
                    } else {
                        DebugLog.d("    Using Saved Rate: $engineRate Hz")
                    }

                    synchronized(this) {
                        if (sonicHandle == 0L || currentSonicRate != engineRate) {
                            if (sonicHandle != 0L) {
                                AudioProcessor.release(sonicHandle)
                            }
                            sonicHandle = AudioProcessor.initSonic(engineRate, 1)
                            currentSonicRate = engineRate
                            DebugLog.d("    [Sonic] NEW INSTANCE created for $engineRate Hz")
                        } else {
                            // DebugLog.d("    [Sonic] REUSING instance")
                        }
                        AudioProcessor.setConfig(sonicHandle, 1.0f, 1.0f)
                    }

                    // Process Audio
                    processPipeCpp(engine, params, chunk.text, callback)
                }
            } catch (e: Exception) {
                DebugLog.e("CRITICAL ERROR in Synthesis Loop", e)
            } finally {
                if (!isStopped.get()) {
                    DebugLog.d(">>> SYNTHESIS DONE <<<")
                    callback?.done()
                } else {
                    DebugLog.d(">>> SYNTHESIS STOPPED <<<")
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
        if (lower.contains("espeak") || lower.contains("shan")) return 1.0f 
        if (lower.contains("vocalizer")) return 0.85f
        if (lower.contains("eloquence")) return 0.4f
        return 1.0f 
    }
    
    private fun processPipeCpp(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback) {
        val uuid = UUID.randomUUID().toString()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

        var localReadFd: ParcelFileDescriptor? = null
        var localWriteFd: ParcelFileDescriptor? = null

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            localReadFd = pipe[0]
            localWriteFd = pipe[1]
            
            val finalReadFd = localReadFd
            
            DebugLog.d("    [Pipe] Created. Starting Engine synthesizeToFile...")

            val readerFuture = pipeExecutor.submit {
                var fis: FileInputStream? = null
                var totalBytesRead = 0
                var firstByteReceived = false
                
                try {
                    val fd = finalReadFd.fileDescriptor
                    fis = FileInputStream(fd)
                    
                    val buffer = ByteArray(4096)
                    var leftoverByte: Byte? = null
                    
                    while (!isStopped.get() && !Thread.currentThread().isInterrupted) {
                        
                        var offset = 0
                        if (leftoverByte != null) {
                            buffer[0] = leftoverByte!!
                            leftoverByte = null
                            offset = 1
                        }

                        // DebugLog: Wait for read
                        val readAmount = fis.read(buffer, offset, buffer.size - offset)
                        
                        if (readAmount == -1) {
                            DebugLog.d("    [Pipe] End of Stream (readAmount = -1)")
                            break
                        }

                        if (!firstByteReceived && readAmount > 0) {
                            firstByteReceived = true
                            DebugLog.d("    [Pipe] First Data Received! Engine is working.")
                        }
                        
                        var totalBytes = readAmount + offset
                        totalBytesRead += totalBytes
                        
                        if (totalBytes > 0) {
                             if (totalBytes % 2 != 0) {
                                 leftoverByte = buffer[totalBytes - 1]
                                 totalBytes -= 1
                             }
                             
                             if (totalBytes > 0) {
                                 val pcmData = ByteArray(totalBytes)
                                 System.arraycopy(buffer, 0, pcmData, 0, totalBytes)
                                 
                                 val currentHandle = sonicHandle
                                 if (currentHandle != 0L) {
                                     val processed = AudioProcessor.processAudio(currentHandle, pcmData, totalBytes)
                                     
                                     if (processed.isNotEmpty()) {
                                         synchronized(callback) {
                                             try { callback.audioAvailable(processed, 0, processed.size) } 
                                             catch (e: Exception) { DebugLog.e("Callback Error", e) }
                                         }
                                     }
                                 }
                             }
                        }
                    }
                } catch (e: IOException) {
                    DebugLog.d("    [Pipe] IOException (Expected on close)")
                } catch (e: Exception) {
                    DebugLog.e("    [Pipe] Unexpected Error", e)
                } finally {
                    DebugLog.d("    [Pipe] Reader Closed. Total Read: $totalBytesRead bytes")
                    try { fis?.close() } catch(e:Exception){}
                    try { finalReadFd.close() } catch(e:Exception){}
                }
            }

            if (localWriteFd != null) {
                val result = engine.synthesizeToFile(text, params, localWriteFd, uuid)
                if (result != TextToSpeech.SUCCESS) {
                    DebugLog.e("    [Engine] synthesizeToFile FAILED. Code: $result", null)
                }
            }
            
            try { localWriteFd?.close() } catch(e:Exception){}
            
            try {
                readerFuture.get()
            } catch (e: Exception) { 
                 DebugLog.e("    [Pipe] Future Task Error", e)
            }

        } catch (e: Exception) {
            DebugLog.e("    [Pipe] Setup Exception", e)
            try { localReadFd?.close() } catch(e:Exception){}
            try { localWriteFd?.close() } catch(e:Exception){}
        }
    }
    // ... Other methods (onDestroy, getEngine, etc) remain the same ...
    private fun getEngine(lang: String): TextToSpeech? {
        return when (lang) {
            "SHAN" -> if (shanEngine != null) shanEngine else englishEngine
            "MYANMAR" -> if (burmeseEngine != null) burmeseEngine else englishEngine
            else -> englishEngine
        }
    }

    override fun onDestroy() { 
        DebugLog.d("=== SERVICE DESTROYED ===")
        isStopped.set(true)
        controllerExecutor.shutdownNow()
        pipeExecutor.shutdownNow()
        
        try { shanEngine?.shutdown() } catch(e:Exception){}
        try { burmeseEngine?.shutdown() } catch(e:Exception){}
        try { englishEngine?.shutdown() } catch(e:Exception){}
        
        synchronized(this) {
            if (sonicHandle != 0L) {
                AudioProcessor.release(sonicHandle)
                sonicHandle = 0L
            }
        }
        super.onDestroy()
    }
    
    override fun onGetVoices(): List<Voice> { return listOf() }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int { return TextToSpeech.LANG_COUNTRY_AVAILABLE }
    override fun onGetLanguage(): Array<String> { return arrayOf("eng", "USA", "") }
}

