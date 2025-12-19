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
    private var writerFuture: Future<*>? = null 
    private var readerFuture: Future<*>? = null
    
    private var currentReadFd: ParcelFileDescriptor? = null
    private var currentWriteFd: ParcelFileDescriptor? = null

    private val isStopped = AtomicBoolean(false)
    private lateinit var prefs: SharedPreferences

    private val OUTPUT_HZ = 24000 
    private var currentInputRate = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoTTS:SilentLock")
            wakeLock?.setReferenceCounted(false)
        } catch (e: Exception) { }

        try {
            prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName, Locale("shn")) { shanEngine = it }
            burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }
            englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName, Locale.US) { englishEngine = it }
        } catch (e: Exception) { }
    }

    private fun initEngine(pkg: String?, locale: Locale, onSuccess: (TextToSpeech) -> Unit) {
        if (pkg.isNullOrEmpty()) return
        try {
            var tempTTS: TextToSpeech? = null
            tempTTS = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try { tempTTS?.language = locale } catch (e: Exception) {}
                    onSuccess(tempTTS!!)
                }
            }, pkg)
        } catch (e: Exception) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
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
                        AudioProcessor.flush() 
                        AudioProcessor.setConfig(1.0f, 1.0f)
                    }

                    val params = Bundle()
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, getVolumeCorrection(engineData.pkgName))
                    val uuid = UUID.randomUUID().toString()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

                    processDualThreads(engine, params, chunk.text, callback, uuid)
                }
            } catch (e: Exception) {
            } finally {
                if (!isStopped.get()) callback?.done()
                try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
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
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            currentReadFd = pipe[0]
            currentWriteFd = pipe[1]
            
            writerFuture = pipeExecutor.submit {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                try { 
                    engine.synthesizeToFile(text, params, currentWriteFd!!, uuid) 
                } 
                catch (e: Exception) { } 
                finally { try { currentWriteFd?.close() } catch (e: Exception) {} }
            }

            readerFuture = pipeExecutor.submit {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                var fis: FileInputStream? = null
                var channel: FileChannel? = null
                
                val inBuffer = ByteBuffer.allocateDirect(4096)
                val outBuffer = ByteArray(16384) 
                
                try {
                    fis = FileInputStream(currentReadFd!!.fileDescriptor)
                    channel = fis.channel
                    
                    while (!isStopped.get() && !Thread.currentThread().isInterrupted) {
                        inBuffer.clear()
                        val read = channel.read(inBuffer)
                        
                        if (read == -1) {
                            AudioProcessor.flush()
                            var flushBytes = 1
                            while (flushBytes > 0 && !isStopped.get()) {
                                flushBytes = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                                if (flushBytes > 0) {
                                    sendAudioToSystem(outBuffer, flushBytes, callback)
                                }
                            }
                            break
                        }
                        
                        if (read > 0) {
                            if (isStopped.get()) break
                            var bytesProcessed = AudioProcessor.processAudio(inBuffer, read, outBuffer)
                            if (bytesProcessed > 0) sendAudioToSystem(outBuffer, bytesProcessed, callback)
                            
                            while (bytesProcessed > 0 && !isStopped.get()) {
                                bytesProcessed = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                                if (bytesProcessed > 0) sendAudioToSystem(outBuffer, bytesProcessed, callback)
                            }
                        }
                    }

                } catch (e: Exception) {
                } finally { try { fis?.close() } catch (e: Exception) {} }
            }
            try { writerFuture?.get(); readerFuture?.get() } catch (e: Exception) {}
        } catch (e: Exception) {
        } finally {
            try { currentWriteFd?.close() } catch (e: Exception) {}
            try { currentReadFd?.close() } catch (e: Exception) {}
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
        writerFuture?.cancel(true)
        readerFuture?.cancel(true)
        
        try { currentWriteFd?.close() } catch(e: Exception) {}
        try { currentReadFd?.close() } catch(e: Exception) {}

        try {
            shanEngine?.stop()
            burmeseEngine?.stop()
            englishEngine?.stop()
        } catch (e: Exception) {}

        AudioProcessor.stop()
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
        AudioProcessor.stop()
        super.onDestroy() 
    }
    
    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

