package com.shan.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class GetSampleText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent()
        intent.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, "မုင်ႇ")
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}