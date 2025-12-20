package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
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

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""

    private lateinit var prefs: SharedPreferences

    @Volatile private var mIsStopped = false
    
    @Volatile private var currentReadFd: ParcelFileDescriptor? = null
    @Volatile private var currentWriteFd: ParcelFileDescriptor? = null

    private val OUTPUT_HZ = 24000 
    private var currentInputRate = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoTTS:ReadingLock")
            wakeLock?.setReferenceCounted(false)
        } catch (e: Exception) {}

        try {
            prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            shanPkgName = prefs.getString("pref_shan_pkg", "com.espeak.ng") ?: "com.espeak.ng"
            initEngine(shanPkgName, Locale("shn")) { shanEngine = it }
            burmesePkgName = prefs.getString("pref_burmese_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }
            englishPkgName = prefs.getString("pref_english_pkg", "com.google.android.tts") ?: "com.google.android.tts"
            initEngine(englishPkgName, Locale.US) { englishEngine = it }
        } catch (e: Exception) { }
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
            if (wakeLock?.isHeld == false) wakeLock?.acquire(60 * 60 * 1000L)
        } catch (e: Exception) {}

        try {
            resetSonicStream()

            val rawChunks = TTSUtils.splitHelper(text)
            
            synchronized(callback) {
                callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
            }

            for (chunk in rawChunks) {
                if (mIsStopped) break

                val engineData = getEngineDataForLang(chunk.lang)
                val engine = engineData.engine ?: continue
                val engineInputRate = determineInputRate(engineData.pkgName)

                try {
                    val sysRate = (request?.speechRate ?: 100) / 100.0f
                    val sysPitch = (request?.pitch ?: 100) / 100.0f
                    
                    val userRatePref = prefs.getInt(engineData.rateKey, 50)
                    val userPitchPref = prefs.getInt(engineData.pitchKey, 50)
                    
                    val userRateMulti = userRatePref / 50.0f
                    val userPitchMulti = userPitchPref / 50.0f
                    
                    engine.setSpeechRate(sysRate * userRateMulti)
                    engine.setPitch(sysPitch * userPitchMulti)
                } catch (e: Exception) {}

                if (currentInputRate != engineInputRate) {
                    AudioProcessor.initSonic(engineInputRate, 1)
                    currentInputRate = engineInputRate
                } else {
                    AudioProcessor.flush() 
                }
                
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, getVolumeCorrection(engineData.pkgName))
                val uuid = UUID.randomUUID().toString()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uuid)

                processAudioChunk(engine, params, chunk.text, callback, uuid)
            }
            
            if (!mIsStopped) {
                callback.done()
            }
        } finally {
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        }
    }

    private fun processAudioChunk(engine: TextToSpeech, params: Bundle, text: String, callback: SynthesisCallback, uuid: String) {
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            currentReadFd = pipe[0]
            currentWriteFd = pipe[1]

            val writerThread = Thread {
                try {
                    engine.synthesizeToFile(text, params, currentWriteFd!!, uuid)
                } catch (e: Exception) {
                } finally {
                    try { currentWriteFd?.close() } catch (e: Exception) {}
                }
            }
            writerThread.start()

            val inBuffer = ByteBuffer.allocateDirect(4096)
            val outBuffer = ByteArray(8192)
            var fis: FileInputStream? = null
            
            try {
                fis = FileInputStream(currentReadFd!!.fileDescriptor)
                val channel = fis.channel
                
                while (!mIsStopped) {
                    inBuffer.clear()
                    val read = channel.read(inBuffer)
                    
                    if (read == -1) {
                        AudioProcessor.flush()
                        var flushBytes = 1
                        while (flushBytes > 0 && !mIsStopped) {
                            flushBytes = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                            if (flushBytes > 0) sendAudioToSystem(outBuffer, flushBytes, callback)
                        }
                        break
                    }
                    
                    if (read > 0) {
                        var bytesProcessed = AudioProcessor.processAudio(inBuffer, read, outBuffer)
                        if (bytesProcessed > 0) sendAudioToSystem(outBuffer, bytesProcessed, callback)
                        
                        while (bytesProcessed > 0 && !mIsStopped) {
                            bytesProcessed = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                            if (bytesProcessed > 0) sendAudioToSystem(outBuffer, bytesProcessed, callback)
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { fis?.close() } catch (e: Exception) {}
            }

            try { writerThread.join() } catch (e: Exception) {}

        } catch (e: Exception) {
        } finally {
            try { currentWriteFd?.close() } catch (e: Exception) {}
            try { currentReadFd?.close() } catch (e: Exception) {}
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

    private fun resetSonicStream() {
        try {
            AudioProcessor.stop()
            currentInputRate = 0
        } catch (e: Exception) {}
    }

    override fun onStop() {
        mIsStopped = true 
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        try { currentWriteFd?.close() } catch (e: Exception) {}
        try { currentReadFd?.close() } catch (e: Exception) {}
        try {
            shanEngine?.stop()
            burmeseEngine?.stop()
            englishEngine?.stop()
        } catch (e: Exception) {}
        AudioProcessor.stop()
    }
    
    override fun onGetVoices(): List<Voice> {
        return listOf(
            Voice("shn-MM", Locale("shn", "MM"), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, setOf()),
            Voice("my-MM", Locale("my", "MM"), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, setOf()),
            Voice("en-US", Locale.US, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, setOf())
        )
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
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("shn", "MM", "")
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

    override fun onDestroy() {
        onStop()
        super.onDestroy()
    }
}

