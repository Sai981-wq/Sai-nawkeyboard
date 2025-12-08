package com.shan.tts.manager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val engineNames = ArrayList<String>()
    private val enginePackages = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)

        loadInstalledEngines()

        // Setup SeekBars & Spinners
        setupEngineUI(R.id.spinnerShan, "pref_shan_pkg", "com.espeak.ng", R.id.seekShanRate, "rate_shan", R.id.seekShanPitch, "pitch_shan")
        setupEngineUI(R.id.spinnerBurmese, "pref_burmese_pkg", "com.google.android.tts", R.id.seekBurmeseRate, "rate_burmese", R.id.seekBurmesePitch, "pitch_burmese")
        setupEngineUI(R.id.spinnerEnglish, "pref_english_pkg", "com.google.android.tts", R.id.seekEnglishRate, "rate_english", R.id.seekEnglishPitch, "pitch_english")
    }

    private fun setupEngineUI(spinnerId: Int, pkgKey: String, defPkg: String, rateId: Int, rateKey: String, pitchId: Int, pitchKey: String) {
        val spinner = findViewById<Spinner>(spinnerId)
        val seekRate = findViewById<SeekBar>(rateId)
        val seekPitch = findViewById<SeekBar>(pitchId)

        // Load Saved Values (Default 100)
        seekRate.progress = prefs.getInt(rateKey, 100)
        seekPitch.progress = prefs.getInt(pitchKey, 100)

        // Listeners
        seekRate.setOnSeekBarChangeListener(getSeekListener(rateKey))
        seekPitch.setOnSeekBarChangeListener(getSeekListener(pitchKey))

        // Bind Spinner Selection
        val savedPkg = prefs.getString(pkgKey, defPkg)
        val idx = enginePackages.indexOf(savedPkg)
        if (idx >= 0) spinner.setSelection(idx)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (enginePackages.isNotEmpty()) prefs.edit().putString(pkgKey, enginePackages[pos]).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun getSeekListener(key: String): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) prefs.edit().putInt(key, progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
    }

    private fun loadInstalledEngines() {
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos = packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        engineNames.clear(); enginePackages.clear()

        for (info in resolveInfos) {
            val pkg = info.serviceInfo.packageName
            if (pkg != packageName) {
                engineNames.add(info.serviceInfo.loadLabel(packageManager).toString())
                enginePackages.add(pkg)
            }
        }
        if (engineNames.isEmpty()) { engineNames.add("No Engines Found"); enginePackages.add("") }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, engineNames)
        findViewById<Spinner>(R.id.spinnerShan).adapter = adapter
        findViewById<Spinner>(R.id.spinnerBurmese).adapter = adapter
        findViewById<Spinner>(R.id.spinnerEnglish).adapter = adapter
    }
}

