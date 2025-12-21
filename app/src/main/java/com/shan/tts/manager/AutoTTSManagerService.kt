package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import java.io.File
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

    @Volatile private var mIsStopped = false
    private val FIXED_OUTPUT_HZ = 24000 

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
            
            shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName, Locale("shn", "MM")) { shanEngine = it }

            burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName, Locale.US) { englishEngine = it }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initEngine(pkg: String, locale: Locale, onReady: (TextToSpeech) -> Unit) {
        if (pkg.isEmpty()) return
        var tts: TextToSpeech? = null
        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = locale
                onReady(tts!!)
            }
        }, pkg)
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE

    override fun onStop() {
        mIsStopped = true
        AudioProcessor.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        mIsStopped = false

        val text = request.charSequenceText.toString()
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
                
                AudioProcessor.initSonic(detectedRate, 1)
                
                val sonicSpeed = sysRate / 100f 
                val sonicPitch = sysPitch / 100f
                AudioProcessor.setConfig(sonicSpeed, sonicPitch)
                
                val volume = getVolumeCorrection(engineData.pkgName)
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)

                processAudioChunkInstant(targetEngine, params, chunk.text, callback, UUID.randomUUID().toString())
            }
        }
        
        callback.done()
    }

    private fun processAudioChunkInstant(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        var tempFile: File? = null
        try {
            tempFile = File.createTempFile("tts_stream", ".wav", cacheDir)
            val destFile = tempFile
            val isDone = AtomicBoolean(false)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { isDone.set(true) }
                override fun onError(utteranceId: String?) { isDone.set(true) }
            })

            engine.synthesizeToFile(text, params, destFile, uuid)

            var offset: Long = 0
            val inBuffer = ByteBuffer.allocateDirect(4096)
            val outBufferDirect = ByteBuffer.allocateDirect(8192) 
            val outBufferArray = ByteArray(8192)

            while (!mIsStopped) {
                val fileLength = destFile.length()
                
                if (fileLength > offset) {
                    FileInputStream(destFile).use { fis ->
                        fis.channel.position(offset)
                        val fc = fis.channel
                        
                        inBuffer.clear()
                        val bytesRead = fc.read(inBuffer)
                        
                        if (bytesRead > 0) {
                            offset += bytesRead
                            
                            outBufferDirect.clear()
                            var processed = AudioProcessor.processAudio(inBuffer, bytesRead, outBufferDirect, outBufferDirect.capacity())
                            
                            if (processed > 0) {
                                outBufferDirect.get(outBufferArray, 0, processed)
                                sendAudioToSystem(outBufferArray, processed, callback)
                            }
                            
                            do {
                                outBufferDirect.clear()
                                processed = AudioProcessor.processAudio(inBuffer, 0, outBufferDirect, outBufferDirect.capacity())
                                if (processed > 0) {
                                    outBufferDirect.get(outBufferArray, 0, processed)
                                    sendAudioToSystem(outBufferArray, processed, callback)
                                }
                            } while (processed > 0 && !mIsStopped)
                        }
                    }
                } else {
                    if (isDone.get()) break
                    Thread.sleep(1)
                }
            }
            AudioProcessor.flush()
            
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { tempFile?.delete() } catch (e: Exception) {}
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
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        AudioProcessor.stop()
    }
}

