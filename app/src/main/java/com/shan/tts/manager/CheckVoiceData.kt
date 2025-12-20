package com.shan.tts.manager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.ArrayList

class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val availableVoices = ArrayList<String>()
        val unavailableVoices = ArrayList<String>()

        availableVoices.add("shn")
        availableVoices.add("shn-MM")
        availableVoices.add("shan")

        availableVoices.add("my")
        availableVoices.add("my-MM")
        availableVoices.add("mya")
        availableVoices.add("mya-MM")

        availableVoices.add("en")
        availableVoices.add("en-US")
        availableVoices.add("eng")
        availableVoices.add("eng-USA")
        availableVoices.add("eng-GBR")

        val returnIntent = Intent()
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices)
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailableVoices)

        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnIntent)
        finish()
    }
}

