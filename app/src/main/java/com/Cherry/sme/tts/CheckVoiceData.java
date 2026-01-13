package com.cherry.sme.tts;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import java.util.ArrayList;
import java.util.List;

public class CheckVoiceData extends Activity {

    private ArrayList<String> finalAvailable = new ArrayList<>();
    private ArrayList<String> finalUnavailable = new ArrayList<>();
    
    private String engPkg;
    private String shanPkg;
    private String burmesePkg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        engPkg = getValidEngine(prefs, "pref_engine_english");
        shanPkg = getValidEngine(prefs, "pref_engine_shan");
        burmesePkg = getValidEngine(prefs, "pref_engine_myanmar");

        checkEngine(engPkg, 101);
    }

    private String getValidEngine(SharedPreferences prefs, String key) {
        String pkg = prefs.getString(key, null);
        if (pkg != null && !pkg.isEmpty() && !pkg.equals(getPackageName())) return pkg;
        
        String sysDef = Settings.Secure.getString(getContentResolver(), "tts_default_synth");
        if (sysDef != null && !sysDef.equals(getPackageName())) return sysDef;

        try {
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            List<ResolveInfo> services = getPackageManager().queryIntentServices(intent, 0);
            for (ResolveInfo info : services) {
                String p = info.serviceInfo.packageName;
                if (!p.equals(getPackageName())) return p;
            }
        } catch (Exception e) {}
        
        return "com.google.android.tts";
    }

    private void checkEngine(String pkgName, int reqCode) {
        try {
            Intent intent = new Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
            intent.setPackage(pkgName);
            startActivityForResult(intent, reqCode);
        } catch (Exception e) {
            onActivityResult(reqCode, Activity.RESULT_CANCELED, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (data != null) {
            ArrayList<String> available = data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES);

            if (available != null) {
                for (String lang : available) {
                    if (lang.startsWith("shn")) {
                        lang = "shn";
                    } else if (lang.startsWith("my")) {
                        lang = "my";
                    }
                    
                    if (!finalAvailable.contains(lang)) {
                        finalAvailable.add(lang);
                    }
                }
            }
        }

        if (requestCode == 101) {
            checkEngine(shanPkg, 102);
        } else if (requestCode == 102) {
            checkEngine(burmesePkg, 103);
        } else if (requestCode == 103) {
            Intent resultData = new Intent();
            resultData.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, finalAvailable);
            resultData.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, finalUnavailable);
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, resultData);
            finish();
        }
    }
}

