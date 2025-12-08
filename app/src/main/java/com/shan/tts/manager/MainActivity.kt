package com.shan.tts.manager

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerShan: Spinner
    private lateinit var spinnerBurmese: Spinner
    private lateinit var spinnerEnglish: Spinner
    private lateinit var tvErrorLog: TextView
    private lateinit var btnCopyLogs: Button
    private lateinit var btnClearLogs: Button
    private lateinit var prefs: SharedPreferences

    private val engineNames = ArrayList<String>()
    private val enginePackages = ArrayList<String>()

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateLogs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)

        spinnerShan = findViewById(R.id.spinnerShan)
        spinnerBurmese = findViewById(R.id.spinnerBurmese)
        spinnerEnglish = findViewById(R.id.spinnerEnglish)
        tvErrorLog = findViewById(R.id.tvErrorLog)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)

        loadInstalledEngines()
        
        // App စဖွင့်တာနဲ့ ရှိသမျှ Log အကုန်ဆွဲထုတ်မယ်
        updateLogs()

        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("TTS Logs", tvErrorLog.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to Clipboard!", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs.setOnClickListener {
            LogHistory.clear()
            updateLogs()
            Toast.makeText(this, "Logs Cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLogs() {
        val allLogs = LogHistory.getAll()
        tvErrorLog.text = if (allLogs.isEmpty()) "No logs yet..." else allLogs
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(errorReceiver, IntentFilter("com.shan.tts.ERROR_REPORT"), RECEIVER_NOT_EXPORTED)
        updateLogs()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(errorReceiver)
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
        
        spinnerShan.adapter = adapter
        setSelection(spinnerShan, "pref_shan_pkg", "com.espeak.ng")
        spinnerShan.onItemSelectedListener = getListener("pref_shan_pkg")

        spinnerBurmese.adapter = adapter
        setSelection(spinnerBurmese, "pref_burmese_pkg", "com.google.android.tts")
        spinnerBurmese.onItemSelectedListener = getListener("pref_burmese_pkg")

        spinnerEnglish.adapter = adapter
        setSelection(spinnerEnglish, "pref_english_pkg", "com.google.android.tts")
        spinnerEnglish.onItemSelectedListener = getListener("pref_english_pkg")
    }

    private fun setSelection(spinner: Spinner, key: String, defaultPkg: String) {
        val saved = prefs.getString(key, defaultPkg)
        val index = enginePackages.indexOf(saved)
        if (index >= 0) spinner.setSelection(index)
    }

    private fun getListener(key: String): AdapterView.OnItemSelectedListener {
        return object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (enginePackages.isNotEmpty()) prefs.edit().putString(key, enginePackages[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}

