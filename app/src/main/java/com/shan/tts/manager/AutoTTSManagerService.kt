package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Bundle
import android.os.SystemClock
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

    @Volatile private var mIsStopped = false
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
            
            AudioProcessor.initSonic(OUTPUT_HZ, 1)

        } catch (e: Exception) {
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
        return arrayOf("eng", "USA", "")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE
    }

    override fun onStop() {
        mIsStopped = true
        AudioProcessor.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        mIsStopped = false

        val text = request.charSequenceText.toString()
        
        // Settings Seekbar မှ Rate နှင့် Pitch ကို ရယူခြင်း
        // Android System က ပို့ပေးတဲ့ တန်ဖိုးကို တိုက်ရိုက်ယူပါမယ်
        val sysRate = request.speechRate // ပုံမှန် 100
        val sysPitch = request.pitch     // ပုံမှန် 100

        val chunks = TTSUtils.splitHelper(text)

        callback.start(OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

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
                currentInputRate = determineInputRate(engineData.pkgName)
                AudioProcessor.initSonic(currentInputRate, 1)
                
                // Seekbar Value များကို Sonic သို့ ပို့ခြင်း
                val sonicSpeed = sysRate / 100f 
                val sonicPitch = sysPitch / 100f
                AudioProcessor.setConfig(sonicSpeed, sonicPitch)
                
                // Volume Correction
                val volume = getVolumeCorrection(engineData.pkgName)
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)

                // Instant Play Logic ကို သုံးပါမယ်
                processAudioChunkInstant(targetEngine, params, chunk.text, callback, UUID.randomUUID().toString())
            }
        }
        
        callback.done()
    }

    // ၂ စက္ကန့်စောင့်စရာမလိုဘဲ ရေးနေတုန်း ဖတ်မယ့် Function အသစ်
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

            // Engine ကို စာစရေးခိုင်းပါမယ်
            engine.synthesizeToFile(text, params, destFile, uuid)

            // ဖိုင်ရေးနေတုန်းမှာပဲ တပြိုင်နက်တည်း လိုက်ဖတ်ပါမယ် (Streaming)
            var offset: Long = 0
            val headerSkipped = AtomicBoolean(false)
            val inBuffer = ByteBuffer.allocateDirect(4096)
            val outBuffer = ByteArray(8192)

            while (!mIsStopped) {
                val fileLength = destFile.length()
                
                // ဖိုင်ထဲမှာ Data အသစ်ရောက်လာပြီလား စစ်မယ်
                if (fileLength > offset) {
                    FileInputStream(destFile).use { fis ->
                        fis.channel.position(offset) // ဖတ်ပြီးသားနေရာကို ကျော်မယ်
                        val fc = fis.channel
                        
                        // Header Skip Logic (44 Bytes) - တကြိမ်ပဲ လုပ်မယ်
                        if (!headerSkipped.get()) {
                            if (fileLength >= 44) {
                                offset += 44 // Header ကို ကျော်လိုက်ပြီ
                                fc.position(offset)
                                headerSkipped.set(true)
                            } else {
                                // Header မပြည့်သေးရင် ခဏစောင့်မယ်
                                Thread.sleep(10)
                                return@use
                            }
                        }

                        // Data ဖတ်ပြီး Sonic ထဲထည့်မယ်
                        inBuffer.clear()
                        val bytesRead = fc.read(inBuffer)
                        if (bytesRead > 0) {
                            offset += bytesRead
                            var processed = AudioProcessor.processAudio(inBuffer, bytesRead, outBuffer)
                            if (processed > 0) sendAudioToSystem(outBuffer, processed, callback)
                            
                            // Sonic ထဲ ကျန်နေတာတွေ အကုန်ညှစ်ထုတ်မယ်
                            do {
                                processed = AudioProcessor.processAudio(inBuffer, 0, outBuffer)
                                if (processed > 0) sendAudioToSystem(outBuffer, processed, callback)
                            } while (processed > 0 && !mIsStopped)
                        }
                    }
                } else {
                    // Data အသစ်မရောက်သေးရင် Engine ပြီးမပြီး စစ်မယ်
                    if (isDone.get()) {
                        // Engine ပြီးသွားပြီ၊ Data လည်းကုန်သွားပြီဆိုရင် Loop ထွက်မယ်
                        break
                    }
                    // Engine မပြီးသေးရင် Data အသစ်ရောက်လာအောင် ခဏစောင့်မယ် (CPU မတက်အောင်)
                    SystemClock.sleep(20)
                }
            }
            // အဆုံးသတ် Flush
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

