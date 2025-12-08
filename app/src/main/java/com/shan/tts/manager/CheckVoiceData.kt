package com.shan.tts.manager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.ArrayList

class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // System က လာစစ်ကြောင်း Log ပို့မယ်
        sendLogToUI("System called CHECK_TTS_DATA (Voice Check)")

        val availableVoices = ArrayList<String>()
        availableVoices.add("shn-MM")
        availableVoices.add("my-MM")
        availableVoices.add("en-US")
        
        // Log ထပ်ပို့မယ်
        sendLogToUI("Sending back: $availableVoices")

        val returnIntent = Intent()
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices)
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList<String>())
        
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnIntent)
        finish()
    }

    private fun sendLogToUI(msg: String) {
        val intent = Intent("com.shan.tts.ERROR_REPORT")
        intent.putExtra("error_msg", "[CheckData] $msg")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }
}

