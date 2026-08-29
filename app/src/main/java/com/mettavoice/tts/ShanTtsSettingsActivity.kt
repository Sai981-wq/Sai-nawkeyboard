package com.mettavoice.tts

import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.mettavoice.tts.R
import java.util.Locale

class ShanTtsSettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "mettavoice_tts_prefs"
        const val PREF_SPEED = "pref_speed"
        const val PREF_PITCH = "pref_pitch"
    }

    private lateinit var speedLabel: TextView
    private lateinit var pitchLabel: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var pitchBar: SeekBar
    private lateinit var etTextToAudio: EditText
    private lateinit var btnListen: Button

    private val directPlayer = ShanTtsService()
    private var playThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_burmese_tts_settings)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        speedLabel = findViewById(R.id.tv_speed_label)
        pitchLabel = findViewById(R.id.tv_pitch_label)
        speedBar = findViewById(R.id.sb_speed)
        pitchBar = findViewById(R.id.sb_pitch)
        etTextToAudio = findViewById(R.id.et_text_to_audio)
        btnListen = findViewById(R.id.btn_listen)

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
        }

        btnListen.setOnClickListener {
            val text = etTextToAudio.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "ကျေးဇူးပြု၍ စာသားရိုက်ထည့်ပါ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentSpeedVal = prefs.getFloat(PREF_SPEED, 0.8f)
            val currentPitchVal = prefs.getFloat(PREF_PITCH, 1.0f)

            directPlayer.stopDirectAudio()

            playThread = Thread {
                directPlayer.playDirectAudio(this@ShanTtsSettingsActivity, text, currentSpeedVal, currentPitchVal)
            }
            playThread?.start()
        }
    }

    private fun updateSpeedLabel(value: Float) {
        val text = "Rate: ${String.format(Locale.US, "%.1f", value)}x"
        speedLabel.text = text
        speedBar.contentDescription = text
    }

    private fun updatePitchLabel(value: Float) {
        val text = "Pitch: ${String.format(Locale.US, "%.1f", value)}x"
        pitchLabel.text = text
        pitchBar.contentDescription = text
    }

    override fun onDestroy() {
        directPlayer.stopDirectAudio()
        super.onDestroy()
    }
}

