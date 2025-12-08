package com.shan.tts.manager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.ArrayList

class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // System က လက်ခံနိုင်တဲ့ ဘာသာစကားကုဒ်တွေ (ISO-3 format)
        val availableVoices = ArrayList<String>()
        
        // 1. Shan (shn-MM)
        availableVoices.add("shn-MM")
        availableVoices.add("shn-MMR")
        
        // 2. Burmese (my-MM)
        availableVoices.add("my-MM")
        availableVoices.add("mya-MM")
        
        // 3. English (en-US)
        availableVoices.add("en-US")
        availableVoices.add("eng-USA")

        // System ကို ပြန်ဖြေကြားခြင်း
        val returnIntent = Intent()
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices)
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList<String>())
        
        // "PASS" (အောင်မြင်သည်) ဟု ပြန်ဖြေခြင်း
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnIntent)
        
        // ပြီးရင် Activity ကို ပိတ်လိုက်မယ်
        finish()
    }
}
