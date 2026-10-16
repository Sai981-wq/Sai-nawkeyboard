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
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private ArrayList<String> engineNames = new ArrayList<>();
    private ArrayList<String> enginePackages = new ArrayList<>();
    private ProgressDialog progressDialog;
    private RemoteTextToSpeech testTts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        setupSliders(R.id.seekRateShan, R.id.tvRateShan, "pref_rate_shan", "Rate: ", true);
        setupSliders(R.id.seekVolShan, R.id.tvVolShan, "pref_vol_shan", "Volume: ", false);

        setupSliders(R.id.seekRateBurmese, R.id.tvRateBurmese, "pref_rate_myanmar", "Rate: ", true);
        setupSliders(R.id.seekVolBurmese, R.id.tvVolBurmese, "pref_vol_myanmar", "Volume: ", false);

        setupSliders(R.id.seekRateEnglish, R.id.tvRateEnglish, "pref_rate_english", "Rate: ", true);
        setupSliders(R.id.seekVolEnglish, R.id.tvVolEnglish, "pref_vol_english", "Volume: ", false);

        setupTestButtons();

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

                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }, 1500);

        setupDonation(R.id.btnKpay, "09750091817", "KBZ Pay Number Copied");
        setupDonation(R.id.btnWave, "09750091817", "Wave Pay Number Copied");

        setupOpenSystemSettings(R.id.spinnerShan);
        setupOpenSystemSettings(R.id.spinnerBurmese);
        setupOpenSystemSettings(R.id.spinnerEnglish);
    }

    private void setupSliders(int seekId, int tvId, final String prefKey, final String labelPrefix, final boolean isRate) {
        SeekBar seekBar = findViewById(seekId);
        final TextView tv = findViewById(tvId);

        int savedVal = prefs.getInt(prefKey, 100);
        if (isRate && savedVal < 10) savedVal = 10;
        seekBar.setProgress(savedVal);
        tv.setText(labelPrefix + savedVal + "%");

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = progress;
                if (isRate && val < 10) {
                    val = 10;
                    sb.setProgress(10);
                }
                tv.setText(labelPrefix + val + "%");
                prefs.edit().putInt(prefKey, val).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void setupTestButtons() {
        Button btnTestShan = findViewById(R.id.btnTestShan);
        Button btnTestBurmese = findViewById(R.id.btnTestBurmese);
        Button btnTestEnglish = findViewById(R.id.btnTestEnglish);

        btnTestShan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                speakTest("SHAN", "မႂ်ႇသုင်ၶႃႈ", "pref_engine_shan", "pref_rate_shan", "pref_vol_shan");
            }
        });

        btnTestBurmese.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                speakTest("MYANMAR", "မင်္ဂလာပါ", "pref_engine_myanmar", "pref_rate_myanmar", "pref_vol_myanmar");
            }
        });

        btnTestEnglish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                speakTest("ENGLISH", "hello", "pref_engine_english", "pref_rate_english", "pref_vol_english");
            }
        });
    }

    private void speakTest(final String lang, final String sampleText, String engineKey, String rateKey, String volKey) {
        final String pkg = prefs.getString(engineKey, "");
        if (pkg == null || pkg.isEmpty()) {
            Toast.makeText(this, "No Engine Selected", Toast.LENGTH_SHORT).show();
            return;
        }

        final float rate = Math.max(10, prefs.getInt(rateKey, 100)) / 100.0f;
        final float volume = prefs.getInt(volKey, 100) / 100.0f;

        if (testTts != null) {
            try {
                testTts.stop();
                testTts.shutdown();
            } catch (Exception e) {}
            testTts = null;
        }

        testTts = new RemoteTextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS && testTts != null) {
                    try {
                        if ("MYANMAR".equals(lang)) {
                            int res = testTts.setLanguage(new Locale("mya"));
                            if (res < 0) res = testTts.setLanguage(new Locale("mya", "MM"));
                            if (res < 0) testTts.setLanguage(new Locale("my"));
                            try {
                                Set<Voice> voices = testTts.getVoices();
                                if (voices != null) {
                                    for (Voice v : voices) {
                                        String vName = v.getName().toLowerCase();
                                        if (vName.contains("my") || vName.contains("burmese") || vName.contains("mya")) {
                                            testTts.setVoice(v);
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception e) {}
                        } else if ("SHAN".equals(lang)) {
                            testTts.setLanguage(new Locale("shn"));
                        } else {
                            testTts.setLanguage(Locale.US);
                        }

                        testTts.setSpeechRate(rate);
                        testTts.setPitch(1.0f);

                        Bundle params = new Bundle();
                        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build();
                        params.putParcelable("audioAttributes", audioAttributes);
                        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);

                        testTts.speak(sampleText, TextToSpeech.QUEUE_FLUSH, params, "test_utt_" + System.nanoTime());
                    } catch (Exception e) {}
                }
            }
        }, pkg);
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
        if (idx >= 0) {
            spinner.setSelection(idx);
        } else if (!enginePackages.isEmpty()) {
            prefs.edit().putString(key, enginePackages.get(0)).apply();
        }

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

    @Override
    protected void onDestroy() {
        if (testTts != null) {
            try {
                testTts.stop();
                testTts.shutdown();
            } catch (Exception e) {}
            testTts = null;
        }
        super.onDestroy();
    }
}

