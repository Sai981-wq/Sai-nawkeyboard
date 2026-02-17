package com.panglong.tts

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class PanglongTtsSettingsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private lateinit var languageSpinner: Spinner
    private lateinit var testButton: Button
    private lateinit var statusText: TextView

    private val languages = arrayOf("ရှမ်း (Shan)", "မြန်မာ (Myanmar)")
    private val langCodes = arrayOf("shn", "my")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        languageSpinner = findViewById(R.id.language_spinner)
        testButton = findViewById(R.id.test_button)
        statusText = findViewById(R.id.status_text)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        languageSpinner.adapter = adapter

        tts = TextToSpeech(this, this, "com.panglong.tts")

        testButton.setOnClickListener {
            val selectedIndex = languageSpinner.selectedItemPosition
            val langCode = langCodes[selectedIndex]
            val locale = Locale(langCode)
            tts?.language = locale

            val testText = when (selectedIndex) {
                0 -> "မႂ်ႇသုင်ႇ"
                1 -> "မင်္ဂလာပါ"
                else -> "test"
            }

            tts?.speak(testText, TextToSpeech.QUEUE_FLUSH, null, "test")
            statusText.text = "Playing: $testText"
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            statusText.text = "TTS Engine Ready"
        } else {
            statusText.text = "TTS Engine Failed to Initialize"
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
