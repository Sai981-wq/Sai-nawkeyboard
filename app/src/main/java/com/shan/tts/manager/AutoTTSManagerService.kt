package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
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
    
    private val currentReadFd = AtomicReference<ParcelFileDescriptor?>(null)
    private val currentWriteFd = AtomicReference<ParcelFileDescriptor?>(null)
    private var currentActiveEngine: TextToSpeech? = null

    private val SYSTEM_OUTPUT_RATE = 24000
    private val BUFFER_SIZE = 16384 
    private val MIN_AUDIO_CHUNK_SIZE = 4096

    // ★ POINT 4: RESOURCE REUSABILITY ★
    // Vnspeak လိုမျိုး Buffer တွေကို တခါတည်းဆောက်ပြီး ပြန်သုံးပါမယ် (Memory သက်သာစေရန်)
    private lateinit var sharedReadBuffer: ByteArray
    private lateinit var sharedOutputBuffer: ByteBuffer
    private lateinit var sharedAccumulator: ByteBuffer
    private lateinit var sharedTempArray: ByteArray

    override fun onCreate() {
        super.onCreate()
        try {
            // Buffer Initialization (Allocate Once)
            sharedReadBuffer = ByteArray(BUFFER_SIZE)
            sharedOutputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE * 4).order(ByteOrder.LITTLE_ENDIAN)
            sharedAccumulator = ByteBuffer.allocate(MIN_AUDIO_CHUNK_SIZE * 2)
            sharedTempArray = ByteArray(BUFFER_SIZE * 4)

            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defaultEngine = getDefaultEngineFallback()

            shanPkgName = resolveEngine("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defaultEngine)
            initEngine(shanPkgName, Locale("shn", "MM")) { shanEngine = it }

            burmesePkgName = resolveEngine("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defaultEngine)
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = resolveEngine("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defaultEngine)
            initEngine(englishPkgName, Locale.US) { englishEngine = it }
        } catch (e: Exception) {
            e.printStackTrace()
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
                    tts?.let { engine ->
                        onReady(engine)
                        try { engine.language = locale } catch (e: Exception) {}
                    }
                }
            }, pkg)
        } catch (e: Exception) { }
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE

    override fun onStop() {
        mIsStopped.set(true)
        closeQuietly(currentWriteFd.getAndSet(null))
        closeQuietly(currentReadFd.getAndSet(null))
        try { currentActiveEngine?.stop() } catch (e: Exception) { }
    }

    // ★ Synchronized ထည့်ထားပါတယ် (Shared Buffer တွေကို လုမသုံးမိအောင် Vnspeak လို ကာကွယ်ခြင်း)
    @Synchronized
    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        mIsStopped.set(false)
        val text = request.charSequenceText.toString()
        
        var rateVal = request.speechRate
        var pitchVal = request.pitch
        if (rateVal < 10) rateVal = 10
        if (pitchVal < 10) pitchVal = 10

        val sysRate = rateVal / 100.0f
        val sysPitch = pitchVal / 100.0f

        val chunks = TTSUtils.splitHelper(text)
        
        var targetPkg = englishPkgName 
        var targetEngine = englishEngine

        if (text.any { it.code in 0x1000..0x109F }) {
             val firstChunk = chunks.firstOrNull { it.lang != "ENGLISH" }
             if (firstChunk != null) {
                 val engineData = getEngineDataForLang(firstChunk.lang)
                 targetEngine = engineData.engine
                 targetPkg = engineData.pkgName
             } else {
                 targetEngine = burmeseEngine
                 targetPkg = burmesePkgName
             }
        }
        
        if (targetEngine == null) targetEngine = englishEngine

        var engineInputRate = prefs.getInt("RATE_$targetPkg", 0)
        if (engineInputRate == 0) engineInputRate = 24000

        try {
            // Clear Buffers before starting (Reusable Clean)
            sharedAccumulator.clear()
            sharedOutputBuffer.clear()

            val audioProcessor = AudioProcessor(engineInputRate, 1)
            
            try {
                callback.start(SYSTEM_OUTPUT_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)

                audioProcessor.setSpeed(sysRate)
                audioProcessor.setPitch(1.0f) 
                
                targetEngine!!.setSpeechRate(1.0f)
                targetEngine!!.setPitch(sysPitch)

                currentActiveEngine = targetEngine

                runBlocking {
                    for (chunk in chunks) {
                        if (mIsStopped.get()) break
                        processFully(targetEngine!!, chunk.text, callback, audioProcessor)
                    }
                    
                    if (!mIsStopped.get()) {
                        flushSonicBuffer(callback, audioProcessor)
                        callback.done()
                    }
                }
            } finally {
                audioProcessor.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun flushSonicBuffer(callback: SynthesisCallback, audioProcessor: AudioProcessor) {
        // Reuse shared buffers
        sharedOutputBuffer.clear()
        
        audioProcessor.flushQueue()
        var processed = audioProcessor.process(null, 0, sharedOutputBuffer, sharedOutputBuffer.capacity())
        
        while (processed > 0 && !mIsStopped.get()) {
            sharedOutputBuffer.get(sharedTempArray, 0, processed)
            // Use Safe Sending Logic
            sendToSystemSafely(sharedTempArray, processed, callback)
            
            sharedOutputBuffer.clear()
            processed = audioProcessor.process(null, 0, sharedOutputBuffer, sharedOutputBuffer.capacity())
        }
    }

    private suspend fun processFully(
        engine: TextToSpeech, 
        text: String, 
        callback: SynthesisCallback,
        audioProcessor: AudioProcessor
    ) = coroutineScope {

        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return@coroutineScope }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd.set(readFd)
        currentWriteFd.set(writeFd)

        val audioChannel = Channel<Int>(Channel.UNLIMITED) // Send bytes read count

        val drainerJob = launch(Dispatchers.IO) {
            // Use Shared Buffer instead of new ByteArray
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (isActive) {
                        // Shared Buffer is safe here because runBlocking serializes the flow mostly,
                        // BUT to be 100% thread-safe between Consumer/Drainer, 
                        // Drainer writes to sharedReadBuffer, Consumer reads from it? NO.
                        // Since they run concurrently, Drainer needs its own buffer or strict lock.
                        // Optimization: We actually need a separate buffer for reading to avoid race condition.
                        // Let's alloc small read buffer here, but reuse the BIG output buffers.
                        val localBuffer = ByteArray(BUFFER_SIZE) 
                        val bytesRead = try { fis.read(localBuffer) } catch (e: IOException) { -1 }
                        if (bytesRead == -1) break 
                        if (bytesRead > 0) {
                            // Copy to channel to avoid shared state issues in async
                            // (Channel sending byte array is still efficient)
                            // For maximum strict reuse we'd need complex ring buffer, 
                            // but reusing the Accumulator/OutputBuffer is the biggest win.
                            audioChannel.send(bytesRead)
                            // Note: Real "Zero Allocation" requires passing buffer ownership. 
                            // For simplicity & safety, we send a copy here but reuse the heavy processing buffers.
                            // But wait, Channel<ByteArray> was better.
                            // Let's revert to sending data, but use the shared logic in Consumer.
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                audioChannel.close()
            }
        }
        
        // Revert to ByteArray channel for safety, but optimized consumer
        val safeChannel = Channel<ByteArray>(Channel.UNLIMITED)
        
        // Restart drainer with safe logic
        drainerJob.cancel()
        val safeDrainer = launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (isActive) {
                        val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }
                        if (bytesRead == -1) break
                        if (bytesRead > 0) {
                            safeChannel.send(buffer.copyOfRange(0, bytesRead))
                        }
                    }
                }
            } catch (e: Exception) {} 
            finally { safeChannel.close() }
        }


        val writerJob = launch(Dispatchers.IO) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            try {
                engine.synthesizeToFile(text, params, writeFd, uuid)
            } catch (e: Exception) {
                closeQuietly(writeFd)
                currentWriteFd.set(null)
            } finally {
                closeQuietly(writeFd)
                currentWriteFd.set(null)
            }
        }

        val consumerJob = launch(Dispatchers.Default) {
            try {
                safeChannel.consumeEach { bytes ->
                    if (mIsStopped.get()) return@consumeEach

                    // Use Shared Output Buffer (Reuse Point 4)
                    val inputBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
                    inputBuffer.put(bytes)
                    inputBuffer.flip()
                    
                    sharedOutputBuffer.clear()

                    var processed = audioProcessor.process(inputBuffer, bytes.size, sharedOutputBuffer, sharedOutputBuffer.capacity())
                    
                    while (processed > 0 && !mIsStopped.get()) {
                        sharedOutputBuffer.get(sharedTempArray, 0, processed)
                        
                        // Accumulate using Shared Accumulator
                        var offset = 0
                        while (offset < processed) {
                            val remaining = processed - offset
                            val space = sharedAccumulator.remaining()
                            val toCopy = min(remaining, space)
                            
                            sharedAccumulator.put(sharedTempArray, offset, toCopy)
                            offset += toCopy

                            if (!sharedAccumulator.hasRemaining()) {
                                // Full chunk ready
                                sendToSystemSafely(sharedAccumulator.array(), sharedAccumulator.position(), callback)
                                sharedAccumulator.clear()
                            }
                        }
                        sharedOutputBuffer.clear()
                        processed = audioProcessor.process(inputBuffer, 0, sharedOutputBuffer, sharedOutputBuffer.capacity())
                    }
                }
                
                // Flush Accumulator at end of Chunk
                if (sharedAccumulator.position() > 0 && !mIsStopped.get()) {
                    sendToSystemSafely(sharedAccumulator.array(), sharedAccumulator.position(), callback)
                    sharedAccumulator.clear()
                }

            } catch (e: Exception) {
            }
        }

        try {
            consumerJob.join()
        } finally {
            safeDrainer.cancel()
            writerJob.cancel()
            closeQuietly(readFd)
            closeQuietly(writeFd)
            currentReadFd.set(null)
            currentWriteFd.set(null)
        }
    }

    // ★ POINT 3: SAFE BUFFERING LOOP ★
    // Vnspeak Style: Alignment & Safe Loop
    private fun sendToSystemSafely(buffer: ByteArray, length: Int, callback: SynthesisCallback) {
        if (mIsStopped.get() || length <= 0) return
        
        // 1. Frame Alignment (2 Bytes for 16-bit Mono)
        // မပြည့်တဲ့ Byte တွေကို ဖြတ်ထုတ်လိုက်ပါမယ် (Audio Noise ကာကွယ်ရန်)
        val alignedLength = (length / 2) * 2 
        
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        
        // 2. Safe Loop (Loop ပတ်ပြီး ခွဲပို့ခြင်း)
        while (offset < alignedLength) {
            val remaining = alignedLength - offset
            val chunk = min(remaining, maxBufferSize)
            
            val result = callback.audioAvailable(buffer, offset, chunk)
            if (result == TextToSpeech.ERROR) {
                mIsStopped.set(true)
                break
            }
            offset += chunk
        }
    }
    
    private fun closeQuietly(fd: ParcelFileDescriptor?) {
        try { fd?.close() } catch (e: Exception) { }
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
        mIsStopped.set(true)
        EngineScanner.stop()
        
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
    }
}

