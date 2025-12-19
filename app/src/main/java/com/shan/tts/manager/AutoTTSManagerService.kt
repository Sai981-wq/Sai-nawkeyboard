package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
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

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)

            shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName, Locale("shn")) { shanEngine = it }

            burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName, Locale.US) { englishEngine = it }
            
            AudioProcessor.initSonic(16000, 1)
        } catch (e: Exception) {
            AppLogger.error("Error in onCreate", e)
        }
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
        } catch (e: Exception) {
            AppLogger.error("Crash initializing $pkg", e)
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        
        // 1. Force Stop & Clean Previous State IMMEDIATELY
        stopEverything("New Request")
        isStopped.set(false)

        currentTask = controllerExecutor.submit {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            
            try {
                if (callback == null) return@submit
                
                val rawChunks = TTSUtils.splitHelper(text)
                if (rawChunks.isEmpty()) {
                    callback.done()
                    return@submit
                }

                // 2. Start callback ASAP
                synchronized(callback) {
                    callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
                }

                for (chunk in rawChunks) {
                    if (isStopped.get()) break

                    val engineData = getEngineDataForLang(chunk.lang)
                    val engine = engineData.engine ?: continue
                    
                    val engineInputRate = determineInputRate(engineData.pkgName)

                    // System Settings -> Engine Direct
                    val sysRate = request?.speechRate ?: 100
                    val sysPitch = request?.pitch ?: 100
                    try {
                        engine.setSpeechRate(sysRate / 100.0f)
                        engine.setPitch(sysPitch / 100.0f)
                    } catch (e: Exception) {}

                    // Refresh Sonic with Force Flush (via init)
                    // We call initSonic every chunk change if rate differs, 
                    // OR if we just started (to ensure flush)
                    if (currentInputRate != engineInputRate) {
                        AudioProcessor.initSonic(engineInputRate, 1)
                        currentInputRate = engineInputRate
                    } else {
                        // Even if rate is same, ensure config is enforced (no speed change)
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

    // ROBUST DUAL THREAD PROCESSING (Fixes Crash/Freeze)
    private fun processDualThreads(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        var lR: ParcelFileDescriptor? = null
        var lW: ParcelFileDescriptor? = null
        var writerFuture: Future<*>? = null
        var readerFuture: Future<*>? = null

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            lR = pipe[0]; lW = pipe[1]
            
            // Writer Thread
            writerFuture = pipeExecutor.submit {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                try { 
                    engine.synthesizeToFile(text, params, lW!!, uuid) 
                } catch (e: Exception) {
                    // Ignore synthesize errors
                } finally {
                    // CRITICAL: Always close Write end immediately after writing
                    try { lW?.close() } catch (e: Exception) {}
                }
            }

            // Reader Thread
            readerFuture = pipeExecutor.submit {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                var fis: FileInputStream? = null
                var channel: FileChannel? = null
                try {
                    fis = FileInputStream(lR!!.fileDescriptor)
                    channel = fis.channel
                    
                    // 8KB Buffer (Sweet Spot for TalkBack speed vs smoothness)
                    val buffer = ByteBuffer.allocateDirect(8192) 
                    
                    while (!isStopped.get() && !Thread.currentThread().isInterrupted) {
                        buffer.clear()
                        val read = channel.read(buffer)
                        if (read == -1) break
                        
                        if (read > 0) {
                            if (isStopped.get()) break
                            
                            val out = AudioProcessor.processAudio(buffer, read)
                            if (out.isNotEmpty()) {
                                sendAudioToSystem(out, callback)
                            }
                        }
                    }
                    if (!isStopped.get()) {
                        var tail = AudioProcessor.drain()
                        while (tail.isNotEmpty() && !isStopped.get()) {
                             sendAudioToSystem(tail, callback)
                             tail = AudioProcessor.drain()
                        }
                    }
                } catch (e: IOException) {
                    // IO Stop is normal
                } finally {
                    try { fis?.close() } catch (e: Exception) {}
                }
            }
            
            // Wait for both to finish (or be cancelled)
            try { writerFuture.get(); readerFuture.get() } catch (e: Exception) {}

        } catch (e: Exception) {
            AppLogger.error("Setup Error", e)
        } finally {
            // DOUBLE SAFETY: Ensure descriptors are closed if threads crashed
            try { lW?.close() } catch (e: Exception) {}
            try { lR?.close() } catch (e: Exception) {}
        }
    }

    private fun sendAudioToSystem(out: ByteArray, callback: SynthesisCallback) {
        if (out.isEmpty()) return
        synchronized(callback) {
            try {
                var offset = 0
                while (offset < out.size) {
                    val chunkLen = min(4096, out.size - offset)
                    callback.audioAvailable(out, offset, chunkLen)
                    offset += chunkLen
                }
            } catch (e: Exception) {}
        }
    }

    private fun stopEverything(reason: String) {
        isStopped.set(true)
        // Cancel main task
        currentTask?.cancel(true)
        
        // CRITICAL: Flush Native Buffer immediately
        AudioProcessor.flush()
        
        // Reset Rate so next init flushes strictly
        currentInputRate = 0
    }

    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return when {
            l.contains("vocalizer") -> 0.85f; l.contains("eloquence") -> 0.6f; else -> 1.0f
        }
    }
    
    override fun onStop() { stopEverything("onStop") }
    override fun onDestroy() { stopEverything("Destroy"); controllerExecutor.shutdownNow(); pipeExecutor.shutdownNow(); AudioProcessor.flush(); super.onDestroy() }
    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

