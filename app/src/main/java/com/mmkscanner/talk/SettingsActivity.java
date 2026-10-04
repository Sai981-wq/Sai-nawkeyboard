package com.mmkscanner.talk;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private SeekBar speedSeekbar;
    private SeekBar volumeSeekbar;
    private Switch languageSwitch;
    private Switch autoFlashSwitch;
    private Switch autoSpeakSwitch;
    private Switch vibrationSwitch;
    private Button testVoiceButton;
    private Button ttsEngineButton;
    private Button backButton;
    private TextView speedValue;
    private TextView volumeValue;
    private TextToSpeech tts;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
        
        speedSeekbar = findViewById(R.id.speedSeekbar);
        volumeSeekbar = findViewById(R.id.volumeSeekbar);
        languageSwitch = findViewById(R.id.languageSwitch);
        autoFlashSwitch = findViewById(R.id.autoFlashSwitch);
        autoSpeakSwitch = findViewById(R.id.autoSpeakSwitch);
        vibrationSwitch = findViewById(R.id.vibrationSwitch);
        testVoiceButton = findViewById(R.id.testVoiceButton);
        ttsEngineButton = findViewById(R.id.ttsEngineButton);
        backButton = findViewById(R.id.backButton);
        speedValue = findViewById(R.id.speedValue);
        volumeValue = findViewById(R.id.volumeValue);

        loadSettings();
        initTTS();

        backButton.setOnClickListener(v -> finish());

        ttsEngineButton.setOnClickListener(v -> showTTSEngineDialog());

        testVoiceButton.setOnClickListener(v -> {
            if (tts != null) {
                Bundle params = new Bundle();
                float volume = prefs.getInt("volume", 80) / 100f;
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
                boolean useMM = prefs.getBoolean("use_myanmar", false);
                String text = useMM ? "အသံစမ်းသပ်နေပါသည်" : "Testing voice";
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "test");
            }
        });

        speedSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speedValue.setText(progress + "%");
                prefs.edit().putInt("speed", progress).apply();
                if (tts != null) tts.setSpeechRate(progress / 50f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        volumeSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeValue.setText(progress + "%");
                prefs.edit().putInt("volume", progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        languageSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("use_myanmar", isChecked).apply();
            if (tts != null) {
                Locale targetLocale = isChecked ? new Locale("my", "MM") : Locale.US;
                tts.setLanguage(targetLocale);
            }
        });

        autoFlashSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("auto_flash", isChecked).apply());

        autoSpeakSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> 
            prefs.edit().putBoolean("auto_speak", isChecked).apply());

        vibrationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> 
            prefs.edit().putBoolean("vibration", isChecked).apply());
    }

    private void initTTS() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        String savedEngine = prefs.getString("tts_engine", null);
        if (savedEngine != null) {
            tts = new TextToSpeech(this, this, savedEngine);
        } else {
            tts = new TextToSpeech(this, this);
        }
    }

    private void showTTSEngineDialog() {
        if (tts == null) return;

        List<TextToSpeech.EngineInfo> engines = tts.getEngines();
        if (engines == null || engines.isEmpty()) {
            Toast.makeText(this, "No TTS engines found", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] engineNames = new String[engines.size()];
        String[] enginePackages = new String[engines.size()];
        String currentEngine = prefs.getString("tts_engine", tts.getDefaultEngine());
        int checkedItem = -1;

        for (int i = 0; i < engines.size(); i++) {
            TextToSpeech.EngineInfo info = engines.get(i);
            engineNames[i] = info.label;
            enginePackages[i] = info.name;
            if (info.name.equals(currentEngine)) {
                checkedItem = i;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select TTS Engine");
        builder.setSingleChoiceItems(engineNames, checkedItem, (dialog, which) -> {
            String selectedPackage = enginePackages[which];
            prefs.edit().putString("tts_engine", selectedPackage).apply();
            initTTS(); 
            dialog.dismiss();
            Toast.makeText(SettingsActivity.this, "Engine set to: " + engineNames[which], Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void loadSettings() {
        int speed = prefs.getInt("speed", 50);
        int volume = prefs.getInt("volume", 80);
        boolean useMyanmar = prefs.getBoolean("use_myanmar", false);
        boolean autoFlash = prefs.getBoolean("auto_flash", false);
        boolean autoSpeak = prefs.getBoolean("auto_speak", true);
        boolean vibration = prefs.getBoolean("vibration", true);

        speedSeekbar.setProgress(speed);
        volumeSeekbar.setProgress(volume);
        speedValue.setText(speed + "%");
        volumeValue.setText(volume + "%");
        languageSwitch.setChecked(useMyanmar);
        autoFlashSwitch.setChecked(autoFlash);
        autoSpeakSwitch.setChecked(autoSpeak);
        vibrationSwitch.setChecked(vibration);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            boolean useMM = prefs.getBoolean("use_myanmar", false);
            Locale targetLocale = useMM ? new Locale("my", "MM") : Locale.US;
            int result = tts.isLanguageAvailable(targetLocale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US);
            } else {
                tts.setLanguage(targetLocale);
            }
            tts.setSpeechRate(prefs.getInt("speed", 50) / 50f);
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}

