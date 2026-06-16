package com.moneyreader.myanmar;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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
    private Button supportButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        tts = new TextToSpeech(this, this);
        
        statusText = findViewById(R.id.statusText);
        scanButton = findViewById(R.id.scanButton);
        settingsButton = findViewById(R.id.settingsButton);
        supportButton = findViewById(R.id.supportButton);

        scanButton.setOnClickListener(v -> checkCameraPermission());
        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        supportButton.setOnClickListener(v -> showDonationDialog());
    }

    private void showDonationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("💖 Support Our Project");
        
        String message = "MMK Scanner Talk အား အသုံးပြုပေးသည့်အတွက် ကျေးဇူးအထူးတင်ရှိပါတယ်။\n\n" +
                         "ဒီအက်ပ်လေးကို အမြင်အာရုံမသန်စွမ်းသူများ နေ့စဉ်ဘဝမှာ အဆင်ပြေစေရန်အတွက် အခမဲ့ ဖန်တီးပေးထားတာ ဖြစ်ပါတယ်။\n\n" +
                         "ဒီအက်ပ်လေးကို ဆက်လက်ထိန်းသိမ်းထားနိုင်ဖို့နဲ့ နောက်ပိုင်းမှာ ပိုမိုကောင်းမွန်တဲ့ နည်းပညာတွေ ထပ်မံဖန်တီးနိုင်ဖို့အတွက် သင့်အနေနဲ့ စေတနာအလျောက် ပါဝင်ကူညီ ပံ့ပိုးပေးနိုင်ပါတယ်။\n\n" +
                         "KBZPay / WavePay\n" +
                         "Sai naw - 09750091817";
                         
        builder.setMessage(message);
        builder.setPositiveButton("ပိတ်မည်", (dialog, which) -> dialog.dismiss());
        
        AlertDialog dialog = builder.create();
        dialog.show();
        
        if (tts != null) {
            tts.speak("Thank you for using MM K Scanner Talk. This app is free. If you find it helpful, you can support the developer. Phone number is shown on the screen.", TextToSpeech.QUEUE_FLUSH, null, "support");
        }
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
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
        }
    }

    private void openCamera() {
        Intent intent = new Intent(this, CameraActivity.class);
        startActivity(intent);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US);
            if (statusText != null) {
                statusText.setText("Ready");
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

