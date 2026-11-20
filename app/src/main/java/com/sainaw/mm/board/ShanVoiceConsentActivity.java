package com.sainaw.mm.board;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class ShanVoiceConsentActivity extends Activity {
    static final String PREFS = "ShanVoicePrivacy";
    static final String CONSENT = "consented";
    private static final String KEY_ASKED_BEFORE = "mic_permission_asked";
    private static final int REQ_RECORD_AUDIO = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CONSENT, true).apply();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }

        boolean askedBefore = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ASKED_BEFORE, false);
        boolean canShowPopup = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO);

        if (!askedBefore || canShowPopup) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ASKED_BEFORE, true).apply();
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        } else {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        finish();
    }
}

