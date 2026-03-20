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
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private ArrayList<String> engineNames = new ArrayList<>();
    private ArrayList<String> enginePackages = new ArrayList<>();
    private ProgressDialog progressDialog;
    private TextToSpeech testTts;

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

                setupEngineControls("SHAN", R.id.seekVolShan, R.id.seekSpeedShan, R.id.btnTestShan, "pref_engine_shan", "pref_vol_shan", "pref_speed_shan");
                setupEngineControls("MYANMAR", R.id.seekVolBurmese, R.id.seekSpeedBurmese, R.id.btnTestBurmese, "pref_engine_myanmar", "pref_vol_myanmar", "pref_speed_myanmar");
                setupEngineControls("ENGLISH", R.id.seekVolEnglish, R.id.seekSpeedEnglish, R.id.btnTestEnglish, "pref_engine_english", "pref_vol_english", "pref_speed_english");

                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }, 1500);

        setupDonation(R.id.btnKpay, "09750091817", "KBZ Pay Number Copied");
        setupDonation(R.id.btnWave, "09750091817", "Wave Pay Number Copied");

        setupOpenSystemSettings(R.id.spinnerShan);
        setupOpenSystemSettings(R.id.spinnerBurmese);
        setupOpenSystemSettings(R.id.spinnerEnglish);
    }

    private void showScanDialog() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

        if (viewId == R.id.btnKpay) {
            btn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    try {
                        startActivity(new Intent(MainActivity.this, LogViewerActivity.class));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Error opening logs", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            });
        }
    }

    private void setupEngineUI(int spinnerId, String pkgKey, String defPkg) {
        Spinner spinner = findViewById(spinnerId);
        setSpinnerSelection(spinner, pkgKey, defPkg);
    }

    private void setupOpenSystemSettings(int viewId) {
        View view = findViewById(viewId);
        if (view == null) return;
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

        if (spShan != null) spShan.setAdapter(adapter);
        if (spBur != null) spBur.setAdapter(adapter);
        if (spEng != null) spEng.setAdapter(adapter);
    }

    private void setSpinnerSelection(Spinner spinner, final String key, String def) {
        if (spinner == null) return;
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

    private void setupEngineControls(final String lang, int volId, int speedId, int btnId, final String pkgKey, final String volKey, final String speedKey) {
        final SeekBar seekVol = findViewById(volId);
        final SeekBar seekSpeed = findViewById(speedId);
        Button btnTest = findViewById(btnId);

        if (seekVol == null || seekSpeed == null || btnTest == null) return;

        seekVol.setMax(100);
        seekSpeed.setMax(100);
        seekVol.setProgress(prefs.getInt(volKey, 100));
        seekSpeed.setProgress(prefs.getInt(speedKey, 50));

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (seekBar == seekVol) prefs.edit().putInt(volKey, seekVol.getProgress()).apply();
                if (seekBar == seekSpeed) prefs.edit().putInt(speedKey, seekSpeed.getProgress()).apply();
            }
        };

        seekVol.setOnSeekBarChangeListener(listener);
        seekSpeed.setOnSeekBarChangeListener(listener);

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pkg = prefs.getString(pkgKey, "");
                if (pkg.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please select an engine first", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (testTts != null) {
                    try { testTts.stop(); testTts.shutdown(); } catch (Exception e) {}
                }
                testTts = new TextToSpeech(MainActivity.this, new TextToSpeech.OnInitListener() {
                    @Override
                    public void onInit(int status) {
                        if (status == TextToSpeech.SUCCESS) {
                            float spd = seekSpeed.getProgress() / 50.0f;
                            testTts.setSpeechRate(spd);
                            Bundle p = new Bundle();
                            p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, seekVol.getProgress() / 100.0f);
                            String text = "Hello";
                            if (lang.equals("SHAN")) text = "မႂ်ႇသုင်ၶႃႈ";
                            else if (lang.equals("MYANMAR")) text = "မင်္ဂလာပါ";
                            testTts.speak(text, TextToSpeech.QUEUE_FLUSH, p, "test_utt");
                        }
                    }
                }, pkg);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (testTts != null) {
            try { testTts.shutdown(); } catch (Exception e) {}
        }
        super.onDestroy();
    }
}

