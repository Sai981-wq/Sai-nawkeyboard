package com.shan.tts.manager

import android.content.ClipData
import android.content.ClipboardManager
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

        setupEngineUI(R.id.spinnerShan, "pref_shan_pkg", "com.espeak.ng", R.id.seekShanRate, "rate_shan", R.id.seekShanPitch, "pitch_shan")
        setupEngineUI(R.id.spinnerBurmese, "pref_burmese_pkg", "com.google.android.tts", R.id.seekBurmeseRate, "rate_burmese", R.id.seekBurmesePitch, "pitch_burmese")
        setupEngineUI(R.id.spinnerEnglish, "pref_english_pkg", "com.google.android.tts", R.id.seekEnglishRate, "rate_english", R.id.seekEnglishPitch, "pitch_english")

        // *** DONATION LOGIC ***
        setupDonation(R.id.btnKpay, "09750091817", "KBZ Pay Number Copied")
        setupDonation(R.id.btnWave, "09750091817", "Wave Pay Number Copied")

        // *** LOG VIEWER TRIGGER ***
        setupLogTrigger()
    }

    // Log ကြည့်ရန် ခလုတ် သို့မဟုတ် Shortcut သတ်မှတ်ခြင်း
    private fun setupLogTrigger() {
        // နည်းလမ်း (၁) - XML မှာ id = btnLogs ဆိုပြီး ခလုတ်ထည့်ထားရင် အလုပ်လုပ်မယ်
        val btnLogs = findViewById<View>(resources.getIdentifier("btnLogs", "id", packageName))
        btnLogs?.setOnClickListener {
            openLogViewer()
        }

        // နည်းလမ်း (၂) - XML ပြင်စရာမလိုပါ (KBZ Pay ခလုတ်ကို ဖိထားရင် Log ပွင့်မယ်)
        val btnKpay = findViewById<View>(R.id.btnKpay)
        btnKpay?.setOnLongClickListener {
            Toast.makeText(this, "Opening Debug Logs...", Toast.LENGTH_SHORT).show()
            openLogViewer()
            true // Consume the long click
        }
    }

    private fun openLogViewer() {
        try {
            startActivity(Intent(this, LogViewerActivity::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Log Activity not found!", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupDonation(viewId: Int, number: String, msg: String) {
        findViewById<View>(viewId).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Donation Number", number)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupEngineUI(spinnerId: Int, pkgKey: String, defPkg: String, rateId: Int, rateKey: String, pitchId: Int, pitchKey: String) {
        val spinner = findViewById<Spinner>(spinnerId)
        val seekRate = findViewById<SeekBar>(rateId)
        val seekPitch = findViewById<SeekBar>(pitchId)

        // Load saved values
        seekRate.progress = prefs.getInt(rateKey, 100)
        seekPitch.progress = prefs.getInt(pitchKey, 100)

        // Listeners
        seekRate.setOnSeekBarChangeListener(getSeekListener(rateKey))
        seekPitch.setOnSeekBarChangeListener(getSeekListener(pitchKey))

        // Spinner Setup
        val savedPkg = prefs.getString(pkgKey, defPkg)
        val idx = enginePackages.indexOf(savedPkg)
        
        // Only set selection if list is not empty and index is valid
        if (enginePackages.isNotEmpty()) {
             if (idx >= 0) {
                 spinner.setSelection(idx)
             } else {
                 // Try to select default if saved one is gone
                 val defIdx = enginePackages.indexOf(defPkg)
                 if (defIdx >= 0) spinner.setSelection(defIdx)
             }
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (enginePackages.isNotEmpty() && pos >= 0 && pos < enginePackages.size) {
                    prefs.edit().putString(pkgKey, enginePackages[pos]).apply()
                }
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
            // Exclude ourselves to prevent recursion if we are in the list
            if (pkg != packageName) {
                val label = info.serviceInfo.loadLabel(packageManager).toString()
                engineNames.add(label)
                enginePackages.add(pkg)
            }
        }
        
        if (engineNames.isEmpty()) { 
            engineNames.add("No Engines Found")
            enginePackages.add("") 
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, engineNames)
        
        // Apply adapter to all spinners
        findViewById<Spinner>(R.id.spinnerShan)?.adapter = adapter
        findViewById<Spinner>(R.id.spinnerBurmese)?.adapter = adapter
        findViewById<Spinner>(R.id.spinnerEnglish)?.adapter = adapter
    }
}

