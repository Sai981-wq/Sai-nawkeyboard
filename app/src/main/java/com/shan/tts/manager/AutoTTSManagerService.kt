package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

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
    private lateinit var prefs: SharedPreferences

    private val OUTPUT_HZ = 24000 
    private var currentInputRate = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service Creating...")
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoTTS:SilentLock")
            wakeLock?.setReferenceCounted(false)
        } catch (e: Exception) { AppLogger.error("WakeLock Error", e) }

        try {
            prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName, Locale("shn")) { shanEngine = it }
            burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }
            englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName, Locale.US) { englishEngine = it }
            
            AppLogger.log("Service Created")
        } catch (e: Exception) { AppLogger.error("Error in onCreate", e) }
    }

    private fun initEngine(pkg: String?, locale: Locale, onSuccess: (TextToSpeech) -> Unit) {
        if (pkg.isNullOrEmpty()) return
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try { tempTTS?.language = locale } catch (e: Exception) {}
                    onSuccess(tempTTS!!)
                    AppLogger.log("Engine Ready: $pkg")
                }
            }, pkg)
        } catch (e: Exception) { AppLogger.error("Crash initializing $pkg", e) }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        val sampleText = if (text.length > 20) text.substring(0, 20) + "..." else text
        AppLogger.log(">>> NEW REQUEST: '$sampleText'")
        
        stopEverything("New Request")
        isStopped.set(false)
        
        try { wakeLock?.acquire(10 * 60 * 1000L) } catch (e: Exception) {}

        currentTask = controllerExecutor.submit {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                if (callback == null) return@submit
                val rawChunks = TTSUtils.splitHelper(text)
                if (rawChunks.isEmpty()) { 
                    callback.done()
                    return@submit 
                }

                synchronized(callback) {
                    callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

                for (chunk in rawChunks) {
                    if (isStopped.get()) break
                    
                    val engineData = getEngineDataForLang(chunk.lang)
                    val engine = engineData.engine ?: continue
                    val engineInputRate = determineInputRate(engineData.pkgName)

                    val sysRate = request?.speechRate ?: 100
                    val sysPitch = request?.pitch ?: 100
                    try {
                        engine.setSpeechRate(sysRate / 100.0f)
                        engine.setPitch(sysPitch / 100.0f)
                    } catch (e: Exception) {}

                    if (currentInputRate != engineInputRate) {
                        AudioProcessor.initSonic(engineInputRate, 1)
                        currentInputRate = engineInputRate
                    } else {
                        AudioProcessor.setConfig(1.0f, 1.0f)
                    }

                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, getVolumeCorrection(engineData.pkgName))
                    val uuid = UUID.randomUUID().toString()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

                    processDualThreads(engine, params, chunk.text, callback, uuid)
                }
            } catch (e: Exception) {
                AppLogger.error("Synthesize Critical Error", e)
            } finally {
                if (!isStopped.get()) callback?.done()
                try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
                AppLogger.log("<<< REQUEST FINISHED")
            }
        }
    }
    
    private fun determineInputRate(pkgName: String): Int {
        val lowerPkg = pkgName.lowercase(Locale.ROOT)
        return when {
            lowerPkg.contains("com.shan.tts") -> 22050
            lowerPkg.contains("eloquence") -> 11025
            lowerPkg.contains("espeak") || lowerPkg.contains("shan") -> 22050
            lowerPkg.contains("google") -> 24000
            lowerPkg.contains("samsung") -> 22050 
            lowerPkg.contains("vocalizer") -> 22050
            else -> 16000 
        }
    }

    data class EngineData(val engine: TextToSpeech?, val pkgName: String, val rateKey: String, val pitchKey: String)
    private fun getEngineDataForLang(lang: String): EngineData {
        return when (lang) {
            "SHAN" -> EngineData(if (shanEngine != null) shanEngine else englishEngine, shanPkgName, "rate_shan", "pitch_shan")
            "MYANMAR" -> EngineData(if (burmeseEngine != null) burmeseEngine else englishEngine, burmesePkgName, "rate_burmese", "pitch_burmese")
            else -> EngineData(englishEngine, englishPkgName, "rate_english", "pitch_english")
        }
    }

    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return when {
            l.contains("vocalizer") -> 0.85f; l.contains("eloquence") -> 0.6f; else -> 1.0f
        }
    }

    private fun processDualThreads(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        var lR: ParcelFileDescriptor? = null
        var lW: ParcelFileDescriptor? = null
        var writerFuture: Future<*>? = null
        var readerFuture: Future<*>? = null

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            lR = pipe[0]; lW = pipe[1]
            
            writerFuture = pipeExecutor.submit {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                try { 
                    engine.synthesizeToFile(text, params, lW!!, uuid) 
                } 
                catch (e: Exception) { AppLogger.error("Writer Error", e) } 
                finally { try { lW?.close() } catch (e: Exception) {} }
            }

            readerFuture = pipeExecutor.submit {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                var fis: FileInputStream? = null
                var channel: FileChannel? = null
                
                val inBuffer = ByteBuffer.allocateDirect(4096)
                val outBuffer = ByteArray(16384) 
                
                try {
                    fis = FileInputStream(lR!!.fileDescriptor)
                    channel = fis.channel
                    
                    while (!isStopped.get() && !Thread.currentThread().isInterrupted) {
                        inBuffer.clear()
                        val read = channel.read(inBuffer)
                        
                        if (read == -1) break
                        
                        if (read > 0) {
                            if (isStopped.get()) break
                            
                            var bytesProcessed = AudioProcessor.processAudio(inBuffer, read, outBuffer)
                            
                            if (bytesProcessed > 0) {
                                sendAudioToSystem(outBuffer, bytesProcessed, callback)
                            }
                            
                            while (bytesProcessed > 0 && !isStopped.get()) {
                                bytesProcessed = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                                if (bytesProcessed > 0) {
                                    sendAudioToSystem(outBuffer, bytesProcessed, callback)
                                }
                            }
                        }
                    }

                } catch (e: Exception) {
                    if (e is java.nio.channels.ClosedByInterruptException || e is InterruptedException) {
                        // Normal interruption, ignore
                    } else {
                        AppLogger.error("Reader Error", e)
                    }
                } finally { try { fis?.close() } catch (e: Exception) {} }
            }
            try { writerFuture.get(); readerFuture.get() } catch (e: Exception) {}
        } catch (e: Exception) {
            AppLogger.error("Setup Error", e)
        } finally {
            try { lW?.close() } catch (e: Exception) {}
            try { lR?.close() } catch (e: Exception) {}
            writerFuture?.cancel(true)
            readerFuture?.cancel(true)
        }
    }

    private fun sendAudioToSystem(data: ByteArray, length: Int, callback: SynthesisCallback) {
        if (length <= 0) return
        synchronized(callback) {
            try {
                var offset = 0
                while (offset < length) {
                    val chunkLen = min(4096, length - offset)
                    callback.audioAvailable(data, offset, chunkLen)
                    offset += chunkLen
                }
            } catch (e: Exception) {}
        }
    }

    private fun stopEverything(reason: String) {
        isStopped.set(true)
        currentTask?.cancel(true)
        
        try {
            shanEngine?.stop()
            burmeseEngine?.stop()
            englishEngine?.stop()
        } catch (e: Exception) {}

        AudioProcessor.flush()
        // Removed: currentInputRate = 0
    }
    
    override fun onStop() { 
        stopEverything("onStop")
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
    }
    
    override fun onDestroy() { 
        stopEverything("Destroy")
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        controllerExecutor.shutdownNow()
        pipeExecutor.shutdownNow()
        AudioProcessor.flush()
        super.onDestroy() 
    }
    
    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

