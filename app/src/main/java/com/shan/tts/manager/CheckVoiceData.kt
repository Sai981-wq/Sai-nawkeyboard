package com.shan.tts.manager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val availableVoices = ArrayList<String>()
        val unavailableVoices = ArrayList<String>()

        VoiceConfig.supportedVoices.forEach { voice ->
            availableVoices.add(voice.name) 
        }

        val returnIntent = Intent()
        returnIntent.putExtra(TextToSpeech.Engine.EXTRA_VOICE_DATA_ROOT_DIRECTORY, "")
        returnIntent.putExtra(TextToSpeech.Engine.EXTRA_VOICE_DATA_FILES, arrayOf<String>())
        returnIntent.putExtra(TextToSpeech.Engine.EXTRA_VOICE_DATA_FILES_INFO, arrayOf<String>())
        
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices)
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailableVoices)

        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnIntent)
        finish()
    }
}

