package com.cherry.sme.tts;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
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

                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }, 1500);

        setupDonation(R.id.btnKpay, "09750091817", "KBZ Pay Number Copied");
        setupDonation(R.id.btnWave, "09750091817", "Wave Pay Number Copied");

        setupOpenSystemSettings(R.id.spinnerShan);
        setupOpenSystemSettings(R.id.spinnerBurmese);
        setupOpenSystemSettings(R.id.spinnerEnglish);

        setupBatteryOptimization();
        setupAutoStart();
        updateBatteryStatus();
    }

    private void setupBatteryOptimization() {
        View btn = findViewById(R.id.btnBatteryOptimize);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBatteryOptimization();
            }
        });
    }

    private void requestIgnoreBatteryOptimization() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                LogCollector.addLog("BATTERY", "Requested ignore battery optimization");
            } else {
                Toast.makeText(this, "Battery optimization already disabled", Toast.LENGTH_SHORT).show();
                LogCollector.addLog("BATTERY", "Already ignoring battery optimization");
                openBatterySettings();
            }
        } catch (Exception e) {
            LogCollector.addError("BATTERY", "Failed to request battery optimization", e);
            openBatterySettings();
        }
    }

    private void openBatterySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
                startActivity(intent);
            } catch (Exception e2) {
                Toast.makeText(this, "Please manually disable battery optimization in Settings", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void setupAutoStart() {
        View btn = findViewById(R.id.btnAutoStart);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAutoStartSettings();
            }
        });
    }

    private void openAutoStartSettings() {
        boolean opened = false;
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        LogCollector.addLog("AUTOSTART", "Manufacturer: " + manufacturer);

        try {
            Intent intent = new Intent();
            if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                intent.setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                startActivity(intent);
                opened = true;
            } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                intent.setComponent(new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
                startActivity(intent);
                opened = true;
            } else if (manufacturer.contains("oppo")) {
                intent.setComponent(new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
                startActivity(intent);
                opened = true;
            } else if (manufacturer.contains("vivo")) {
                intent.setComponent(new ComponentName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
                startActivity(intent);
                opened = true;
            } else if (manufacturer.contains("realme")) {
                intent.setComponent(new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"));
                startActivity(intent);
                opened = true;
            } else if (manufacturer.contains("oneplus")) {
                intent.setComponent(new ComponentName("com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"));
                startActivity(intent);
                opened = true;
            } else if (manufacturer.contains("samsung")) {
                intent.setComponent(new ComponentName("com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"));
                startActivity(intent);
                opened = true;
            } else if (manufacturer.contains("asus")) {
                intent.setComponent(new ComponentName("com.asus.mobilemanager",
                        "com.asus.mobilemanager.autostart.AutoStartActivity"));
                startActivity(intent);
                opened = true;
            } else if (manufacturer.contains("lenovo")) {
                intent.setComponent(new ComponentName("com.lenovo.security",
                        "com.lenovo.security.purebackground.PureBackgroundActivity"));
                startActivity(intent);
                opened = true;
            } else if (manufacturer.contains("tecno") || manufacturer.contains("infinix") || manufacturer.contains("itel")) {
                intent.setComponent(new ComponentName("com.transsion.phonemanager",
                        "com.transsion.phonemanager.module.autostart.AutoStartActivity"));
                startActivity(intent);
                opened = true;
            }
        } catch (Exception e) {
            opened = false;
            LogCollector.addWarn("AUTOSTART", "Primary intent failed: " + e.getMessage());
        }

        if (!opened) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                startActivity(intent);
                opened = true;
            } catch (Exception e) {}
        }

        if (!opened) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"));
                startActivity(intent);
                opened = true;
            } catch (Exception e) {}
        }

        if (!opened) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                opened = true;
                Toast.makeText(this, "Please enable AutoStart/Background permission manually", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                LogCollector.addError("AUTOSTART", "Cannot open any settings", e);
            }
        }

        if (opened) {
            LogCollector.addLog("AUTOSTART", "Opened autostart settings for " + manufacturer);
            Toast.makeText(this, "Please enable AutoStart for Cherry SME TTS", Toast.LENGTH_LONG).show();
        }
    }

    private void updateBatteryStatus() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            TextView btnBattery = findViewById(R.id.btnBatteryOptimize);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                btnBattery.setText("Battery Optimization Disabled");
                btnBattery.setTextColor(0xFF388E3C);
            } else {
                btnBattery.setText("Disable Battery Optimization");
                btnBattery.setTextColor(0xFFE65100);
            }
        } catch (Exception e) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBatteryStatus();
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
