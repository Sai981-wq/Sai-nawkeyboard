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
import android.speech.tts.Voice
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private val supportedVoices = VoiceConfig.supportedVoices

    private val engineMap = HashMap<String, TextToSpeech>()
    private lateinit var prefs: SharedPreferences
    private val audioProcessor = AudioProcessor()

    @Volatile private var mIsStopped = false
    
    private var currentLanguage: String = "eng"
    private var currentCountry: String = "USA"

    private val OUTPUT_HZ = 24000 
    private var currentInputRate = 0

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        
        supportedVoices.forEach { voice ->
            val pkg = prefs.getString("pref_${voice.locale.language}_pkg", voice.enginePkg) ?: voice.enginePkg
            initEngine(pkg, voice.locale) { tts ->
                engineMap[voice.name] = tts
            }
        }
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

    @Synchronized
    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText.toString()
        if (text.isNullOrEmpty() || callback == null) return

        mIsStopped = false 

        try {
            audioProcessor.destroy()
            currentInputRate = 0

            val rawChunks = TTSUtils.splitHelper(text)
            val originalParams = request?.params ?: Bundle()
            
            synchronized(callback) {
                callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
            }

            for (chunk in rawChunks) {
                if (mIsStopped) break

                val targetVoice = getVoiceForLang(chunk.lang)
                val engine = engineMap[targetVoice.name] ?: engineMap["en-US"] ?: continue
                
                val enginePkg = engine.engines.find { it.name == engine.defaultEngine }?.name ?: targetVoice.enginePkg
                val engineInputRate = determineInputRate(enginePkg)

                try {
                    val sysRate = (request?.speechRate ?: 100) / 100.0f
                    val sysPitch = (request?.pitch ?: 100) / 100.0f
                    
                    var userRatePref = prefs.getInt(targetVoice.rateKey, 50)
                    var userPitchPref = prefs.getInt(targetVoice.pitchKey, 50)
                    
                    if (userRatePref < 10) userRatePref = 10
                    if (userPitchPref < 10) userPitchPref = 10
                    
                    val userRateMulti = userRatePref / 50.0f
                    val userPitchMulti = userPitchPref / 50.0f
                    
                    val finalRate = sysRate * userRateMulti
                    val finalPitch = sysPitch * userPitchMulti
                    
                    try {
                        engine.setSpeechRate(finalRate)
                        engine.setPitch(finalPitch)
                    } catch (e: Exception) {
                        engine.setSpeechRate(1.0f)
                        engine.setPitch(1.0f)
                    }
                } catch (e: Exception) {}

                if (currentInputRate != engineInputRate) {
                    audioProcessor.init(engineInputRate, 1)
                    currentInputRate = engineInputRate
                } else {
                    audioProcessor.flush() 
                }
                
                val engineParams = Bundle(originalParams)
                engineParams.remove("rate")
                engineParams.remove("pitch")
                engineParams.remove(TextToSpeech.Engine.KEY_PARAM_RATE)
                engineParams.remove(TextToSpeech.Engine.KEY_PARAM_PITCH)
                
                val volCorrection = getVolumeCorrection(enginePkg)
                if (volCorrection != 1.0f) {
                    engineParams.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volCorrection)
                }
                
                val uuid = originalParams.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID) 
                           ?: UUID.randomUUID().toString()
                engineParams.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

                processAudioChunk(engine, engineParams, chunk.text, callback, uuid)
            }
            
            if (!mIsStopped) {
                callback.done()
            }
        } finally {
        }
    }

    private fun processAudioChunk(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        var pipe: Array<ParcelFileDescriptor>? = null
        try {
            pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]

            val writerThread = Thread {
                try {
                    engine.synthesizeToFile(text, params, writeFd, uuid)
                } catch (e: Exception) {
                } finally {
                    try { writeFd.close() } catch (e: Exception) {}
                }
            }
            writerThread.start()

            val inBuffer = ByteBuffer.allocateDirect(4096)
            val outBuffer = ByteArray(8192)
            
            FileInputStream(readFd.fileDescriptor).use { fis ->
                val channel = fis.channel
                while (!mIsStopped) {
                    inBuffer.clear()
                    val readBytes = channel.read(inBuffer)

                    if (readBytes == -1) {
                        audioProcessor.flush()
                        var flushLength: Int
                        do {
                            flushLength = audioProcessor.process(inBuffer, 0, outBuffer)
                            if (flushLength > 0) {
                                sendAudioToSystem(outBuffer, flushLength, callback)
                            }
                        } while (flushLength > 0 && !mIsStopped)
                        break
                    }

                    if (readBytes > 0) {
                        var processed = audioProcessor.process(inBuffer, readBytes, outBuffer)
                        if (processed > 0) {
                            sendAudioToSystem(outBuffer, processed, callback)
                        }
                        
                        do {
                            processed = audioProcessor.process(inBuffer, 0, outBuffer)
                            if (processed > 0) {
                                sendAudioToSystem(outBuffer, processed, callback)
                            }
                        } while (processed > 0 && !mIsStopped)
                    }
                }
            }
            
            try { writerThread.join(500) } catch (e: Exception) {}

        } catch (e: Exception) {
        } finally {
            try { pipe?.get(0)?.close() } catch (e: Exception) {}
            try { pipe?.get(1)?.close() } catch (e: Exception) {}
        }
    }

    private fun sendAudioToSystem(data: ByteArray, length: Int, callback: SynthesisCallback) {
        if (length <= 0) return
        val maxBufferSize = callback.maxBufferSize 
        var offset = 0
        synchronized(callback) {
            try {
                while (offset < length) {
                    val bytesToWrite = min(maxBufferSize, length - offset)
                    callback.audioAvailable(data, offset, bytesToWrite)
                    offset += bytesToWrite
                }
            } catch (e: Exception) {}
        }
    }

    override fun onStop() {
        mIsStopped = true 
        engineMap.values.forEach { try { it.stop() } catch (e: Exception) {} }
        audioProcessor.destroy()
    }
    
    override fun onGetVoices(): List<Voice> {
        return supportedVoices.map { it.toAndroidVoice() }
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val checkLang = lang?.lowercase(Locale.ROOT) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        return if (checkLang == "shn" || checkLang == "my" || checkLang == "en") {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result == TextToSpeech.LANG_COUNTRY_AVAILABLE || result == TextToSpeech.LANG_AVAILABLE) {
            currentLanguage = lang ?: "eng"
            currentCountry = country ?: ""
            return result
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf(currentLanguage, currentCountry, "")
    }

    private fun getVoiceForLang(lang: String): VoiceModel {
        return when (lang) {
            "SHAN" -> supportedVoices.find { it.name == "shn-MM" }!!
            "MYANMAR" -> supportedVoices.find { it.name == "my-MM" }!!
            else -> supportedVoices.find { it.name == "en-US" }!!
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

    private fun getVolumeCorrection(pkg: String): Float {
        val l = pkg.lowercase(Locale.ROOT)
        return when {
            l.contains("vocalizer") -> 0.85f; l.contains("eloquence") -> 0.6f; else -> 1.0f
        }
    }

    override fun onDestroy() {
        onStop()
        super.onDestroy()
    }
}

