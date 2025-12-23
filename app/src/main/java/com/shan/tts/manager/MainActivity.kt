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
    // ConfigPrefs သည် Rate/Pitch အတွက် မလိုတော့သော်လည်း သိမ်းဆည်းထားခြင်းမရှိပါက Error တက်နိုင်၍ ထားရှိသည်
    private lateinit var configPrefs: SharedPreferences 
    private val engineNames = ArrayList<String>()
    private val enginePackages = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
        configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)

        loadInstalledEngines()

        // Rate နှင့် Pitch Slider များကို ဖယ်ရှားလိုက်ပါပြီ
        // Engine ရွေးချယ်ရန်အတွက်သာ Setup လုပ်ပါတော့မည်
        setupEngineUI(R.id.spinnerShan, "pref_shan_pkg", "com.espeak.ng")
            
        setupEngineUI(R.id.spinnerBurmese, "pref_burmese_pkg", "com.google.android.tts")
            
        setupEngineUI(R.id.spinnerEnglish, "pref_english_pkg", "com.google.android.tts")

        setupDonation(R.id.btnKpay, "09750091817", "KBZ Pay Number Copied")
        setupDonation(R.id.btnWave, "09750091817", "Wave Pay Number Copied")

        // System TTS Settings ကို ဖွင့်ရန် Button (ရှိလျှင်) သို့မဟုတ် Menu ထည့်သွင်းနိုင်သည်
        // ဥပမာ - Engine နာမည်များကို နှိပ်လျှင် System Settings ပွင့်အောင် လုပ်ပေးထားသည်
        setupOpenSystemSettings(R.id.spinnerShan)
        setupOpenSystemSettings(R.id.spinnerBurmese)
        setupOpenSystemSettings(R.id.spinnerEnglish)

        performFullSystemScan()
    }

    private fun performFullSystemScan() {
        val progress = ProgressDialog(this)
        progress.setTitle("System Preparation")
        progress.setMessage("Initializing all TTS Engines...\nPlease wait, this may take a while.")
        progress.setCancelable(false) 
        progress.show()

        EngineScanner.scanAllEngines(this) {
            runOnUiThread {
                try {
                    if (progress.isShowing) {
                        progress.dismiss()
                    }
                } catch (e: Exception) {}
                Toast.makeText(this, "All Engines Ready!", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

    // Rate နှင့် Pitch Logic များကို ဖယ်ရှားပြီး Engine ရွေးရန်သီးသန့် ပြင်ထားသည်
    private fun setupEngineUI(spinnerId: Int, pkgKey: String, defPkg: String) {
        setSpinnerSelection(findViewById(spinnerId), pkgKey, defPkg)
    }

    // Spinner ကို Long Click နှိပ်လျှင် Android System TTS Settings ကို ဖွင့်ပေးမည့် Function
    private fun setupOpenSystemSettings(viewId: Int) {
        val view = findViewById<View>(viewId)
        view?.setOnLongClickListener {
            try {
                val intent = Intent()
                intent.action = "com.android.settings.TTS_SETTINGS"
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                Toast.makeText(this, "Opening System TTS Settings...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show()
            }
            true
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
        
        val spShan = findViewById<Spinner>(R.id.spinnerShan)
        val spBur = findViewById<Spinner>(R.id.spinnerBurmese)
        val spEng = findViewById<Spinner>(R.id.spinnerEnglish)

        spShan?.adapter = adapter
        spBur?.adapter = adapter
        spEng?.adapter = adapter
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

