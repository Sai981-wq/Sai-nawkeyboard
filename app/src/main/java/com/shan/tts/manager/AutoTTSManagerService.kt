package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""

    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "TTS_CONFIG"
    
    private val mIsStopped = AtomicBoolean(false)
    private val executorService = Executors.newCachedThreadPool()
    private val currentReadFd = AtomicReference<ParcelFileDescriptor?>(null)

    // Output ကို 24000Hz အသေ သတ်မှတ်ထားပါ (Standard for Android TTS)
    private val SYSTEM_OUTPUT_RATE = 24000

    private val outBufferLocal = object : ThreadLocal<ByteBuffer>() {
        override fun initialValue(): ByteBuffer = ByteBuffer.allocateDirect(8192).order(ByteOrder.LITTLE_ENDIAN)
    }

    private val outBufferArrayLocal = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray = ByteArray(8192)
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("=== Service Created ===")
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defaultEngine = getDefaultEngineFallback()

            shanPkgName = resolveEngine("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defaultEngine)
            AppLogger.log("Initializing Shan Engine: $shanPkgName")
            initEngine(shanPkgName, Locale("shn", "MM")) { shanEngine = it }

            burmesePkgName = resolveEngine("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defaultEngine)
            AppLogger.log("Initializing Burmese Engine: $burmesePkgName")
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = resolveEngine("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defaultEngine)
            AppLogger.log("Initializing English Engine: $englishPkgName")
            initEngine(englishPkgName, Locale.US) { englishEngine = it }

        } catch (e: Exception) {
            AppLogger.error("Error in onCreate", e)
        }
    }

    private fun getDefaultEngineFallback(): String {
        return try {
            val tts = TextToSpeech(this, null)
            val pkg = tts.defaultEngine
            tts.shutdown()
            pkg ?: "com.google.android.tts"
        } catch (e: Exception) { "com.google.android.tts" }
    }

    private fun resolveEngine(prefKey: String, priorityList: List<String>, fallback: String): String {
        val userPref = prefs.getString(prefKey, "")
        if (!userPref.isNullOrEmpty() && isPackageInstalled(userPref)) return userPref
        for (pkg in priorityList) if (isPackageInstalled(pkg)) return pkg
        return fallback
    }

    private fun isPackageInstalled(pkgName: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkgName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) { false }
    }

    private fun initEngine(pkg: String, locale: Locale, onReady: (TextToSpeech) -> Unit) {
        if (pkg.isEmpty()) return
        try {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(this, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    AppLogger.log("Engine initialized successfully: $pkg")
                    tts?.let { engine ->
                        onReady(engine)
                        try { engine.language = locale } catch (e: Exception) {
                             AppLogger.error("Failed to set language for $pkg", e)
                        }
                    }
                } else {
                    AppLogger.error("Engine initialization failed for $pkg with status $status")
                }
            }, pkg)
        } catch (e: Exception) { 
             AppLogger.error("Exception initializing engine $pkg", e)
        }
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE

    override fun onStop() {
        AppLogger.log("=== Request STOP ===")
        mIsStopped.set(true)
        val fd = currentReadFd.getAndSet(null)
        try { fd?.close() } catch (e: IOException) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            AppLogger.error("Request or Callback is null")
            return
        }

        mIsStopped.set(false)
        val text = request.charSequenceText.toString()
        val sysRate = request.speechRate / 100f
        val sysPitch = request.pitch / 100f
        
        AppLogger.log(">>> Synthesize Request: '${text.take(20)}...', Rate: $sysRate, Pitch: $sysPitch")
        
        val chunks = TTSUtils.splitHelper(text)
        AppLogger.log("Text split into ${chunks.size} chunks")

        // System ကို 24000Hz နဲ့ အမြဲ Start ပါ
        callback.start(SYSTEM_OUTPUT_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)

        for ((index, chunk) in chunks.withIndex()) {
            if (mIsStopped.get()) {
                AppLogger.log("Stopped during chunk processing")
                break
            }

            val langCode = when (chunk.lang) {
                "SHAN" -> "shn"
                "MYANMAR" -> "my"
                else -> "eng"
            }
            
            AppLogger.log("Processing Chunk $index: Lang=$langCode")
            
            val engineData = getEngineDataForLang(langCode)
            val targetEngine = engineData.engine
            val targetPkg = engineData.pkgName

            if (targetEngine == null) {
                AppLogger.error("Target engine is NULL for $langCode")
                // Try fallback to English engine if specific engine fails
                if (englishEngine != null) {
                     AppLogger.log("Falling back to English engine")
                     synchronized(englishEngine!!) {
                        englishEngine!!.setSpeechRate(sysRate)
                        englishEngine!!.setPitch(sysPitch)
                        processSafeStream(englishEngine!!, englishPkgName, chunk.text, callback)
                     }
                }
            } else {
                synchronized(targetEngine) {
                    targetEngine.setSpeechRate(sysRate)
                    targetEngine.setPitch(sysPitch)
                    processSafeStream(targetEngine, targetPkg, chunk.text, callback)
                }
            }
        }
        
        if (!mIsStopped.get()) {
            AppLogger.log("<<< Synthesis Done")
            callback.done()
        }
    }

    private fun processSafeStream(engine: TextToSpeech, pkgName: String, text: String, callback: SynthesisCallback) {
        AppLogger.log("Starting pipe stream for $pkgName")
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { 
            AppLogger.error("Failed to create pipe", e)
            return 
        }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd.set(readFd)

        // Engine Rate ကို ရှာပါ (e.g. 16000, 22050)
        val engineInputRate = prefs.getInt("RATE_$pkgName", 24000)
        AppLogger.log("Engine input rate for $pkgName: $engineInputRate Hz")
        
        // Sonic ကို Engine Rate အတိုင်း Initialize လုပ်ပါ
        val audioProcessor = AudioProcessor(engineInputRate, 1)

        // Resampling Hack: Speed = Input / Output
        val resampleRatio = engineInputRate.toFloat() / SYSTEM_OUTPUT_RATE.toFloat()
        AppLogger.log("Setting Sonic speed to $resampleRatio for resampling")
        audioProcessor.setSpeed(resampleRatio)

        val readerTask: Future<*> = executorService.submit {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            
            val buffer = ByteArray(4096)
            val localInBuffer = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)
            val localOutBuffer = outBufferLocal.get()!!
            val localByteArray = outBufferArrayLocal.get()!!
            
            var totalBytesRead = 0
            var totalBytesSent = 0

            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    AppLogger.log("Reader thread started, waiting for data...")
                    while (!mIsStopped.get()) {
                        val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }
                        
                        if (bytesRead == -1) {
                            AppLogger.log("End of stream reached (EOF)")
                            break
                        }

                        if (bytesRead > 0) {
                            if (totalBytesRead == 0) AppLogger.log("First data received! Size: $bytesRead")
                            totalBytesRead += bytesRead
                            
                            localInBuffer.clear()
                            if (localInBuffer.capacity() < bytesRead) { 
                                // Buffer check ignored
                            }
                            localInBuffer.put(buffer, 0, bytesRead)
                            localInBuffer.flip()

                            var processed = audioProcessor.process(localInBuffer, bytesRead, localOutBuffer, localOutBuffer.capacity())
                            
                            while (processed > 0 && !mIsStopped.get()) {
                                localOutBuffer.get(localByteArray, 0, processed)
                                sendAudioToSystem(localByteArray, processed, callback)
                                totalBytesSent += processed
                                
                                localOutBuffer.clear()
                                processed = audioProcessor.process(localInBuffer, 0, localOutBuffer, localOutBuffer.capacity())
                            }
                        }
                    }
                    
                    if (!mIsStopped.get()) {
                         AppLogger.log("Flushing Sonic queue...")
                         audioProcessor.flushQueue()
                         localOutBuffer.clear()
                         var processed = audioProcessor.process(localInBuffer, 0, localOutBuffer, localOutBuffer.capacity())
                         while (processed > 0 && !mIsStopped.get()) {
                             localOutBuffer.get(localByteArray, 0, processed)
                             sendAudioToSystem(localByteArray, processed, callback)
                             totalBytesSent += processed
                             
                             localOutBuffer.clear()
                             processed = audioProcessor.process(localInBuffer, 0, localOutBuffer, localOutBuffer.capacity())
                         }
                    }
                }
            } catch (e: Exception) { 
                AppLogger.error("Error in reader thread", e)
            } finally {
                AppLogger.log("Reader finished. Total Read: $totalBytesRead, Total Sent: $totalBytesSent")
                audioProcessor.release()
            }
        }

        val params = Bundle()
        // Important: Ensure volume is set
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        
        AppLogger.log("Calling engine.synthesizeToFile...")
        val result = engine.synthesizeToFile(text, params, writeFd, uuid)
        
        try { writeFd.close() } catch (e: IOException) { }

        if (result == TextToSpeech.SUCCESS) {
            AppLogger.log("synthesizeToFile returned SUCCESS")
            try { 
                readerTask.get() 
            } catch (e: Exception) {
                AppLogger.error("Reader task failed", e)
            }
        } else {
            AppLogger.error("synthesizeToFile returned ERROR")
            readerTask.cancel(true)
            audioProcessor.release()
        }
        
        currentReadFd.set(null)
    }

    private fun sendAudioToSystem(buffer: ByteArray, length: Int, callback: SynthesisCallback) {
        if (mIsStopped.get()) return
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        while (offset < length && !mIsStopped.get()) {
            val chunk = min(length - offset, maxBufferSize)
            callback.audioAvailable(buffer, offset, chunk)
            offset += chunk
        }
    }
    
    data class EngineData(val engine: TextToSpeech?, val pkgName: String)

    private fun getEngineDataForLang(lang: String): EngineData {
        return when (lang) {
            "SHAN", "shn" -> EngineData(if (shanEngine != null) shanEngine else englishEngine, shanPkgName)
            "MYANMAR", "my", "mya", "bur" -> EngineData(if (burmeseEngine != null) burmeseEngine else englishEngine, burmesePkgName)
            else -> EngineData(englishEngine, englishPkgName)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.log("=== Service Destroyed ===")
        mIsStopped.set(true)
        EngineScanner.stop()
        executorService.shutdownNow()
        
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
    }
}

