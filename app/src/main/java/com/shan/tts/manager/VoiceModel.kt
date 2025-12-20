package com.shan.tts.manager

import android.speech.tts.Voice
import java.util.Locale

data class VoiceModel(
    val name: String,
    val locale: Locale,
    val enginePkg: String,
    val rateKey: String,
    val pitchKey: String
) {
    fun toAndroidVoice(): Voice {
        return Voice(
            name,
            locale,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_NORMAL,
            false,
            emptySet()
        )
    }
}
