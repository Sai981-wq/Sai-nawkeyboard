package com.moneyreader.myanmar;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private SeekBar speedSeekbar;
    private SeekBar volumeSeekbar;
    private Switch autoSpeakSwitch;
    private Switch vibrationSwitch;
    private Button testVoiceButton;
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
        autoSpeakSwitch = findViewById(R.id.autoSpeakSwitch);
        vibrationSwitch = findViewById(R.id.vibrationSwitch);
        testVoiceButton = findViewById(R.id.testVoiceButton);
        backButton = findViewById(R.id.backButton);
        speedValue = findViewById(R.id.speedValue);
        volumeValue = findViewById(R.id.volumeValue);

        int savedSpeed = prefs.getInt("tts_speed", 50);
        int savedVolume = prefs.getInt("tts_volume", 80);
        boolean autoSpeak = prefs.getBoolean("auto_speak", true);
        boolean vibration = prefs.getBoolean("vibration", true);

        speedSeekbar.setProgress(savedSpeed);
        volumeSeekbar.setProgress(savedVolume);
        autoSpeakSwitch.setChecked(autoSpeak);
        vibrationSwitch.setChecked(vibration);
        speedValue.setText(savedSpeed + "%");
        volumeValue.setText(savedVolume + "%");

        speedSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speedValue.setText(progress + "%");
                prefs.edit().putInt("tts_speed", progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        volumeSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeValue.setText(progress + "%");
                prefs.edit().putInt("tts_volume", progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        autoSpeakSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
            prefs.edit().putBoolean("auto_speak", isChecked).apply());

        vibrationSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
            prefs.edit().putBoolean("vibration", isChecked).apply());

        testVoiceButton.setOnClickListener(v -> testVoice());
        backButton.setOnClickListener(v -> finish());
    }

    private void testVoice() {
        if (tts != null) {
            float speed = speedSeekbar.getProgress() / 50.0f;
            if (speed < 0.1f) speed = 0.1f;
            tts.setSpeechRate(speed);
            tts.speak("တစ်သောင်းကျပ်", TextToSpeech.QUEUE_FLUSH, null, "test");
            Toast.makeText(this, "အသံစမ်းသပ်နေပါသည်", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("my", "MM"));
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
