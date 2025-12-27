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

        // 1. Shan (shn) -> ISO 3-Letter (shn) + ISO 3-Letter Country (MMR)
        availableVoices.add("shn-MMR")

        // 2. Burmese (mya) -> ISO 3-Letter (mya) + ISO 3-Letter Country (MMR)
        availableVoices.add("mya-MMR")

        // 3. English (eng) -> ISO 3-Letter (eng) + ISO 3-Letter Country (USA)
        availableVoices.add("eng-USA")

        val returnIntent = Intent()
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices)
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailableVoices)

        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnIntent)
        finish()
    }
}

