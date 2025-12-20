package com.shan.tts.manager

import java.util.Locale

object VoiceConfig {
    val supportedVoices = listOf(
        VoiceModel("shn-MM", Locale("shn", "MM"), "com.espeak.ng", "rate_shan", "pitch_shan"),
        VoiceModel("my-MM", Locale("my", "MM"), "com.google.android.tts", "rate_burmese", "pitch_burmese"),
        VoiceModel("en-US", Locale.US, "com.google.android.tts", "rate_english", "pitch_english")
    )
}
