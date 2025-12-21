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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""

    private lateinit var prefs: SharedPreferences

    @Volatile private var mIsStopped = false
    
    private var currentLanguage: String = "eng"
    private var currentCountry: String = "USA"

    private val OUTPUT_HZ = 24000 
    private var currentInputRate = 0

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName, Locale("shn", "MM")) { shanEngine = it }

            burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName, Locale.US) { englishEngine = it }
            
            // Initial Init with default
            AudioProcessor.initSonic(OUTPUT_HZ, 1)

        } catch (e: Exception) {
            // Log error if needed
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

    override fun onGetLanguage(): Array<String> {
        val lang = if (currentLanguage.isNotEmpty()) currentLanguage else "eng"
        val country = if (currentCountry.isNotEmpty()) currentCountry else "USA"
        return arrayOf(lang, country, "")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val checkLang = lang?.lowercase(Locale.ROOT) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        return if (checkLang == "shn" || checkLang == "my" || checkLang == "mya" || checkLang == "en" || checkLang == "eng") {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        currentLanguage = lang ?: "eng"
        currentCountry = country ?: "USA"
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onStop() {
        mIsStopped = true
        AudioProcessor.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        mIsStopped = false

        val text = request.charSequenceText.toString()
        val lang = request.language
        val country = request.country
        
        currentLanguage = lang
        currentCountry = country

        val engineData = getEngineDataForLang(lang)
        val targetEngine = engineData.engine ?: englishEngine
        
        if (targetEngine == null) {
            callback.error()
            return
        }

        currentInputRate = determineInputRate(engineData.pkgName)
        AudioProcessor.initSonic(currentInputRate, 1)
        
        val speed = getRateValue(engineData.rateKey)
        val pitch = getPitchValue(engineData.pitchKey)
        val volume = getVolumeCorrection(engineData.pkgName)

        val sonicSpeed = speed / 100f
        val sonicPitch = pitch / 100f

        AudioProcessor.setConfig(sonicSpeed, sonicPitch)
        
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        
        callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
        
        processAudioChunk(targetEngine, params, text, callback, UUID.randomUUID().toString())
        
        callback.done()
    }

    private fun processAudioChunk(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        var tempFile: File? = null
        try {
            tempFile = File.createTempFile("tts_audio", ".wav", cacheDir)
            val destFile = tempFile
            val latch = CountDownLatch(1)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { latch.countDown() }
                override fun onError(utteranceId: String?) { latch.countDown() }
            })

            engine.synthesizeToFile(text, params, destFile.absolutePath, uuid)
            latch.await(4000, TimeUnit.MILLISECONDS)

            if (destFile.length() > 44) {
                val inBuffer: ByteBuffer = ByteBuffer.allocateDirect(4096)
                val outBuffer: ByteArray = ByteArray(8192)

                FileInputStream(destFile).use { fis ->
                    val channel = fis.channel
                    fis.skip(44) 

                    while (!mIsStopped) {
                        inBuffer.clear()
                        val readBytes: Int = channel.read(inBuffer)

                        if (readBytes == -1) {
                            AudioProcessor.flush()
                            var flushLength: Int
                            do {
                                // 0 ကို Int အနေနဲ့ သေချာပေးပို့ခြင်း
                                flushLength = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                                if (flushLength > 0) {
                                    sendAudioToSystem(outBuffer, flushLength, callback)
                                }
                            } while (flushLength > 0 && !mIsStopped)
                            break
                        }

                        if (readBytes > 0) {
                            // readBytes ကို Int အနေနဲ့ သေချာပေးပို့ခြင်း
                            var processed: Int = AudioProcessor.processAudio(inBuffer, readBytes, outBuffer)
                            if (processed > 0) {
                                sendAudioToSystem(outBuffer, processed, callback)
                            }
                            do {
                                processed = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                                if (processed > 0) {
                                    sendAudioToSystem(outBuffer, processed, callback)
                                }
                            } while (processed > 0 && !mIsStopped)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Log error
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
            "SHAN", "shn" -> EngineData(if (shanEngine != null) shanEngine else englishEngine, shanPkgName, "rate_shan", "pitch_shan")
            "MYANMAR", "my", "mya", "bur" -> EngineData(if (burmeseEngine != null) burmeseEngine else englishEngine, burmesePkgName, "rate_burmese", "pitch_burmese")
            else -> EngineData(englishEngine, englishPkgName, "rate_english", "pitch_english")
        }
    }

    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return if (l.contains("espeak") || l.contains("shan")) 1.0f else 0.8f
    }

    private fun getRateValue(key: String): Int {
        return prefs.getInt(key, 100)
    }

    private fun getPitchValue(key: String): Int {
        return prefs.getInt(key, 100)
    }

    override fun onDestroy() {
        super.onDestroy()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        AudioProcessor.stop()
    }
}

