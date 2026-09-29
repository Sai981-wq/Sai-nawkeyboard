package com.mettavoice.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import java.util.Locale

class ShanTtsSettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "mettavoice_tts_prefs"
        const val PREF_SPEED = "pref_speed"
        const val PREF_PITCH = "pref_pitch"
        const val PREF_SECONDARY_ENGINE = "pref_secondary_engine"
    }

    private lateinit var speedLabel: TextView
    private lateinit var pitchLabel: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var pitchBar: SeekBar
    private lateinit var etTextToAudio: EditText
    private lateinit var btnListen: Button
    private lateinit var tabLayout: TabLayout
    private lateinit var primaryContainer: ScrollView
    private lateinit var secondaryContainer: ScrollView
    private lateinit var spinnerEngines: Spinner
    private lateinit var btnTestEnglish: Button
    private var externalTts: TextToSpeech? = null
    private var engineList = listOf<TextToSpeech.EngineInfo>()
    private val directPlayer = ShanTtsService()
    private var playThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_burmese_tts_settings)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        tabLayout = findViewById(R.id.tabLayout)
        primaryContainer = findViewById(R.id.primary_container)
        secondaryContainer = findViewById(R.id.secondary_container)
        speedLabel = findViewById(R.id.tv_speed_label)
        pitchLabel = findViewById(R.id.tv_pitch_label)
        speedBar = findViewById(R.id.sb_speed)
        pitchBar = findViewById(R.id.sb_pitch)
        etTextToAudio = findViewById(R.id.et_text_to_audio)
        btnListen = findViewById(R.id.btn_listen)
        spinnerEngines = findViewById(R.id.spinner_tts_engines)
        btnTestEnglish = findViewById(R.id.btn_test_english)

        setupTabs()
        setupPrimaryTtsControls(prefs)
        setupSecondaryTtsControls(prefs)
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Primary TTS (Myanmar)"))
        tabLayout.addTab(tabLayout.newTab().setText("Secondary TTS (English)"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        primaryContainer.visibility = View.VISIBLE
                        secondaryContainer.visibility = View.GONE
                    }
                    1 -> {
                        primaryContainer.visibility = View.GONE
                        secondaryContainer.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupSecondaryTtsControls(prefs: android.content.SharedPreferences) {
        externalTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engineList = externalTts?.engines?.filter { it.name != packageName } ?: emptyList()
                val engineNames = engineList.map { it.label }
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, engineNames)
                spinnerEngines.adapter = adapter

                val savedEngine = prefs.getString(PREF_SECONDARY_ENGINE, "")
                val savedIndex = engineList.indexOfFirst { it.name == savedEngine }
                if (savedIndex >= 0) spinnerEngines.setSelection(savedIndex)

                spinnerEngines.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val selectedEnginePackage = engineList[position].name
                        prefs.edit().putString(PREF_SECONDARY_ENGINE, selectedEnginePackage).apply()
                        externalTts = TextToSpeech(this@ShanTtsSettingsActivity, null, selectedEnginePackage)
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
        }
        btnTestEnglish.setOnClickListener {
            externalTts?.language = Locale.US
            externalTts?.speak("Hello, this is a test for English Text to Speech.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun setupPrimaryTtsControls(prefs: android.content.SharedPreferences) {
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
                Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show()
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
        externalTts?.stop()
        externalTts?.shutdown()
        super.onDestroy()
    }
}

