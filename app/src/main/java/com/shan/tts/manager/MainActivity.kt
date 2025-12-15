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

        loadInstalledEngines()

        setupEngineUI(R.id.spinnerShan, "pref_shan_pkg", "com.espeak.ng", R.id.seekShanRate, "rate_shan", R.id.seekShanPitch, "pitch_shan")
        setupEngineUI(R.id.spinnerBurmese, "pref_burmese_pkg", "com.google.android.tts", R.id.seekBurmeseRate, "rate_burmese", R.id.seekBurmesePitch, "pitch_burmese")
        setupEngineUI(R.id.spinnerEnglish, "pref_english_pkg", "com.google.android.tts", R.id.seekEnglishRate, "rate_english", R.id.seekEnglishPitch, "pitch_english")

        setupDonation(R.id.btnKpay, "09750091817", "KBZ Pay Number Copied")
        setupDonation(R.id.btnWave, "09750091817", "Wave Pay Number Copied")

        // Auto Scan on startup
        performEngineScan()
    }

    private fun performEngineScan() {
        val progress = ProgressDialog(this)
        progress.setMessage("Scanning TTS Engines...")
        progress.setCancelable(false)
        progress.show()

        EngineScanner.scanAllEngines(this, 
            onProgress = { msg ->
                runOnUiThread { progress.setMessage(msg) }
            },
            onComplete = {
                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(this, "Scan Complete", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    
    private fun setupDonation(viewId: Int, number: String, msg: String) {
        val btn = findViewById<View>(viewId)
        
        // Single Click to Copy
        btn?.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Donation Number", number)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Long Click to Open Logs (KBZ Pay Only or both)
        if (viewId == R.id.btnKpay) {
            btn?.setOnLongClickListener {
                try {
                    val intent = Intent(this, LogViewerActivity::class.java)
                    startActivity(intent)
                    Toast.makeText(this, "Opening Logs...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error opening logs: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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
        val idx = enginePackages.indexOf(savedPkg)
        
        if (enginePackages.isNotEmpty()) {
             if (idx >= 0) {
                 spinner.setSelection(idx)
             } else {
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
        
        findViewById<Spinner>(R.id.spinnerShan)?.adapter = adapter
        findViewById<Spinner>(R.id.spinnerBurmese)?.adapter = adapter
        findViewById<Spinner>(R.id.spinnerEnglish)?.adapter = adapter
    }
}

