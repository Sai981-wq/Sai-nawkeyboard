package com.moneyreader.myanmar;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final int CAMERA_PERMISSION_CODE = 100;
    private TextToSpeech tts;
    private TextView statusText;
    private Button scanButton;
    private Button settingsButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tts = new TextToSpeech(this, this);
        
        statusText = findViewById(R.id.statusText);
        scanButton = findViewById(R.id.scanButton);
        settingsButton = findViewById(R.id.settingsButton);

        scanButton.setOnClickListener(v -> checkCameraPermission());
        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            Toast.makeText(this, "ကင်မရာ ခွင့်ပြုချက် လိုအပ်ပါသည်", Toast.LENGTH_SHORT).show();
        }
    }

    private void openCamera() {
        Intent intent = new Intent(this, CameraActivity.class);
        startActivityForResult(intent, 200);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 200 && resultCode == RESULT_OK && data != null) {
            String result = data.getStringExtra("detected_value");
            if (result != null && !result.isEmpty()) {
                speakResult(result);
            }
        }
    }

    private void speakResult(String value) {
        String myanmarText = convertToMyanmar(value);
        if (tts != null) {
            tts.speak(myanmarText, TextToSpeech.QUEUE_FLUSH, null, "money_result");
        }
    }

    private String convertToMyanmar(String value) {
        switch (value) {
            case "50": return "ငါးဆယ်ကျပ်";
            case "100": return "တစ်ရာကျပ်";
            case "200": return "နှစ်ရာကျပ်";
            case "500": return "ငါးရာကျပ်";
            case "1000": return "တစ်ထောင်ကျပ်";
            case "5000": return "ငါးထောင်ကျပ်";
            case "10000": return "တစ်သောင်းကျပ်";
            case "20000": return "နှစ်သောင်းကျပ်";
            default: return value + " ကျပ်";
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            Locale myanmarLocale = new Locale("my", "MM");
            if (tts.isLanguageAvailable(myanmarLocale) >= TextToSpeech.LANG_AVAILABLE) {
                tts.setLanguage(myanmarLocale);
            }
            if (statusText != null) {
                statusText.setText("အသင့်ဖြစ်ပါပြီ");
            }
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

