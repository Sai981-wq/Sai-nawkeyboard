package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""

    private lateinit var prefs: SharedPreferences
    private lateinit var configPrefs: SharedPreferences

    private var lastConfiguredRate: Int = -1

    @Volatile private var mIsStopped = false
    private val FIXED_OUTPUT_HZ = 24000 

    private val inBufferLocal = object : ThreadLocal<ByteBuffer>() {
        override fun initialValue(): ByteBuffer = ByteBuffer.allocateDirect(4096)
    }

    private val outBufferLocal = object : ThreadLocal<ByteBuffer>() {
        override fun initialValue(): ByteBuffer = ByteBuffer.allocateDirect(8192)
    }

    private val outBufferArrayLocal = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray = ByteArray(8192)
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service Created: AutoTTSManagerService started.")
        try {
            prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            
            shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName, Locale("shn", "MM")) { 
                shanEngine = it 
                AppLogger.log("Shan Engine Ready: $shanPkgName")
            }

            burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName, Locale("my", "MM")) { 
                burmeseEngine = it 
                AppLogger.log("Burmese Engine Ready: $burmesePkgName")
            }

            englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName, Locale.US) { 
                englishEngine = it 
                AppLogger.log("English Engine Ready: $englishPkgName")
            }
            
        } catch (e: Exception) {
            AppLogger.error("Error in onCreate", e)
        }
    }

    private fun initEngine(pkg: String, locale: Locale, onReady: (TextToSpeech) -> Unit) {
        if (pkg.isEmpty()) return
        var tts: TextToSpeech? = null
        try {
            tts = TextToSpeech(this, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(locale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        AppLogger.log("Warning: Language not supported for $pkg")
                    }
                    onReady(tts!!)
                } else {
                    AppLogger.error("Failed to init engine: $pkg (Status: $status)")
                }
            }, pkg)
        } catch (e: Exception) {
            AppLogger.error("Crash during initEngine: $pkg", e)
        }
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE

    override fun onStop() {
        AppLogger.log("Service Stopped: Request Cancelled")
        mIsStopped = true
        AudioProcessor.stop()
        lastConfiguredRate = -1 
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        mIsStopped = false

        val text = request.charSequenceText.toString()
        // AppLogger.log("Request Received: [${text.take(20)}...] Rate=${request.speechRate}")

        val sysRate = request.speechRate
        val sysPitch = request.pitch
        val chunks = TTSUtils.splitHelper(text)

        callback.start(FIXED_OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

        for (chunk in chunks) {
            if (mIsStopped) break

            val langCode = when(chunk.lang) {
                "SHAN" -> "shn"
                "MYANMAR" -> "my"
                else -> "eng"
            }

            val engineData = getEngineDataForLang(langCode)
            val targetEngine = engineData.engine ?: englishEngine
            
            if (targetEngine != null) {
                val detectedRate = configPrefs.getInt("RATE_${engineData.pkgName}", 22050)
                // AppLogger.log("Processing Chunk: Lang=$langCode, Engine=${engineData.pkgName}, Hz=$detectedRate")
                
                if (detectedRate != lastConfiguredRate) {
                    AppLogger.log("Init Sonic: New Hz=$detectedRate")
                    AudioProcessor.initSonic(detectedRate, 1)
                    lastConfiguredRate = detectedRate
                }
                
                val rateFloat = sysRate / 100f
                val pitchFloat = sysPitch / 100f
                targetEngine.setSpeechRate(rateFloat)
                targetEngine.setPitch(pitchFloat)
                
                val volume = getVolumeCorrection(engineData.pkgName)
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)

                processAudioChunkInstant(targetEngine, params, chunk.text, callback, UUID.randomUUID().toString())
            } else {
                AppLogger.error("Target Engine is NULL for lang: $langCode")
            }
        }
        
        callback.done()
    }

    private fun processAudioChunkInstant(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        try {
            val isDone = AtomicBoolean(false)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // AppLogger.log("Engine Started Synthesis...")
                }
                override fun onDone(utteranceId: String?) { 
                    isDone.set(true) 
                }
                override fun onError(utteranceId: String?) { 
                    isDone.set(true) 
                    AppLogger.error("Engine Error Callback Triggered")
                }
            })

            engine.synthesizeToFile(text, params, writeFd, uuid)
            writeFd.close()

            val localInBuffer = inBufferLocal.get()!!
            val localOutBuffer = outBufferLocal.get()!!
            val localByteArray = outBufferArrayLocal.get()!!

            ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                val fc = fis.channel
                var totalBytesProcessed = 0
                while (!mIsStopped) {
                    
                    localInBuffer.clear()
                    val bytesRead = fc.read(localInBuffer)
                    
                    if (bytesRead == -1) break

                    if (bytesRead > 0) {
                        totalBytesProcessed += bytesRead
                        localInBuffer.flip()

                        localOutBuffer.clear()
                        var processed = AudioProcessor.processAudio(localInBuffer, bytesRead, localOutBuffer, localOutBuffer.capacity())
                        
                        if (processed > 0) {
                            localOutBuffer.get(localByteArray, 0, processed)
                            sendAudioToSystem(localByteArray, processed, callback)
                        }
                        
                        while (processed > 0 && !mIsStopped) {
                            localOutBuffer.clear()
                            processed = AudioProcessor.processAudio(localInBuffer, 0, localOutBuffer, localOutBuffer.capacity())
                            if (processed > 0) {
                                localOutBuffer.get(localByteArray, 0, processed)
                                sendAudioToSystem(localByteArray, processed, callback)
                            }
                        }
                    }
                }
                // AppLogger.log("Chunk Finished. Total Raw Bytes Processed: $totalBytesProcessed")
            }
            AudioProcessor.flush()
            
        } catch (e: Exception) {
            AppLogger.error("Exception in Pipe Processing", e)
        } finally {
            try { readFd.close() } catch (e: Exception) {}
            engine.setOnUtteranceProgressListener(null)
        }
    }

    private fun sendAudioToSystem(buffer: ByteArray, length: Int, callback: SynthesisCallback) {
        if (mIsStopped) return
        val maxBufferSize = callback.maxBufferSize
        var offset = 0
        while (offset < length && !mIsStopped) {
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

    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return if (l.contains("espeak") || l.contains("shan")) 1.0f else 0.8f
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.log("Service Destroyed.")
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        AudioProcessor.stop()
    }
}

