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
import java.util.concurrent.atomic.AtomicLong
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
    private val currentGenerationId = AtomicLong(0)
    
    private val currentReadFd = AtomicReference<ParcelFileDescriptor?>(null)
    private val currentWriteFd = AtomicReference<ParcelFileDescriptor?>(null)
    private var currentActiveEngine: TextToSpeech? = null

    private val SYSTEM_OUTPUT_RATE = 24000
    private val BUFFER_SIZE = 65536 
    private val MIN_AUDIO_CHUNK_SIZE = 2048 

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
        currentGenerationId.incrementAndGet()
        
        closeQuietly(currentWriteFd.getAndSet(null))
        closeQuietly(currentReadFd.getAndSet(null))
        
        try { currentActiveEngine?.stop() } catch (e: Exception) { }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        mIsStopped.set(false)
        val myGenId = currentGenerationId.incrementAndGet()
        
        val fullText = request.charSequenceText.toString()
        
        var rateVal = request.speechRate
        var pitchVal = request.pitch
        if (rateVal < 10) rateVal = 10
        if (pitchVal < 10) pitchVal = 10

        val totalRate = rateVal / 100.0f
        val sysPitch = pitchVal / 100.0f

        // ★ DYNAMIC HYBRID CALCULATION ★
        // Engine ကို 2.5x အထိပေးမယ် (Data Flow ကောင်းအောင်)
        // 2.5x ထက်ကျော်ရင် Sonic နဲ့ ပေါင်းတင်မယ်
        val ENGINE_SAFE_MAX = 2.5f
        
        val engineRate: Float
        val sonicRate: Float

        if (totalRate <= ENGINE_SAFE_MAX) {
            // အရှိန်နည်းရင် Engine အကုန်လုပ် (အသံပိုကြည်တယ်)
            engineRate = totalRate
            sonicRate = 1.0f
        } else {
            // အရှိန်များရင် Engine ကို Max ပေး၊ ကျန်တာ Sonic လုပ်
            engineRate = ENGINE_SAFE_MAX
            sonicRate = totalRate / ENGINE_SAFE_MAX
        }

        // Vnspeak Chunking Logic
        val sentenceChunks = fullText.split(Regex("(?<=[\\n.?!])\\s+"))

        try {
            var targetPkg = englishPkgName 
            var targetEngine = englishEngine
            
            if (fullText.any { it.code in 0x1000..0x109F }) {
                 if (fullText.any { it.code in 0x1075..0x108F }) { 
                     targetEngine = shanEngine
                     targetPkg = shanPkgName
                 } else {
                     targetEngine = burmeseEngine
                     targetPkg = burmesePkgName
                 }
            }
            if (targetEngine == null) targetEngine = englishEngine

            var engineInputRate = prefs.getInt("RATE_$targetPkg", 0)
            if (engineInputRate == 0) engineInputRate = 24000

            callback.start(SYSTEM_OUTPUT_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)

            val audioProcessor = AudioProcessor(engineInputRate, 1)
            
            try {
                // Apply Dynamic Rates
                audioProcessor.setSpeed(sonicRate)
                audioProcessor.setPitch(sysPitch) // Sonic handles Pitch perfectly

                // Engine gets safe optimized speed
                targetEngine!!.setSpeechRate(engineRate)
                targetEngine!!.setPitch(1.0f) // Keep Engine Pitch neutral

                currentActiveEngine = targetEngine

                runBlocking {
                    for (sentence in sentenceChunks) {
                        if (currentGenerationId.get() != myGenId) break
                        if (sentence.isBlank()) continue
                        processSentence(targetEngine!!, sentence, callback, audioProcessor, myGenId)
                    }
                    
                    if (currentGenerationId.get() == myGenId) {
                        flushSonicBuffer(callback, audioProcessor, myGenId)
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

    private suspend fun processSentence(
        engine: TextToSpeech, 
        text: String, 
        callback: SynthesisCallback,
        audioProcessor: AudioProcessor,
        genId: Long
    ) = coroutineScope {

        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return@coroutineScope }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val uuid = UUID.randomUUID().toString()

        currentReadFd.set(readFd)
        currentWriteFd.set(writeFd)

        val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val drainerJob = launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (isActive && currentGenerationId.get() == genId) {
                        val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }
                        if (bytesRead == -1) break 
                        if (bytesRead > 0) {
                            audioChannel.send(buffer.copyOfRange(0, bytesRead))
                        }
                    }
                }
            } catch (e: Exception) { } 
            finally { audioChannel.close() }
        }

        val writerJob = launch(Dispatchers.IO) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            try {
                if (currentGenerationId.get() == genId) {
                    engine.synthesizeToFile(text, params, writeFd, uuid)
                }
            } catch (e: Exception) { } 
            finally {
                closeQuietly(writeFd)
                currentWriteFd.set(null)
            }
        }

        val consumerJob = launch(Dispatchers.Default) {
            val inputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val outputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE * 4).order(ByteOrder.LITTLE_ENDIAN)
            val tempArray = ByteArray(BUFFER_SIZE * 4)
            val accumulator = ByteBuffer.allocate(MIN_AUDIO_CHUNK_SIZE * 2)

            try {
                audioChannel.consumeEach { bytes ->
                    if (currentGenerationId.get() != genId) return@consumeEach

                    inputBuffer.clear()
                    inputBuffer.put(bytes)
                    inputBuffer.flip()

                    var processed = audioProcessor.process(inputBuffer, bytes.size, outputBuffer, outputBuffer.capacity())
                    
                    while (processed > 0 && currentGenerationId.get() == genId) {
                        outputBuffer.get(tempArray, 0, processed)
                        
                        var offset = 0
                        while (offset < processed) {
                            val remaining = processed - offset
                            val space = accumulator.remaining()
                            val toCopy = min(remaining, space)
                            
                            accumulator.put(tempArray, offset, toCopy)
                            offset += toCopy

                            if (!accumulator.hasRemaining()) {
                                sendToSystemSafely(accumulator.array(), accumulator.position(), callback, genId)
                                accumulator.clear()
                            }
                        }
                        outputBuffer.clear()
                        processed = audioProcessor.process(inputBuffer, 0, outputBuffer, outputBuffer.capacity())
                    }
                }
                
                if (accumulator.position() > 0 && currentGenerationId.get() == genId) {
                    sendToSystemSafely(accumulator.array(), accumulator.position(), callback, genId)
                    accumulator.clear()
                }

            } catch (e: Exception) { }
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

    private fun flushSonicBuffer(callback: SynthesisCallback, audioProcessor: AudioProcessor, genId: Long) {
        val outputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE * 4).order(ByteOrder.LITTLE_ENDIAN)
        val tempArray = ByteArray(BUFFER_SIZE * 4)
        
        audioProcessor.flushQueue()
        var processed = audioProcessor.process(null, 0, outputBuffer, outputBuffer.capacity())
        
        while (processed > 0 && currentGenerationId.get() == genId) {
            outputBuffer.get(tempArray, 0, processed)
            sendToSystemSafely(tempArray, processed, callback, genId)
            outputBuffer.clear()
            processed = audioProcessor.process(null, 0, outputBuffer, outputBuffer.capacity())
        }
    }

    private fun sendToSystemSafely(buffer: ByteArray, length: Int, callback: SynthesisCallback, genId: Long) {
        if (currentGenerationId.get() != genId || length <= 0) return
        
        val alignedLength = (length / 2) * 2 
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        
        while (offset < alignedLength) {
            if (currentGenerationId.get() != genId) break 

            val remaining = alignedLength - offset
            val chunk = min(remaining, maxBufferSize)
            
            val result = callback.audioAvailable(buffer, offset, chunk)
            if (result == TextToSpeech.ERROR) {
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

