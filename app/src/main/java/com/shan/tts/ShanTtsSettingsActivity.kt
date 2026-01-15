package com.shan.tts

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class ShanTtsSettingsActivity : Activity() {

    companion object {
        const val PREFS_NAME = "shan_tts_prefs"
        const val PREF_SPEED = "pref_speed"
        const val PREF_PITCH = "pref_pitch"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        scrollView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        scrollView.setBackgroundColor(Color.WHITE)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(60, 60, 60, 60)
        layout.gravity = Gravity.CENTER_HORIZONTAL
        
        scrollView.addView(layout)

        val title = TextView(this)
        title.text = "Shan TTS Settings"
        title.textSize = 26f
        title.setTypeface(null, Typeface.BOLD)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, 60)
        title.setTextColor(Color.BLACK)
        layout.addView(title)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentSpeed = prefs.getFloat(PREF_SPEED, 1.0f)
        val currentPitch = prefs.getFloat(PREF_PITCH, 1.0f)

        val speedLabel = TextView(this)
        speedLabel.text = "TTS rate: ${String.format("%.1f", currentSpeed)}x"
        speedLabel.textSize = 18f
        speedLabel.setTextColor(Color.BLACK)
        layout.addView(speedLabel)

        val speedBar = SeekBar(this)
        speedBar.max = 200
        speedBar.progress = (currentSpeed * 100).toInt() - 50
        speedBar.setPadding(0, 30, 0, 30)
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 50) / 100f
                speedLabel.text = "TTS rate: ${String.format("%.1f", value)}x"
                prefs.edit().putFloat(PREF_SPEED, value).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        layout.addView(speedBar)

        val spacer1 = TextView(this)
        spacer1.height = 50
        layout.addView(spacer1)

        val pitchLabel = TextView(this)
        pitchLabel.text = "TTS pitch: ${String.format("%.1f", currentPitch)}x"
        pitchLabel.textSize = 18f
        pitchLabel.setTextColor(Color.BLACK)
        layout.addView(pitchLabel)

        val pitchBar = SeekBar(this)
        pitchBar.max = 150 
        pitchBar.progress = (currentPitch * 100).toInt() - 50
        pitchBar.setPadding(0, 30, 0, 30)
        pitchBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 50) / 100f
                pitchLabel.text = "TTS pitch: ${String.format("%.1f", value)}x"
                prefs.edit().putFloat(PREF_PITCH, value).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        layout.addView(pitchBar)

        val spacer2 = TextView(this)
        spacer2.height = 80
        layout.addView(spacer2)

        val divider = View(this)
        divider.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2)
        divider.setBackgroundColor(Color.LTGRAY)
        layout.addView(divider)

        val spacer3 = TextView(this)
        spacer3.height = 80
        layout.addView(spacer3)

        val aboutTitle = TextView(this)
        aboutTitle.text = "Welcome to Shan TTS"
        aboutTitle.textSize = 22f
        aboutTitle.setTypeface(null, Typeface.BOLD)
        aboutTitle.gravity = Gravity.CENTER
        aboutTitle.setTextColor(Color.DKGRAY)
        layout.addView(aboutTitle)

        val spacer4 = TextView(this)
        spacer4.height = 30
        layout.addView(spacer4)

        val aboutText = TextView(this)
        aboutText.text = """
            Created by: Sai Naw

            This engine is dedicated to the Shan blind community, to make technology accessible in the Shan language.

            Your feedback is essential for improvement. If you find any bugs or have suggestions for new words, please contact:
            sainaw1331@gmail.com

            This engine will be continuously updated.
            Thank you for your support.
        """.trimIndent()
        
        aboutText.textSize = 16f
        aboutText.gravity = Gravity.CENTER_HORIZONTAL
        aboutText.setLineSpacing(0f, 1.3f)
        aboutText.textAlignment = View.TEXT_ALIGNMENT_CENTER
        aboutText.setTextColor(Color.BLACK)
        layout.addView(aboutText)

        val spacer5 = TextView(this)
        spacer5.height = 100
        layout.addView(spacer5)

        setContentView(scrollView)
    }
}

