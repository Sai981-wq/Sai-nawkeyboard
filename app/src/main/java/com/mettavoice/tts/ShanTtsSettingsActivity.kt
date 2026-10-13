package com.mettavoice.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import java.util.Locale
import android.os.Build

class ShanTtsSettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "mettavoice_tts_prefs"
        const val PREF_SPEED = "pref_speed"
        const val PREF_PITCH = "pref_pitch"
        const val PREF_VOLUME = "pref_volume"
        const val PREF_SECONDARY_ENGINE = "pref_secondary_engine"
        const val PREF_READ_PUNCTUATION = "pref_read_punctuation"
        const val PREF_READ_NUMBERS = "pref_read_numbers"
        const val PREF_PRIMARY_VOICE = "pref_primary_voice"
        const val PREF_LAST_CRASH = "pref_last_crash"
    }

    private lateinit var speedLabel: TextView
    private lateinit var pitchLabel: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var pitchBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var etTextToAudio: EditText
    private lateinit var btnListen: Button
    private lateinit var btnClear: Button
    private lateinit var tabLayout: TabLayout
    private lateinit var primaryContainer: ScrollView
    private lateinit var secondaryContainer: ScrollView
    private lateinit var spinnerEngines: Spinner
    private lateinit var spinnerPrimaryVoice: Spinner
    private lateinit var btnTestEnglish: Button
    private lateinit var cbReadPunctuation: CheckBox
    private lateinit var cbReadNumbers: CheckBox
    private lateinit var tvErrorLog: TextView
    private var externalTts: TextToSpeech? = null
    private var engineList = listOf<TextToSpeech.EngineInfo>()
    private val directPlayer = ShanTtsService()
    private var playThread: Thread? = null

    // Service မှ လှမ်းပို့မည့် Error များကို လက်ခံဖမ်းယူပြီး UI တွင် ပြပေးမည့် Receiver
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("log_msg")
            if (message != null) {
                showErrorLog(message)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            prefs.edit().putString(PREF_LAST_CRASH, "App Crash in ${thread.name}:\n${throwable.stackTraceToString()}").commit()
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }

        setContentView(R.layout.activity_burmese_tts_settings)

        tabLayout = findViewById(R.id.tabLayout)
        primaryContainer = findViewById(R.id.primary_container)
        secondaryContainer = findViewById(R.id.secondary_container)
        speedLabel = findViewById(R.id.tv_speed_label)
        pitchLabel = findViewById(R.id.tv_pitch_label)
        volumeLabel = findViewById(R.id.tv_volume_label)
        speedBar = findViewById(R.id.sb_speed)
        pitchBar = findViewById(R.id.sb_pitch)
        volumeBar = findViewById(R.id.sb_volume)
        etTextToAudio = findViewById(R.id.et_text_to_audio)
        btnListen = findViewById(R.id.btn_listen)
        btnClear = findViewById(R.id.btn_clear)
        spinnerEngines = findViewById(R.id.spinner_tts_engines)
        btnTestEnglish = findViewById(R.id.btn_test_english)
        cbReadPunctuation = findViewById(R.id.cb_read_punctuation)
        cbReadNumbers = findViewById(R.id.cb_read_numbers)
        spinnerPrimaryVoice = findViewById(R.id.spinner_primary_voice)
        tvErrorLog = findViewById(R.id.tv_error_log)

        val lastCrash = prefs.getString(PREF_LAST_CRASH, null)
        if (lastCrash != null) {
            showErrorLog(lastCrash)
            prefs.edit().remove(PREF_LAST_CRASH).apply()
        }

        setupTabs()
        setupPrimaryTtsControls(prefs)
        setupSecondaryTtsControls(prefs)
    }

    // App ပွင့်လာချိန်တွင် Receiver ကို အသက်သွင်းခြင်း
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.mettavoice.tts.TTS_LOG")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    // App နောက်ကွယ်ရောက်သွားချိန်တွင် Receiver ကို ပိတ်ထားခြင်း
    override fun onPause() {
        super.onPause()
        unregisterReceiver(logReceiver)
    }

    private fun showErrorLog(message: String) {
        runOnUiThread {
            tvErrorLog.visibility = View.VISIBLE
            // Error အသစ်ဝင်လာတိုင်း အဟောင်းအောက်တွင် ဆက်၍ပြပေးရန်
            val currentText = tvErrorLog.text.toString()
            if (currentText.isEmpty()) {
                tvErrorLog.text = message
            } else {
                tvErrorLog.text = "$currentText\n\n$message"
            }
        }
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Primary TTS"))
        tabLayout.addTab(tabLayout.newTab().setText("Secondary TTS"))

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
        val voices = arrayOf("MettaVoice Standard (Offline)", "Nilar (Neural)", "Thiha (Neural)")
        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voices)
        spinnerPrimaryVoice.adapter = voiceAdapter
        
        val savedVoice = prefs.getString(PREF_PRIMARY_VOICE, "legacy")
        when (savedVoice) {
            "nilar" -> spinnerPrimaryVoice.setSelection(1)
            "thiha" -> spinnerPrimaryVoice.setSelection(2)
            else -> spinnerPrimaryVoice.setSelection(0)
        }

        spinnerPrimaryVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedVoiceId = when(position) {
                    1 -> "nilar"
                    2 -> "thiha"
                    else -> "legacy"
                }
                prefs.edit().putString(PREF_PRIMARY_VOICE, selectedVoiceId).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val btnResetSpeed = findViewById<Button>(R.id.btn_reset_speed)
        val btnResetPitch = findViewById<Button>(R.id.btn_reset_pitch)
        val btnResetVolume = findViewById<Button>(R.id.btn_reset_volume)

        val currentSpeed = prefs.getFloat(PREF_SPEED, 0.8f)
        val currentPitch = prefs.getFloat(PREF_PITCH, 1.0f)
        val currentVolume = prefs.getInt(PREF_VOLUME, 100)
        
        speedBar.max = 180
        speedBar.progress = ((currentSpeed * 100) - 20).toInt()
        updateSpeedLabel(currentSpeed)
        
        pitchBar.max = 150
        pitchBar.progress = ((currentPitch * 100) - 50).toInt()
        updatePitchLabel(currentPitch)
        
        volumeBar.max = 100
        volumeBar.progress = currentVolume
        updateVolumeLabel(currentVolume)

        cbReadPunctuation.isChecked = prefs.getBoolean(PREF_READ_PUNCTUATION, false)
        cbReadPunctuation.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_READ_PUNCTUATION, isChecked).apply()
        }

        cbReadNumbers.isChecked = prefs.getBoolean(PREF_READ_NUMBERS, true)
        cbReadNumbers.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_READ_NUMBERS, isChecked).apply()
        }

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

        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateVolumeLabel(progress)
                prefs.edit().putInt(PREF_VOLUME, progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnResetVolume.setOnClickListener {
            volumeBar.progress = 100
            updateVolumeLabel(100)
            prefs.edit().putInt(PREF_VOLUME, 100).apply()
        }

        btnClear.setOnClickListener {
            etTextToAudio.text.clear()
            tvErrorLog.visibility = View.GONE
            tvErrorLog.text = ""
        }

        btnListen.setOnClickListener {
            tvErrorLog.visibility = View.GONE
            tvErrorLog.text = ""
            val text = etTextToAudio.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val currentSpeedVal = prefs.getFloat(PREF_SPEED, 0.8f)
            val currentPitchVal = prefs.getFloat(PREF_PITCH, 1.0f)
            directPlayer.stopDirectAudio()
            playThread = Thread {
                directPlayer.playDirectAudio(this@ShanTtsSettingsActivity, text, currentSpeedVal, currentPitchVal) { error ->
                    showErrorLog(error)
                }
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
    
    private fun updateVolumeLabel(value: Int) {
        val text = "Volume: $value%"
        volumeLabel.text = text
        volumeBar.contentDescription = text
    }

    override fun onDestroy() {
        directPlayer.stopDirectAudio()
        externalTts?.stop()
        externalTts?.shutdown()
        super.onDestroy()
    }
}

