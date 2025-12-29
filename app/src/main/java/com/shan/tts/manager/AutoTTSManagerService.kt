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
    // Buffer Size ကို 64KB အထိ တိုးလိုက်ပါတယ် (Vnspeak လိုမျိုး အများကြီးဖတ်နိုင်အောင်)
    private val BUFFER_SIZE = 65536 
    private val MIN_AUDIO_CHUNK_SIZE = 4096

    // Shared Buffers (Memory Reusability)
    private lateinit var sharedOutputBuffer: ByteBuffer
    private lateinit var sharedAccumulator: ByteBuffer
    private lateinit var sharedTempArray: ByteArray

    override fun onCreate() {
        super.onCreate()
        try {
            // Buffer Allocation (Once)
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
        // Stop လုပ်တာနဲ့ Pipe တွေကို ချက်ချင်းပိတ်မယ် (Deadlock ဖြေရှင်းရန်)
        closeQuietly(currentWriteFd.getAndSet(null))
        closeQuietly(currentReadFd.getAndSet(null))
        try { currentActiveEngine?.stop() } catch (e: Exception) { }
    }

    // ★ REMOVED @Synchronized to prevent Deadlock with Jieshuo's fast skipping
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
            // ★ STRICT CLEANUP: Reset shared buffers before starting new sentence
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
        sharedOutputBuffer.clear()
        
        audioProcessor.flushQueue()
        var processed = audioProcessor.process(null, 0, sharedOutputBuffer, sharedOutputBuffer.capacity())
        
        while (processed > 0 && !mIsStopped.get()) {
            sharedOutputBuffer.get(sharedTempArray, 0, processed)
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

        val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val drainerJob = launch(Dispatchers.IO) {
            // Local buffer for reading from pipe (Thread safe)
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (isActive) {
                        val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }
                        if (bytesRead == -1) break 
                        if (bytesRead > 0) {
                            audioChannel.send(buffer.copyOfRange(0, bytesRead))
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                audioChannel.close()
            }
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
                audioChannel.consumeEach { bytes ->
                    if (mIsStopped.get()) return@consumeEach

                    val inputBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
                    inputBuffer.put(bytes)
                    inputBuffer.flip()
                    
                    sharedOutputBuffer.clear()

                    var processed = audioProcessor.process(inputBuffer, bytes.size, sharedOutputBuffer, sharedOutputBuffer.capacity())
                    
                    while (processed > 0 && !mIsStopped.get()) {
                        sharedOutputBuffer.get(sharedTempArray, 0, processed)
                        
                        var offset = 0
                        while (offset < processed) {
                            val remaining = processed - offset
                            val space = sharedAccumulator.remaining()
                            val toCopy = min(remaining, space)
                            
                            sharedAccumulator.put(sharedTempArray, offset, toCopy)
                            offset += toCopy

                            if (!sharedAccumulator.hasRemaining()) {
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
            drainerJob.cancel()
            writerJob.cancel()
            closeQuietly(readFd)
            closeQuietly(writeFd)
            currentReadFd.set(null)
            currentWriteFd.set(null)
        }
    }

    private fun sendToSystemSafely(buffer: ByteArray, length: Int, callback: SynthesisCallback) {
        if (mIsStopped.get() || length <= 0) return
        
        // Frame Alignment (2 Bytes)
        val alignedLength = (length / 2) * 2 
        
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        
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

