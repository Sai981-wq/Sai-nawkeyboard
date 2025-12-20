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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""

    // Queue အတွက် Data Class
    data class TTSRequest(
        val text: String,
        val callback: SynthesisCallback,
        val sysRate: Int,
        val sysPitch: Int,
        val uuid: String
    )
    private val requestQueue = LinkedBlockingQueue<TTSRequest>()
    
    // Worker Thread & Cancellation Control
    private var workerThread: Thread? = null
    private val isRunning = AtomicBoolean(true)
    private val currentRequestCancelled = AtomicBoolean(false)
    
    // လုပ်လက်စ Thread တွေကို လှမ်းပိတ်ဖို့ Global Variable အဖြစ်ထုတ်ထားခြင်း
    @Volatile private var currentWriterFuture: Future<*>? = null
    @Volatile private var currentReaderFuture: Future<*>? = null
    @Volatile private var currentReadFd: ParcelFileDescriptor? = null
    @Volatile private var currentWriteFd: ParcelFileDescriptor? = null

    private val pipeExecutor = Executors.newCachedThreadPool()
    private lateinit var prefs: SharedPreferences

    private val OUTPUT_HZ = 24000 
    private var currentInputRate = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoTTS:WorkerLock")
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

        startWorker()
    }

    // နောက်ကွယ်က အလုပ်သမား Thread
    private fun startWorker() {
        workerThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isRunning.get()) {
                try {
                    // အလုပ်မရှိရင် စောင့်မယ်
                    val request = requestQueue.poll(2, TimeUnit.SECONDS)
                    if (request != null) {
                        currentRequestCancelled.set(false)
                        try { wakeLock?.acquire(10 * 60 * 1000L) } catch (e: Exception) {}
                        
                        processRequest(request)
                        
                        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
                    }
                } catch (e: Exception) {
                    // Error တက်လဲ Loop မထွက်ဘူး
                }
            }
        }
        workerThread?.start()
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
        if (text.isNullOrEmpty() || callback == null) return

        // အသစ်လာရင် Queue ကိုရှင်းပြီး လက်ရှိလုပ်လက်စကို အတင်းဖြတ်ချမယ် (Kill Switch)
        forceStopCurrentWork()

        val uuid = UUID.randomUUID().toString()
        val reqData = TTSRequest(text, callback, request?.speechRate ?: 100, request?.pitch ?: 100, uuid)
        requestQueue.offer(reqData)
    }

    private fun forceStopCurrentWork() {
        requestQueue.clear() // တန်းစီနေတာတွေ ဖျက်မယ်
        currentRequestCancelled.set(true) // Loop တွေကို ရပ်ဖို့ အလံပြမယ်
        
        // လုပ်လက်စ Thread တွေကို အတင်း Cancel လုပ်မယ် (Stuck မဖြစ်အောင်)
        currentWriterFuture?.cancel(true)
        currentReaderFuture?.cancel(true)
        
        // Pipe တွေကို ပိတ်လိုက်မှ Read/Write လုပ်နေတာတွေ လွတ်ထွက်သွားမယ်
        try { currentWriteFd?.close() } catch (e: Exception) {}
        try { currentReadFd?.close() } catch (e: Exception) {}
    }

    private fun processRequest(req: TTSRequest) {
        try {
            val callback = req.callback
            val rawChunks = TTSUtils.splitHelper(req.text)
            if (rawChunks.isEmpty()) { 
                callback.done()
                return 
            }

            synchronized(callback) {
                try { callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1) } catch(e:Exception){}
            }

            for (chunk in rawChunks) {
                if (currentRequestCancelled.get()) break
                
                val engineData = getEngineDataForLang(chunk.lang)
                val engine = engineData.engine ?: continue
                val engineInputRate = determineInputRate(engineData.pkgName)

                try {
                    engine.setSpeechRate(req.sysRate / 100.0f)
                    engine.setPitch(req.sysPitch / 100.0f)
                } catch (e: Exception) {}

                // Hz ညှိဖို့အတွက် Sonic ကို သုံးပါတယ်
                if (currentInputRate != engineInputRate) {
                    AudioProcessor.initSonic(engineInputRate, 1)
                    currentInputRate = engineInputRate
                } else {
                    AudioProcessor.flush() 
                    AudioProcessor.setConfig(1.0f, 1.0f)
                }

                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, getVolumeCorrection(engineData.pkgName))
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, req.uuid)

                processDualThreads(engine, params, chunk.text, callback, req.uuid)
            }
            
            if (!currentRequestCancelled.get()) {
                try { callback.done() } catch(e:Exception){}
            }
        } catch (e: Exception) {
            // Log error but keep worker alive
        }
    }

    private fun processDualThreads(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            currentReadFd = pipe[0]
            currentWriteFd = pipe[1]
            
            // Writer Future
            currentWriterFuture = pipeExecutor.submit {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                try { 
                    engine.synthesizeToFile(text, params, currentWriteFd!!, uuid) 
                } 
                catch (e: Exception) { } 
                finally { try { currentWriteFd?.close() } catch (e: Exception) {} }
            }

            // Reader Future
            currentReaderFuture = pipeExecutor.submit {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                var fis: FileInputStream? = null
                var channel: FileChannel? = null
                val inBuffer = ByteBuffer.allocateDirect(8192) // Buffer ကြီးကြီးထားမယ်
                val outBuffer = ByteArray(16384) 
                
                try {
                    fis = FileInputStream(currentReadFd!!.fileDescriptor)
                    channel = fis.channel
                    
                    while (!currentRequestCancelled.get() && !Thread.currentThread().isInterrupted) {
                        inBuffer.clear()
                        val read = channel.read(inBuffer)
                        
                        if (read == -1) {
                            AudioProcessor.flush()
                            var flushBytes = 1
                            while (flushBytes > 0 && !currentRequestCancelled.get()) {
                                flushBytes = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                                if (flushBytes > 0) sendAudioToSystem(outBuffer, flushBytes, callback)
                            }
                            break
                        }
                        
                        if (read > 0) {
                            var bytesProcessed = AudioProcessor.processAudio(inBuffer, read, outBuffer)
                            if (bytesProcessed > 0) sendAudioToSystem(outBuffer, bytesProcessed, callback)
                            
                            while (bytesProcessed > 0 && !currentRequestCancelled.get()) {
                                bytesProcessed = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                                if (bytesProcessed > 0) sendAudioToSystem(outBuffer, bytesProcessed, callback)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Interrupted (Force Stop) ဖြစ်ရင် ဒီကိုရောက်မယ်
                } finally { try { fis?.close() } catch (e: Exception) {} }
            }
            
            // စောင့်မယ် (User က Stop မလုပ်သရွေ့)
            try { 
                currentWriterFuture?.get()
                currentReaderFuture?.get()
            } catch (e: Exception) {
                // Cancel လုပ်ရင် Exception တက်မယ်၊ ကျော်သွားမယ်
            }
            
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

    // Helper functions...
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
    
    override fun onStop() { 
        forceStopCurrentWork()
    }
    
    override fun onDestroy() { 
        isRunning.set(false)
        forceStopCurrentWork()
        workerThread?.interrupt()
        pipeExecutor.shutdownNow()
        
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        try {
            shanEngine?.stop()
            burmeseEngine?.stop()
            englishEngine?.stop()
        } catch (e: Exception) {}
        
        AudioProcessor.stop()
        super.onDestroy() 
    }
    
    override fun onGetVoices(): List<Voice> = listOf()
    override fun onIsLanguageAvailable(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(l: String?, c: String?, v: String?) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("eng", "USA", "")
}

