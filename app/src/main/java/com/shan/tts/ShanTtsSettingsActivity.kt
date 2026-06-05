package com.shan.tts

import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView

class ShanTtsSettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "shan_tts_prefs"
        const val PREF_SPEED = "pref_speed"
        const val PREF_PITCH = "pref_pitch"
    }

    private lateinit var speedLabel: TextView
    private lateinit var pitchLabel: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var pitchBar: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shan_tts_settings)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        speedLabel = findViewById(R.id.tv_speed_label)
        pitchLabel = findViewById(R.id.tv_pitch_label)
        speedBar = findViewById(R.id.sb_speed)
        pitchBar = findViewById(R.id.sb_pitch)

        val btnResetSpeed = findViewById<Button>(R.id.btn_reset_speed)
        val btnResetPitch = findViewById<Button>(R.id.btn_reset_pitch)

        val currentSpeed = prefs.getFloat(PREF_SPEED, 0.8f)
        val currentPitch = prefs.getFloat(PREF_PITCH, 1.0f)

        speedBar.max = 180
        speedBar.progress = ((currentSpeed * 100) - 20).toInt()
        updateSpeedLabel(currentSpeed)

        pitchBar.max = 150
        pitchBar.progress = ((currentPitch * 100) - 50).toInt()
        updatePitchLabel(currentPitch)

        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 20) / 100f
                updateSpeedLabel(value)
                prefs.edit().putFloat(PREF_SPEED, value).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnResetSpeed.setOnClickListener {
            speedBar.progress = 60 
            val value = 0.8f
            updateSpeedLabel(value)
            prefs.edit().putFloat(PREF_SPEED, value).apply()
            btnResetSpeed.announceForAccessibility("Speed reset to zero point eight")
        }

        pitchBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 50) / 100f
                updatePitchLabel(value)
                prefs.edit().putFloat(PREF_PITCH, value).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnResetPitch.setOnClickListener {
            pitchBar.progress = 50 
            val value = 1.0f
            updatePitchLabel(value)
            prefs.edit().putFloat(PREF_PITCH, value).apply()
            btnResetPitch.announceForAccessibility("Pitch reset to normal")
        }
    }

    private fun updateSpeedLabel(value: Float) {
        val text = "Rate: ${String.format("%.1f", value)}x"
        speedLabel.text = text
        speedBar.contentDescription = text
    }

    private fun updatePitchLabel(value: Float) {
        val text = "Pitch: ${String.format("%.1f", value)}x"
        pitchLabel.text = text
        pitchBar.contentDescription = text
    }
}

