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
    private val READ_BUFFER_SIZE = 16384 
    private val MIN_AUDIO_CHUNK_SIZE = 4096 // Increased for stability

    override fun onCreate() {
        super.onCreate()
        try {
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

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        mIsStopped.set(false)
        val text = request.charSequenceText.toString()
        
        // Safety Clamps
        var rateVal = request.speechRate
        var pitchVal = request.pitch
        if (rateVal < 10) rateVal = 10
        if (pitchVal < 10) pitchVal = 10

        val sysRate = rateVal / 100.0f
        val sysPitch = pitchVal / 100.0f

        val chunks = TTSUtils.splitHelper(text)
        
        // ★ MAJOR FIX: Determine Engine & Rate BEFORE the loop ★
        // We assume the whole sentence uses the primary language or majority language
        // to prevent switching engines mid-sentence which causes Sonic reset issues.
        var targetPkg = englishPkgName 
        var targetEngine = englishEngine

        // Simple logic: If text has Shan/Burmese, pick that engine.
        // For mixed text, sticking to one engine per request is safer for Sonic stability.
        if (text.any { it.code in 0x1000..0x109F }) {
             // Logic to pick Burmese or Shan based on first detection
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

        // Get Rate Once
        var engineInputRate = prefs.getInt("RATE_$targetPkg", 0)
        if (engineInputRate == 0) engineInputRate = 24000

        try {
            // ★ SINGLE SONIC INSTANCE FOR WHOLE REQUEST ★
            // Chunk တွေအများကြီးအတွက် Sonic တစ်ခုတည်းသုံးမယ် (Speed မရွေ့အောင်)
            val audioProcessor = AudioProcessor(engineInputRate, 1)
            
            try {
                callback.start(SYSTEM_OUTPUT_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)

                audioProcessor.setSpeed(sysRate)
                audioProcessor.setPitch(1.0f) // Sonic handles pitch shift from 1.0 base
                
                targetEngine!!.setSpeechRate(1.0f) // Engine stays constant
                targetEngine!!.setPitch(sysPitch) // Let engine handle pitch if possible, or 1.0f if preferred

                currentActiveEngine = targetEngine

                runBlocking {
                    for (chunk in chunks) {
                        if (mIsStopped.get()) break
                        
                        // We use the SAME engine and SAME processor for stability
                        processFully(targetEngine!!, chunk.text, callback, audioProcessor)
                    }
                    
                    // Final Flush at the VERY END of the sentence
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

    // Extracted Flush Logic
    private fun flushSonicBuffer(callback: SynthesisCallback, audioProcessor: AudioProcessor) {
        val outputBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE * 4).order(ByteOrder.LITTLE_ENDIAN)
        val tempArray = ByteArray(READ_BUFFER_SIZE * 4)
        
        audioProcessor.flushQueue()
        var processed = audioProcessor.process(null, 0, outputBuffer, outputBuffer.capacity())
        
        while (processed > 0 && !mIsStopped.get()) {
            outputBuffer.get(tempArray, 0, processed)
            sendToSystem(tempArray, processed, callback)
            outputBuffer.clear()
            processed = audioProcessor.process(null, 0, outputBuffer, outputBuffer.capacity())
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
            val buffer = ByteArray(READ_BUFFER_SIZE)
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
            // Important: Force Engine Volume/Pan to defaults
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
            
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
            val inputBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val outputBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE * 4).order(ByteOrder.LITTLE_ENDIAN)
            val tempArray = ByteArray(READ_BUFFER_SIZE * 4)

            // Large Accumulator for stability
            val accumulator = ByteBuffer.allocate(MIN_AUDIO_CHUNK_SIZE * 2)
            
            try {
                audioChannel.consumeEach { bytes ->
                    if (mIsStopped.get()) return@consumeEach

                    inputBuffer.clear()
                    inputBuffer.put(bytes)
                    inputBuffer.flip()

                    var processed = audioProcessor.process(inputBuffer, bytes.size, outputBuffer, outputBuffer.capacity())
                    
                    while (processed > 0 && !mIsStopped.get()) {
                        outputBuffer.get(tempArray, 0, processed)
                        
                        var offset = 0
                        while (offset < processed) {
                            val remaining = processed - offset
                            val space = accumulator.remaining()
                            val toCopy = min(remaining, space)
                            
                            accumulator.put(tempArray, offset, toCopy)
                            offset += toCopy

                            // Only send when we have a GOOD CHUNK of audio
                            if (!accumulator.hasRemaining()) {
                                sendToSystem(accumulator.array(), accumulator.position(), callback)
                                accumulator.clear()
                            }
                        }
                        outputBuffer.clear()
                        processed = audioProcessor.process(inputBuffer, 0, outputBuffer, outputBuffer.capacity())
                    }
                }
                
                // Note: We DO NOT flush Sonic here anymore. 
                // We flush only after ALL chunks are done in onSynthesizeText.
                // This keeps the stream continuous between words.
                
                // But we MUST flush the Accumulator because the chunk ended
                if (accumulator.position() > 0 && !mIsStopped.get()) {
                    sendToSystem(accumulator.array(), accumulator.position(), callback)
                    accumulator.clear()
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

    private fun sendToSystem(buffer: ByteArray, length: Int, callback: SynthesisCallback) {
        if (mIsStopped.get() || length <= 0) return
        
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        
        while (offset < length) {
            val chunk = min(length - offset, maxBufferSize)
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

