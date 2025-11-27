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
import android.widget.Switch;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);

        Button btnEnable = findViewById(R.id.btn_enable_keyboard);
        btnEnable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        });

        Button btnSelect = findViewById(R.id.btn_select_keyboard);
        btnSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputMethodManager imeManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imeManager != null) {
                    imeManager.showInputMethodPicker();
                } else {
                    Toast.makeText(SettingsActivity.this, "Error", Toast.LENGTH_SHORT).show();
                }
            }
        });

        setupSwitch(R.id.switch_vibrate, "vibrate_on", true);
        setupSwitch(R.id.switch_sound, "sound_on", false);
        setupSwitch(R.id.switch_theme, "dark_theme", false);
        setupSwitch(R.id.switch_number_row, "number_row", false);

        Button btnAbout = findViewById(R.id.btn_about);
        btnAbout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAboutDialog();
            }
        });
    }

    private void setupSwitch(int id, final String key, boolean def) {
        Switch s = findViewById(id);
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).apply();
            }
        });
    }

    private void showAboutDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("About Sai Naw Keyboard");
        builder.setMessage("Version: 1.0.0\n\n" +
                "Developed by: Sai Naw\n\n" +
                "This keyboard is dedicated to the Shan and Myanmar visually impaired communities.\n\n" +
                "Designed for seamless typing with Screen reader support.\n\n" +
                "Contact: sainaw1331@gmail.com");
        builder.setPositiveButton("OK", null);
        builder.show();
    }
}

