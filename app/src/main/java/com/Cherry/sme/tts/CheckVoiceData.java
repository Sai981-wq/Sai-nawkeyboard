package com.cherry.sme.tts;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;

public class CheckVoiceData extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String sysDef = Settings.Secure.getString(getContentResolver(), "tts_default_synth");
        String targetEngine = prefs.getString("pref_engine_english", sysDef);

        if (targetEngine == null || getPackageName().equals(targetEngine)) {
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL);
            finish();
            return;
        }

        try {
            Intent intent = new Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
            intent.setPackage(targetEngine);
            startActivityForResult(intent, 100);
        } catch (Exception e) {
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 100) {
            setResult(resultCode, data);
            finish();
        }
    }
}

