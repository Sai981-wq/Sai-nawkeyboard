package com.shan.tts.manager

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.ArrayList

class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val availableVoices = ArrayList<String>()
        val unavailableVoices = ArrayList<String>()

        if (isPackageInstalled("com.espeak.ng") || isPackageInstalled("com.shan.tts")) {
            availableVoices.add("shn-MM")
            availableVoices.add("shn")
        } else {
            unavailableVoices.add("shn-MM")
            unavailableVoices.add("shn")
        }

        if (isPackageInstalled("com.google.android.tts")) {
            availableVoices.add("my-MM")
            availableVoices.add("mya-MM")
            availableVoices.add("my")
            availableVoices.add("en-US")
            availableVoices.add("eng-USA")
        } else {
            unavailableVoices.add("my-MM")
            unavailableVoices.add("mya-MM")
            unavailableVoices.add("en-US")
        }

        val returnIntent = Intent()
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices)
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailableVoices)
        
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnIntent)
        finish()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}

