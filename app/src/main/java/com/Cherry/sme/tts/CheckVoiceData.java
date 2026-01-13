package com.cherry.sme.tts;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import java.util.ArrayList;

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
        String sysDef = Settings.Secure.getString(getContentResolver(), "tts_default_synth");

        engPkg = prefs.getString("pref_engine_english", sysDef);
        shanPkg = prefs.getString("pref_engine_shan", sysDef);
        burmesePkg = prefs.getString("pref_engine_myanmar", sysDef);

        checkEngine(engPkg, 101);
    }

    private void checkEngine(String pkgName, int reqCode) {
        if (pkgName == null || pkgName.isEmpty() || pkgName.equals(getPackageName())) {
            onActivityResult(reqCode, Activity.RESULT_OK, null);
            return;
        }
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
            ArrayList<String> unavailable = data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES);

            if (available != null) {
                for (String lang : available) {
                    if (!finalAvailable.contains(lang)) finalAvailable.add(lang);
                }
            }
            if (unavailable != null) {
                for (String lang : unavailable) {
                    if (!finalUnavailable.contains(lang)) finalUnavailable.add(lang);
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

