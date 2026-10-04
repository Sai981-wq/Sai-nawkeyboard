package com.mmkscanner.talk;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
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
        tts = new TextToSpeech(this, this);

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

        backButton.setOnClickListener(v -> finish());

        ttsEngineButton.setOnClickListener(v -> {
            try {
                Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
                startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

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

