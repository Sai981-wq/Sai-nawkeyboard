package com.sainaw.mm.board;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);

        // Setup Buttons
        setupButton(R.id.btn_enable_keyboard, v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        
        setupButton(R.id.btn_select_keyboard, v -> {
            InputMethodManager imeManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imeManager != null) imeManager.showInputMethodPicker();
            else Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show();
        });

        // Sub-Menu Navigation
        setupButton(R.id.btn_languages, v -> startActivity(new Intent(this, LanguageSettingsActivity.class)));
        setupButton(R.id.btn_accessibility, v -> startActivity(new Intent(this, AccessibilitySettingsActivity.class)));
        setupButton(R.id.btn_user_dictionary, v -> startActivity(new Intent(this, UserDictionaryActivity.class)));
        setupButton(R.id.btn_about, v -> showAboutDialog());

        // General Switches
        setupSwitch(R.id.switch_vibrate, "vibrate_on", true);
        setupSwitch(R.id.switch_sound, "sound_on", false);
        setupSwitch(R.id.switch_theme, "dark_theme", false);
        setupSwitch(R.id.switch_number_row, "number_row", false);
    }

    private void setupButton(int id, android.view.View.OnClickListener listener) {
        Button btn = findViewById(id);
        if (btn != null) btn.setOnClickListener(listener);
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

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("About Sai Naw Keyboard")
                .setMessage("Created for visually impaired users.\nVersion 1.0")
                .setPositiveButton("OK", null)
                .show();
    }
}

