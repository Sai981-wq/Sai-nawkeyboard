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

    // ၁။ တန်းစီဇယား (Queue) တည်ဆောက်ခြင်း
    data class TTSRequest(
        val text: String,
        val callback: SynthesisCallback,
        val sysRate: Int,
        val sysPitch: Int,
        val uuid: String
    )
    private val requestQueue = LinkedBlockingQueue<TTSRequest>()

    // ၂။ နောက်ကွယ်က အလုပ်သမား (Worker Thread)
    private var workerThread: Thread? = null
    private val isRunning = AtomicBoolean(true)
    private val isInterrupted = AtomicBoolean(false) // ဖြတ်ချခံရခြင်း ရှိ/မရှိ စစ်ရန်

    private val pipeExecutor = Executors.newCachedThreadPool()
    private lateinit var prefs: SharedPreferences

    private val OUTPUT_HZ = 24000 
    private var currentInputRate = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        
        // Power Management
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoTTS:QueueWorker")
            wakeLock?.setReferenceCounted(false)
        } catch (e: Exception) { }

        // Load Settings & Engines
        try {
            prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName, Locale("shn")) { shanEngine = it }
            burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }
            englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName, Locale.US) { englishEngine = it }
        } catch (e: Exception) { }

        // ၃။ အလုပ်သမားကို စတင်ခိုင်းစေခြင်း
        startWorker()
    }

    private fun startWorker() {
        workerThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isRunning.get()) {
                try {
                    // Queue ထဲမှာ အလုပ်လာမယ့်အချိန်ကို စောင့်မယ် (5 seconds)
                    val request = requestQueue.poll(5, TimeUnit.SECONDS)
                    if (request != null) {
                        try { wakeLock?.acquire(10 * 60 * 1000L) } catch (e: Exception) {}
                        processRequest(request)
                        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
                    }
                } catch (e: InterruptedException) {
                    // Thread ကို နှိုးလိုက်ရင် (Stop လုပ်တဲ့အခါ) ဒီကိုရောက်မယ်
                } catch (e: Exception) {
                    // Other errors
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

    // System က စာဖတ်ခိုင်းတဲ့အခါ Queue ထဲထည့်မယ်
    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        if (text.isNullOrEmpty() || callback == null) return

        // အသစ်လာရင် အဟောင်းတွေကို ရှင်းထုတ်မယ် (Queue Clear)
        // ဒါမှ စာရိုက်တဲ့အခါ "မ", "မင်္ဂ" ဆိုပြီး ထပ်မနေမှာ
        stopForNewRequest()

        val uuid = UUID.randomUUID().toString()
        val reqData = TTSRequest(
            text, 
            callback, 
            request?.speechRate ?: 100, 
            request?.pitch ?: 100,
            uuid
        )
        
        // Queue ထဲထည့်မယ်
        requestQueue.offer(reqData)
    }

    private fun stopForNewRequest() {
        // Queue ထဲက စောင့်နေတဲ့ဟာတွေကို ဖျက်မယ်
        requestQueue.clear()
        // လက်ရှိလုပ်နေတဲ့ကောင်ကို ရပ်ဖို့ အလံပြမယ်
        isInterrupted.set(true)
        // Worker Thread အိပ်နေရင် နှိုးလိုက်မယ်
        // workerThread?.interrupt() // မလိုပါ၊ AtomicBoolean နဲ့တင်လုံလောက်ပါတယ်
    }

    // Worker Thread က လုပ်မယ့် အလုပ်
    private fun processRequest(req: TTSRequest) {
        isInterrupted.set(false) // အလုပ်စပြီ၊ အလံပြန်ချ
        val callback = req.callback
        val text = req.text

        val rawChunks = TTSUtils.splitHelper(text)
        if (rawChunks.isEmpty()) { 
            callback.done()
            return 
        }

        synchronized(callback) {
            callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
        }

        for (chunk in rawChunks) {
            // ကြားဖြတ်ခံရရင် ရပ်မယ်
            if (isInterrupted.get()) break
            
            val engineData = getEngineDataForLang(chunk.lang)
            val engine = engineData.engine ?: continue
            val engineInputRate = determineInputRate(engineData.pkgName)

            try {
                engine.setSpeechRate(req.sysRate / 100.0f)
                engine.setPitch(req.sysPitch / 100.0f)
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
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, req.uuid)

            processDualThreads(engine, params, chunk.text, callback, req.uuid)
        }
        
        if (!isInterrupted.get()) {
            callback.done()
        }
    }

    private fun processDualThreads(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        var lR: ParcelFileDescriptor? = null
        var lW: ParcelFileDescriptor? = null
        var writerFuture: java.util.concurrent.Future<*>? = null
        var readerFuture: java.util.concurrent.Future<*>? = null
        
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            lR = pipe[0]; lW = pipe[1]
            
            writerFuture = pipeExecutor.submit {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                try { 
                    engine.synthesizeToFile(text, params, lW!!, uuid) 
                } 
                catch (e: Exception) { } 
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
                    
                    while (!isInterrupted.get() && !Thread.currentThread().isInterrupted) {
                        inBuffer.clear()
                        val read = channel.read(inBuffer)
                        
                        if (read == -1) {
                            AudioProcessor.flush()
                            var flushBytes = 1
                            while (flushBytes > 0 && !isInterrupted.get()) {
                                flushBytes = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                                if (flushBytes > 0) {
                                    sendAudioToSystem(outBuffer, flushBytes, callback)
                                }
                            }
                            break
                        }
                        
                        if (read > 0) {
                            if (isInterrupted.get()) break
                            var bytesProcessed = AudioProcessor.processAudio(inBuffer, read, outBuffer)
                            if (bytesProcessed > 0) sendAudioToSystem(outBuffer, bytesProcessed, callback)
                            
                            while (bytesProcessed > 0 && !isInterrupted.get()) {
                                bytesProcessed = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                                if (bytesProcessed > 0) sendAudioToSystem(outBuffer, bytesProcessed, callback)
                            }
                        }
                    }
                } catch (e: Exception) {
                } finally { try { fis?.close() } catch (e: Exception) {} }
            }
            
            try { writerFuture.get(); readerFuture.get() } catch (e: Exception) {}
            
        } catch (e: Exception) {
        } finally {
            try { lW?.close() } catch (e: Exception) {}
            try { lR?.close() } catch (e: Exception) {}
        }
    }
    
    // Helper functions
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

    override fun onStop() { 
        stopForNewRequest()
    }
    
    override fun onDestroy() { 
        isRunning.set(false)
        stopForNewRequest()
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

