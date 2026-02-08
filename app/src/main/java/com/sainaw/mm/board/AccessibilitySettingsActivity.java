package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class AccessibilitySettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accessibility_settings);
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Accessibility");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        prefs = getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);

        // 1. Lift-to-Type
        setupSwitch(R.id.switch_typing_mode, "lift_to_type", true);

        // 2. Phonetic Sounds (အသစ်ထည့်သော Switch)
        // Default = true (ဖွင့်ထားမယ်)
        setupSwitch(R.id.switch_phonetic_sounds, "use_phonetic_sounds", true);

        // 3. Smart Echo
        setupSwitch(R.id.switch_smart_echo, "smart_echo", false);
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
    
    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}

