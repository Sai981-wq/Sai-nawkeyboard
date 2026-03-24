package com.sainaw.mm.board;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class FeedbackSettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private TextView tvVibrateStrengthVal;
    private TextView tvSoundVolumeVal;
    private final String[] vibrateOptions = {"System Default", "Light", "Medium", "Strong"};
    private final String[] soundOptions = {"Low", "Normal", "High"};

    private final ActivityResultLauncher<Intent> zipPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        boolean success = SaiNawZipHelper.extractZip(this, uri, "custom_sounds");
                        if (success) {
                            prefs.edit().putBoolean("use_custom_sounds", true).apply();
                            Toast.makeText(this, "Custom Sounds Imported!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Failed to extract ZIP", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Sound & Vibration");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        prefs = getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);

        tvVibrateStrengthVal = findViewById(R.id.tv_vibrate_strength_val);
        tvSoundVolumeVal = findViewById(R.id.tv_sound_volume_val);

        setupSwitch(R.id.switch_vibrate, "vibrate_on", true);
        setupSwitch(R.id.switch_sound, "sound_on", false);

        updateVibrateText();
        updateSoundText();

        findViewById(R.id.layout_vibrate_strength).setOnClickListener(v -> showVibrateDialog());
        findViewById(R.id.layout_sound_volume).setOnClickListener(v -> showSoundDialog());

        findViewById(R.id.btn_import_sound_zip).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/zip");
            zipPickerLauncher.launch(intent);
        });

        findViewById(R.id.btn_reset_sound).setOnClickListener(v -> {
            prefs.edit().putBoolean("use_custom_sounds", false).apply();
            Toast.makeText(this, "Reset to Default Sounds", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupSwitch(int id, final String key, boolean def) {
        SwitchCompat s = findViewById(id);
        if (s != null) {
            s.setChecked(prefs.getBoolean(key, def));
            s.setOnCheckedChangeListener((buttonView, isChecked) -> 
                prefs.edit().putBoolean(key, isChecked).apply()
            );
        }
    }

    private void showVibrateDialog() {
        int current = prefs.getInt("vibrate_strength", 0);
        new AlertDialog.Builder(this)
            .setTitle("Vibration Strength")
            .setSingleChoiceItems(vibrateOptions, current, (dialog, which) -> {
                prefs.edit().putInt("vibrate_strength", which).apply();
                updateVibrateText();
                dialog.dismiss();
            }).show();
    }

    private void showSoundDialog() {
        int current = prefs.getInt("sound_volume", 1);
        new AlertDialog.Builder(this)
            .setTitle("Sound Volume")
            .setSingleChoiceItems(soundOptions, current, (dialog, which) -> {
                prefs.edit().putInt("sound_volume", which).apply();
                updateSoundText();
                dialog.dismiss();
            }).show();
    }

    private void updateVibrateText() {
        tvVibrateStrengthVal.setText(vibrateOptions[prefs.getInt("vibrate_strength", 0)]);
    }

    private void updateSoundText() {
        tvSoundVolumeVal.setText(soundOptions[prefs.getInt("sound_volume", 1)]);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}

