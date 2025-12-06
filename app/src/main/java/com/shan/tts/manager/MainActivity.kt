package com.shan.tts.manager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerShan: Spinner
    private lateinit var spinnerBurmese: Spinner
    private lateinit var spinnerEnglish: Spinner
    private lateinit var prefs: SharedPreferences

    private val engineNames = ArrayList<String>()
    private val enginePackages = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)

        spinnerShan = findViewById(R.id.spinnerShan)
        spinnerBurmese = findViewById(R.id.spinnerBurmese)
        spinnerEnglish = findViewById(R.id.spinnerEnglish)

        loadInstalledEngines()
    }

    private fun loadInstalledEngines() {
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos = packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)

        engineNames.clear()
        enginePackages.clear()

        for (info in resolveInfos) {
            val serviceInfo = info.serviceInfo
            val label = serviceInfo.loadLabel(packageManager).toString()
            val packageName = serviceInfo.packageName

            // မိမိ App ကို List ထဲမထည့်ပါ (Loop မဖြစ်စေရန်)
            if (packageName != this.packageName) {
                engineNames.add(label)
                enginePackages.add(packageName)
            }
        }

        if (engineNames.isEmpty()) {
            engineNames.add("No Engines Found")
            enginePackages.add("")
        }

        setupSpinners()
    }

    private fun setupSpinners() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, engineNames)

        // Shan (Default: try finding eSpeak, else first item)
        spinnerShan.adapter = adapter
        setSelection(spinnerShan, "pref_shan_pkg", "com.espeak.ng")
        spinnerShan.onItemSelectedListener = getListener("pref_shan_pkg")

        // Burmese (Default: try finding Google, else first item)
        spinnerBurmese.adapter = adapter
        setSelection(spinnerBurmese, "pref_burmese_pkg", "com.google.android.tts")
        spinnerBurmese.onItemSelectedListener = getListener("pref_burmese_pkg")

        // English (Default: try finding Google, else first item)
        spinnerEnglish.adapter = adapter
        setSelection(spinnerEnglish, "pref_english_pkg", "com.google.android.tts")
        spinnerEnglish.onItemSelectedListener = getListener("pref_english_pkg")
    }

    private fun setSelection(spinner: Spinner, key: String, defaultPkg: String) {
        val saved = prefs.getString(key, defaultPkg)
        val index = enginePackages.indexOf(saved)
        if (index >= 0) {
            spinner.setSelection(index)
        }
    }

    private fun getListener(key: String): AdapterView.OnItemSelectedListener {
        return object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (enginePackages.isNotEmpty()) {
                    val pkg = enginePackages[position]
                    prefs.edit().putString(key, pkg).apply()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}
