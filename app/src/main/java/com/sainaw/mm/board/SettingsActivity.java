package com.sainaw.mm.board;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog; // Use AndroidX Dialog
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat; // Use SwitchCompat

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);

        // Enable Keyboard Button
        Button btnEnable = findViewById(R.id.btn_enable_keyboard);
        btnEnable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));

        // Select Keyboard Button
        Button btnSelect = findViewById(R.id.btn_select_keyboard);
        btnSelect.setOnClickListener(v -> {
            InputMethodManager imeManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imeManager != null) {
                imeManager.showInputMethodPicker();
            } else {
                Toast.makeText(SettingsActivity.this, R.string.error_toast, Toast.LENGTH_SHORT).show();
            }
        });

        // Settings Switches
        setupSwitch(R.id.switch_vibrate, "vibrate_on", true);
        setupSwitch(R.id.switch_sound, "sound_on", false);
        setupSwitch(R.id.switch_theme, "dark_theme", false);
        setupSwitch(R.id.switch_number_row, "number_row", false);

        // About Button
        Button btnAbout = findViewById(R.id.btn_about);
        btnAbout.setOnClickListener(v -> showAboutDialog());
    }

    // Helper method to setup switches concisely
    private void setupSwitch(int id, final String key, boolean def) {
        // Changed to SwitchCompat for better compatibility
        SwitchCompat s = findViewById(id); 
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener((buttonView, isChecked) -> 
            prefs.edit().putBoolean(key, isChecked).apply()
        );
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(R.string.btn_ok, null)
                .show();
    }
}

