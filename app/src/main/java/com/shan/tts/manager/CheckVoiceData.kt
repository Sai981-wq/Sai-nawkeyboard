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
        
        // Shan (ပုံစံမျိုးစုံ ထည့်ပေးမယ်)
        availableVoices.add("shn-MM")
        availableVoices.add("shn-MMR")
        availableVoices.add("shn")

        // Burmese
        availableVoices.add("my-MM")
        availableVoices.add("mya-MM")
        availableVoices.add("my")

        // English
        availableVoices.add("en-US")
        availableVoices.add("eng-USA")
        availableVoices.add("eng-MMR") // အစ်ကို့ဖုန်းအတွက် သီးသန့်

        val returnIntent = Intent()
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices)
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList<String>())
        
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnIntent)
        finish()
    }
}

