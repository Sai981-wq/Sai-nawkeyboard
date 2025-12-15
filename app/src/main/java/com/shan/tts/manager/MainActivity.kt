package com.shan.tts.manager

import android.app.ProgressDialog
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

        // UI Setup
        setupEngineUI(R.id.spinnerShan, "pref_shan_pkg", "com.espeak.ng", R.id.seekShanRate, "rate_shan", R.id.seekShanPitch, "pitch_shan")
        setupEngineUI(R.id.spinnerBurmese, "pref_burmese_pkg", "com.google.android.tts", R.id.seekBurmeseRate, "rate_burmese", R.id.seekBurmesePitch, "pitch_burmese")
        setupEngineUI(R.id.spinnerEnglish, "pref_english_pkg", "com.google.android.tts", R.id.seekEnglishRate, "rate_english", R.id.seekEnglishPitch, "pitch_english")
        
        setupDonation(R.id.btnKpay, "09750091817", "KBZ Pay Number Copied")
        setupDonation(R.id.btnWave, "09750091817", "Wave Pay Number Copied")

        // အက်ပ်စဖွင့်တာနဲ့ Scan ဖတ်မယ် (မပြီးမချင်း မလွှတ်ပေးပါ)
        performFullSystemScan()
    }

    private fun performFullSystemScan() {
        // User ကို ပိတ်ထားမယ့် Dialog (ပယ်ဖျက်လို့မရ)
        val progress = ProgressDialog(this)
        progress.setTitle("System Preparation")
        progress.setMessage("Initializing all TTS Engines...\nPlease wait, this may take a while.")
        progress.setCancelable(false) // Back နှိပ်လို့မရအောင် ပိတ်ထားသည်
        progress.show()

        EngineScanner.scanAllEngines(this, 
            onProgress = { msg ->
                runOnUiThread { 
                    progress.setMessage(msg) 
                }
            },
            onComplete = {
                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(this, "All Engines Ready!", Toast.LENGTH_SHORT).show()
                    // Scan ပြီးမှ Engine List ကို UI မှာ ပြန်တင်မယ်
                    loadInstalledEngines() 
                }
            }
        )
    }
    
    // ... (UI Logic ကျန်တာ အတူတူပါပဲ) ...

    private fun setupDonation(viewId: Int, number: String, msg: String) {
        val btn = findViewById<View>(viewId)
        btn?.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Donation Number", number)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        if (viewId == R.id.btnKpay) {
            btn?.setOnLongClickListener {
                try {
                    startActivity(Intent(this, LogViewerActivity::class.java))
                } catch (e: Exception) {}
                true
            }
        }
    }

    private fun setupEngineUI(spinnerId: Int, pkgKey: String, defPkg: String, rateId: Int, rateKey: String, pitchId: Int, pitchKey: String) {
        val spinner = findViewById<Spinner>(spinnerId)
        val seekRate = findViewById<SeekBar>(rateId)
        val seekPitch = findViewById<SeekBar>(pitchId)

        seekRate.progress = prefs.getInt(rateKey, 50)
        seekPitch.progress = prefs.getInt(pitchKey, 50)

        seekRate.setOnSeekBarChangeListener(getSeekListener(rateKey))
        seekPitch.setOnSeekBarChangeListener(getSeekListener(pitchKey))

        val savedPkg = prefs.getString(pkgKey, defPkg)
        // Note: Spinner selection happens after loadInstalledEngines
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
            // Filter out own app
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
        
        val spShan = findViewById<Spinner>(R.id.spinnerShan)
        val spBur = findViewById<Spinner>(R.id.spinnerBurmese)
        val spEng = findViewById<Spinner>(R.id.spinnerEnglish)

        spShan?.adapter = adapter
        spBur?.adapter = adapter
        spEng?.adapter = adapter

        // Restore selections
        setSpinnerSelection(spShan, "pref_shan_pkg", "com.espeak.ng")
        setSpinnerSelection(spBur, "pref_burmese_pkg", "com.google.android.tts")
        setSpinnerSelection(spEng, "pref_english_pkg", "com.google.android.tts")
    }

    private fun setSpinnerSelection(spinner: Spinner?, key: String, def: String) {
        val saved = prefs.getString(key, def)
        val idx = enginePackages.indexOf(saved)
        if (idx >= 0) spinner?.setSelection(idx)
        
        spinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (enginePackages.isNotEmpty() && pos >= 0) {
                    prefs.edit().putString(key, enginePackages[pos]).apply()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}

