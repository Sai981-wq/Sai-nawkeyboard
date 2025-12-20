package com.shan.tts.manager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

class GetSampleText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val language = intent.getStringExtra("language")
        val country = intent.getStringExtra("country")
        val variant = intent.getStringExtra("variant")

        val locale = if (language != null) {
            Locale(language, country ?: "", variant ?: "")
        } else {
            Locale.getDefault()
        }

        val text = when (locale.language.lowercase(Locale.ROOT)) {
            "shn", "shan" -> "မႂ်ႇသုင်ၶႃႈ ၼႆႉပဵၼ် တူဝ်ယၢင်ႇ ဢၼ်လူဢၢၼ်ႇပၼ် ၽႃႇသႃႇတႆးၶႃႈ"
            "my", "mya", "bur" -> "မင်္ဂလာပါ၊ ဒါကတော့ မြန်မာစာ အစမ်းဖတ်ပြခြင်း ဖြစ်ပါတယ်"
            "en", "eng" -> "This is an example of speech synthesis in English."
            else -> "This is an example of speech synthesis."
        }

        val returnIntent = Intent()
        returnIntent.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, text)
        
        setResult(TextToSpeech.LANG_AVAILABLE, returnIntent)
        finish()
    }
}

