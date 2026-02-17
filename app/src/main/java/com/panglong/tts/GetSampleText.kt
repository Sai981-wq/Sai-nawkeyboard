package com.panglong.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class GetSampleText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        val language = intent?.getStringExtra("language") ?: "shn"
        val sampleText = when (language) {
            "my", "mya" -> "မင်္ဂလာပါ"
            else -> "မႂ်ႇသုင်ႇ"
        }
        result.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sampleText)
        setResult(TextToSpeech.LANG_AVAILABLE, result)
        finish()
    }
}
