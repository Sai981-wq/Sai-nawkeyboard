package com.cherry.sme.tts;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private ArrayList<String> engineNames = new ArrayList<>();
    private ArrayList<String> enginePackages = new ArrayList<>();
    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        showScanDialog();

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                loadInstalledEngines();
                
                setupEngineUI(R.id.spinnerShan, "pref_engine_shan", "com.espeak.ng");
                setupEngineUI(R.id.spinnerBurmese, "pref_engine_myanmar", "org.saomaicenter.myanmartts");
                setupEngineUI(R.id.spinnerEnglish, "pref_engine_english", "com.google.android.tts");

                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
            }
        }, 1500);

        setupDonation(R.id.btnKpay, "09750091817", "KBZ Pay Number Copied");
        setupDonation(R.id.btnWave, "09750091817", "Wave Pay Number Copied");

        setupOpenSystemSettings(R.id.spinnerShan);
        setupOpenSystemSettings(R.id.spinnerBurmese);
        setupOpenSystemSettings(R.id.spinnerEnglish);
    }

    private void showScanDialog() {
        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Scanning engines, please wait...");
        progressDialog.setCancelable(false);
        progressDialog.show();
    }

    private void setupDonation(int viewId, final String number, final String msg) {
        View btn = findViewById(viewId);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Donation Number", number);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupEngineUI(int spinnerId, String pkgKey, String defPkg) {
        Spinner spinner = findViewById(spinnerId);
        setSpinnerSelection(spinner, pkgKey, defPkg);
    }

    private void setupOpenSystemSettings(int viewId) {
        View view = findViewById(viewId);
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                try {
                    Intent intent = new Intent();
                    intent.setAction("com.android.settings.TTS_SETTINGS");
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    Toast.makeText(MainActivity.this, "Opening System TTS Settings...", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Cannot open settings", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
    }

    private void loadInstalledEngines() {
        Intent intent = new Intent("android.intent.action.TTS_SERVICE");
        List<ResolveInfo> resolveInfos = getPackageManager().queryIntentServices(intent, PackageManager.GET_META_DATA);
        engineNames.clear();
        enginePackages.clear();

        for (ResolveInfo info : resolveInfos) {
            String pkg = info.serviceInfo.packageName;
            if (!pkg.equals(getPackageName())) {
                String label = info.serviceInfo.loadLabel(getPackageManager()).toString();
                engineNames.add(label);
                enginePackages.add(pkg);
            }
        }

        if (engineNames.isEmpty()) {
            engineNames.add("No Engines Found");
            enginePackages.add("");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, engineNames);

        Spinner spShan = findViewById(R.id.spinnerShan);
        Spinner spBur = findViewById(R.id.spinnerBurmese);
        Spinner spEng = findViewById(R.id.spinnerEnglish);

        spShan.setAdapter(adapter);
        spBur.setAdapter(adapter);
        spEng.setAdapter(adapter);
    }

    private void setSpinnerSelection(Spinner spinner, final String key, String def) {
        String saved = prefs.getString(key, def);
        int idx = enginePackages.indexOf(saved);
        if (idx >= 0) spinner.setSelection(idx);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!enginePackages.isEmpty() && position >= 0) {
                    prefs.edit().putString(key, enginePackages.get(position)).apply();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
}

