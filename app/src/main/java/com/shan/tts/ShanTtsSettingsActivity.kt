package com.shan.tts

import android.app.Activity
import android.content.Context
import android.os.Bundle
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
        setContentView(R.layout.activity_shan_tts_settings)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentSpeed = prefs.getFloat(PREF_SPEED, 1.0f)
        val currentPitch = prefs.getFloat(PREF_PITCH, 1.0f)

        val speedLabel = findViewById<TextView>(R.id.tv_speed_label)
        val speedBar = findViewById<SeekBar>(R.id.sb_speed)
        val pitchLabel = findViewById<TextView>(R.id.tv_pitch_label)
        val pitchBar = findViewById<SeekBar>(R.id.sb_pitch)

        speedLabel.text = getString(R.string.speed_label).replace("1.0", String.format("%.1f", currentSpeed))
        speedBar.progress = (currentSpeed * 100).toInt() - 50

        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 50) / 100f
                speedLabel.text = "TTS rate: ${String.format("%.1f", value)}x"
                prefs.edit().putFloat(PREF_SPEED, value).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        pitchLabel.text = getString(R.string.pitch_label).replace("1.0", String.format("%.1f", currentPitch))
        pitchBar.progress = (currentPitch * 100).toInt() - 50

        pitchBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 50) / 100f
                pitchLabel.text = "TTS pitch: ${String.format("%.1f", value)}x"
                prefs.edit().putFloat(PREF_PITCH, value).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
}

