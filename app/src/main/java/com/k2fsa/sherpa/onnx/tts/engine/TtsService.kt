package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log

class TtsService : TextToSpeechService() {
    override fun onCreate() {
        super.onCreate()
        onLoadLanguage(TtsEngine.lang, "", "")
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(_lang: String?, _country: String?, _variant: String?): Int {
        val reqLang = _lang ?: ""
        val availableLangs = TtsEngine.getAvailableLanguages(this)

        if (availableLangs.contains(reqLang)) {
            return TextToSpeech.LANG_AVAILABLE
        }

        for (avail in availableLangs) {
            if (reqLang.startsWith(avail) || avail.startsWith(reqLang)) {
                 return TextToSpeech.LANG_AVAILABLE
            }
        }

        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf(TtsEngine.lang ?: "eng", "", "")
    }

    override fun onLoadLanguage(_lang: String?, _country: String?, _variant: String?): Int {
        var lang = _lang ?: ""
        val preferenceHelper = PreferenceHelper(this)
        
        if (preferenceHelper.getCurrentLanguage().equals("")){
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return TextToSpeech.LANG_MISSING_DATA
        } 
        
        val availableLangs = TtsEngine.getAvailableLanguages(this)
        var targetLang = ""
        
        if (availableLangs.contains(lang)) {
            targetLang = lang
        } else {
             for (avail in availableLangs) {
                if (lang.startsWith(avail) || avail.startsWith(lang)) {
                     targetLang = avail
                     break
                }
            }
        }

        if (targetLang.isNotEmpty()) {
            TtsEngine.createTts(application, targetLang)
            return TextToSpeech.LANG_AVAILABLE
        } else {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onStop() {}

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (TtsEngine.tts == null || request == null || callback == null) {
            return
        }
        
        val text = request.charSequenceText.toString()

        if (text.isBlank()) {
            callback.done()
            return
        }

        val language = request.language
        val country = request.country
        val variant = request.variant
        
        val ret = onIsLanguageAvailable(language, country, variant)
        if (ret == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.error()
            return
        }

        var pitch = 100f
        val preferenceHelper = PreferenceHelper(this)
        if (preferenceHelper.applySystemSpeed()){
            pitch = request.pitch * 1.0f
            if (pitch != 0f) {
                TtsEngine.speed.value = request.speechRate / pitch
            }
        }

        val tts = TtsEngine.tts!!

        callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1)

        val ttsCallback: (FloatArray) -> Int = fun(floatSamples): Int {
            val samples: ByteArray

            if (pitch != 100f){ 
                val speedFactor = pitch / 100f
                val newSampleCount = (floatSamples.size / speedFactor).toInt()
                val newSamples = FloatArray(newSampleCount)

                for (i in 0 until newSampleCount) {
                     val index = (i * speedFactor).toInt()
                     if (index < floatSamples.size) {
                        newSamples[i] = floatSamples[index] * TtsEngine.volume.value
                     }
                }
                samples = floatArrayToByteArray(newSamples)
            } else {
                for (i in floatSamples.indices) {
                    floatSamples[i] *= TtsEngine.volume.value
                }
                samples = floatArrayToByteArray(floatSamples)
            }

            val maxBufferSize: Int = callback.maxBufferSize
            var offset = 0
            while (offset < samples.size) {
                val bytesToWrite = Math.min(maxBufferSize, samples.size - offset)
                callback.audioAvailable(samples, offset, bytesToWrite)
                offset += bytesToWrite
            }
            return 1
        }

        try {
            tts.generateWithCallback(
                text = text,
                sid = TtsEngine.speakerId.value,
                speed = TtsEngine.speed.value,
                callback = ttsCallback,
            )
        } catch (e: Exception) {
            callback.error()
        }

        callback.done()
    }

    private fun floatArrayToByteArray(audio: FloatArray): ByteArray {
        val byteArray = ByteArray(audio.size * 2)
        for (i in audio.indices) {
            var sampleVal = audio[i]
            if (sampleVal > 1.0f) sampleVal = 1.0f
            if (sampleVal < -1.0f) sampleVal = -1.0f
            
            val sample = (sampleVal * 32767).toInt()
            byteArray[2 * i] = sample.toByte()
            byteArray[2 * i + 1] = (sample shr 8).toByte()
        }
        return byteArray
    }
}

