package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class FeedbackSettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private TextView tvVibrateStrengthVal;
    private TextView tvSoundVolumeVal;

    private final String[] vibrateOptions = {"System Default", "Light", "Medium", "Strong"};
    private final String[] soundOptions = {"Low", "Normal", "High"};

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

        LinearLayout layoutVibrate = findViewById(R.id.layout_vibrate_strength);
        layoutVibrate.setOnClickListener(v -> showVibrateDialog());

        LinearLayout layoutSound = findViewById(R.id.layout_sound_volume);
        layoutSound.setOnClickListener(v -> showSoundDialog());
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
        int currentOption = prefs.getInt("vibrate_strength", 0);
        new AlertDialog.Builder(this)
            .setTitle("Vibration Strength")
            .setSingleChoiceItems(vibrateOptions, currentOption, (dialog, which) -> {
                prefs.edit().putInt("vibrate_strength", which).apply();
                updateVibrateText();
                dialog.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSoundDialog() {
        int currentOption = prefs.getInt("sound_volume", 1);
        new AlertDialog.Builder(this)
            .setTitle("Sound Volume")
            .setSingleChoiceItems(soundOptions, currentOption, (dialog, which) -> {
                prefs.edit().putInt("sound_volume", which).apply();
                updateSoundText();
                dialog.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void updateVibrateText() {
        int val = prefs.getInt("vibrate_strength", 0);
        if (val >= 0 && val < vibrateOptions.length) {
            tvVibrateStrengthVal.setText(vibrateOptions[val]);
        }
    }

    private void updateSoundText() {
        int val = prefs.getInt("sound_volume", 1);
        if (val >= 0 && val < soundOptions.length) {
            tvSoundVolumeVal.setText(soundOptions[val]);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
