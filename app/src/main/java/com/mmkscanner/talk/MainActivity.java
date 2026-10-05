package com.mmkscanner.talk;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.common.InputImage;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback, Camera.PreviewCallback, TextToSpeech.OnInitListener {

    private static final int CAMERA_PERMISSION_CODE = 100;
    private Camera camera;
    private SurfaceView surfaceView;
    private TextView resultText;
    private Button flashlightButton;
    private BanknoteClassifier classifier;
    private TextToSpeech tts;
    private Handler handler;
    private Vibrator vibrator;
    private ExecutorService cameraExecutor;
    
    private boolean isProcessing = false;
    private boolean isFlashlightOn = false;
    private String lastStableDetection = "";
    private int detectionCount = 0;
    private long lastSpeakTime = 0;
    private long lastDirSpeakTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        surfaceView = findViewById(R.id.surfaceView);
        resultText = findViewById(R.id.resultText);
        flashlightButton = findViewById(R.id.flashlightButton);

        handler = new Handler(Looper.getMainLooper());
        cameraExecutor = Executors.newSingleThreadExecutor();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        classifier = new BanknoteClassifier(this);

        initTTS();

        flashlightButton.setOnClickListener(v -> toggleFlashlight());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            surfaceView.getHolder().addCallback(this);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }
    
    private void initTTS() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
        String savedEngine = prefs.getString("tts_engine", null);
        if (savedEngine != null) {
            tts = new TextToSpeech(this, this, savedEngine);
        } else {
            tts = new TextToSpeech(this, this);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_support) {
            showDonationDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showDonationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Support Our Project");
        
        String message = "MMK Scanner Talk အား အသုံးပြုပေးသည့်အတွက် ကျေးဇူးအထူးတင်ရှိပါတယ်။\n\n" +
                         "ဒီအက်ပ်လေးကို အမြင်အာရုံမသန်စွမ်းသူများ နေ့စဉ်ဘဝမှာ အဆင်ပြေစေရန်အတွက် အခမဲ့ ဖန်တီးပေးထားတာ ဖြစ်ပါတယ်။\n\n" +
                         "ဒီအက်ပ်လေးကို ဆက်လက်ထိန်းသိမ်းထားနိုင်ဖို့နဲ့ နောက်ပိုင်းမှာ ပိုမိုကောင်းမွန်တဲ့ နည်းပညာတွေ ထပ်မံဖန်တီးနိုင်ဖို့အတွက် သင့်အနေနဲ့ စေတနာအလျောက် ပါဝင်ကူညီ ပံ့ပိုးပေးနိုင်ပါတယ်။\n\n" +
                         "KBZPay / WavePay\n" +
                         "Sai naw - 09750091817";
                         
        builder.setMessage(message);
        builder.setPositiveButton("ပိတ်မည်", (dialog, which) -> dialog.dismiss());
        builder.setNeutralButton("ကူးယူမည်", (dialog, which) -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Donation Number", "09750091817");
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        
        AlertDialog dialog = builder.create();
        dialog.show();
        
        if (tts != null) {
            Bundle params = getTtsParams();
            SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
            boolean useMM = prefs.getBoolean("use_myanmar", false);
            String speakText = useMM ? "အက်ပ်ကို အသုံးပြုပေးသည့်အတွက် ကျေးဇူးတင်ပါသည်။ ဖုန်းနံပါတ်ကို မျက်နှာပြင်တွင် ဖော်ပြထားပါသည်။" : "Thank you for using MM K Scanner Talk. This app is free. If you find it helpful, you can support the developer. Phone number is shown on the screen.";
            tts.speak(speakText, TextToSpeech.QUEUE_FLUSH, params, "support");
        }
    }

    private void setFlashlight(boolean turnOn) {
        if (camera != null && isFlashlightOn != turnOn) {
            try {
                Camera.Parameters params = camera.getParameters();
                if (params.getSupportedFlashModes() != null) {
                    SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
                    boolean useMM = prefs.getBoolean("use_myanmar", false);
                    if (turnOn) {
                        params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                        isFlashlightOn = true;
                        handler.post(() -> flashlightButton.setText("Flash On"));
                        if (tts != null) tts.speak(useMM ? "ဓာတ်မီး ဖွင့်လိုက်ပါပြီ" : "Flashlight on", TextToSpeech.QUEUE_FLUSH, getTtsParams(), "flash_on");
                    } else {
                        params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                        isFlashlightOn = false;
                        handler.post(() -> flashlightButton.setText("Flash Off"));
                        if (tts != null) tts.speak(useMM ? "ဓာတ်မီး ပိတ်လိုက်ပါပြီ" : "Flashlight off", TextToSpeech.QUEUE_FLUSH, getTtsParams(), "flash_off");
                    }
                    camera.setParameters(params);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void toggleFlashlight() {
        setFlashlight(!isFlashlightOn);
    }

    private void showErrorScreen(String errorMsg) {
        resultText.setText("Error: " + errorMsg);
        resultText.setTextColor(Color.RED);
        resultText.setTextSize(14f);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            camera = Camera.open(0);
            if (camera == null) {
                showErrorScreen("Camera not found");
                return;
            }
            Camera.Parameters params = camera.getParameters();
            if (params.getSupportedFocusModes() != null && params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            if (params.getSupportedFlashModes() != null) {
                isFlashlightOn = false;
                params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                handler.post(() -> flashlightButton.setText("Flash Off"));
            }
            camera.setParameters(params);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Throwable t) {
            showErrorScreen(t.toString());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (isProcessing) return;

        SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
        if (prefs.getBoolean("auto_flash", false) && !isFlashlightOn) {
            Camera.Size size = camera.getParameters().getPreviewSize();
            int sum = 0;
            int frameSize = size.width * size.height;
            for (int i = 0; i < frameSize; i += 100) {
                sum += (data[i] & 0xFF);
            }
            int avgLuminance = sum / (frameSize / 100);
            if (avgLuminance < 40) {
                handler.post(() -> setFlashlight(true));
            }
        }

        isProcessing = true;
        cameraExecutor.execute(() -> {
            try {
                Camera.Size size = camera.getParameters().getPreviewSize();
                InputImage image = InputImage.fromByteArray(
                        data,
                        size.width,
                        size.height,
                        90,
                        InputImage.IMAGE_FORMAT_NV21
                );
                
                classifier.classify(image, (result, direction) -> {
                    handler.post(() -> {
                        processDetection(result, direction);
                        isProcessing = false;
                    });
                });
            } catch (Throwable t) {
                handler.post(() -> isProcessing = false);
            }
        });
    }

    private void processDetection(String result, String direction) {
        if (result == null) return;
        SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
        boolean useMM = prefs.getBoolean("use_myanmar", false);

        if (!direction.isEmpty() && (result.equals("partial") || result.equals("unknown"))) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastDirSpeakTime > 2500) {
                String speakDir = direction;
                if (useMM) {
                    switch (direction) {
                        case "Left": speakDir = "ဘယ်ဘက်ရွှေ့ပါ"; break;
                        case "Right": speakDir = "ညာဘက်ရွှေ့ပါ"; break;
                        case "Up": speakDir = "အပေါ်ရွှေ့ပါ"; break;
                        case "Down": speakDir = "အောက်ရွှေ့ပါ"; break;
                    }
                } else {
                    speakDir = "Move " + direction;
                }
                
                if (tts != null) tts.speak(speakDir, TextToSpeech.QUEUE_FLUSH, getTtsParams(), "dir");
                lastDirSpeakTime = currentTime;
            }
            resultText.setText(useMM ? "ခဏငြိမ်ထားပါ..." : "Aligning...");
            return;
        }

        if (result.equals("partial")) {
            resultText.setText(useMM ? "ရှာဖွေနေသည်..." : "Searching...");
            return;
        }

        if (!result.equals("unknown")) {
            if (!result.equals(lastStableDetection)) {
                lastStableDetection = result;
                detectionCount = 1;
                lastSpeakTime = 0; 
            } else {
                detectionCount++;
            }
            
            int requiredCount = (result.equals("10000") || result.equals("5000")) ? 2 : 3;

            if (detectionCount >= requiredCount) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastSpeakTime > 2500) {
                    resultText.setText(useMM ? result + " ကျပ်" : result + " Kyats");
                    speakDetection(result);
                    triggerVibration();
                    lastSpeakTime = currentTime;
                }
            }
        } else {
            resultText.setText("");
            detectionCount = 0;
            lastStableDetection = "";
        }
    }

    private void triggerVibration() {
        SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
        if (prefs.getBoolean("vibration", true) && vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(150);
            }
        }
    }

    private Bundle getTtsParams() {
        Bundle params = new Bundle();
        SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
        float volume = prefs.getInt("volume", 80) / 100f;
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
        return params;
    }

    private void speakDetection(String value) {
        if (tts != null) {
            String text = getSpokenText(value);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, getTtsParams(), "detection");
        }
    }

    private String getSpokenText(String value) {
        SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
        boolean useMM = prefs.getBoolean("use_myanmar", false);
        
        if (useMM) {
            switch (value) {
                case "50": return "ငါးဆယ် ကျပ်";
                case "100": return "တစ်ရာ ကျပ်";
                case "200": return "နှစ်ရာ ကျပ်";
                case "500": return "ငါးရာ ကျပ်";
                case "1000": return "တစ်ထောင် ကျပ်";
                case "5000": return "ငါးထောင် ကျပ်";
                case "10000": return "တစ်သောင်း ကျပ်";
                case "20000": return "နှစ်သောင်း ကျပ်";
                default: return value + " ကျပ်";
            }
        } else {
            switch (value) {
                case "50": return "Fifty Kyats";
                case "100": return "One Hundred Kyats";
                case "200": return "Two Hundred Kyats";
                case "500": return "Five Hundred Kyats";
                case "1000": return "One Thousand Kyats";
                case "5000": return "Five Thousand Kyats";
                case "10000": return "Ten Thousand Kyats";
                case "20000": return "Twenty Thousand Kyats";
                default: return value + " Kyats";
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            surfaceView.getHolder().addCallback(this);
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
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

    private void releaseCamera() {
        try {
            if (camera != null) {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
                camera = null;
            }
        } catch (Exception e) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tts != null) {
            SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
            tts.setSpeechRate(prefs.getInt("speed", 50) / 50f);
            
            boolean useMM = prefs.getBoolean("use_myanmar", false);
            Locale targetLocale = useMM ? new Locale("my", "MM") : Locale.US;
            tts.setLanguage(targetLocale);
            
            String savedEngine = prefs.getString("tts_engine", null);
            if (savedEngine != null && !savedEngine.equals(tts.getDefaultEngine())) {
                 initTTS();
            }
        } else {
             initTTS();
        }
    }

    @Override
    protected void onDestroy() {
        releaseCamera();
        if (classifier != null) classifier.close();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (cameraExecutor != null) { cameraExecutor.shutdown(); }
        super.onDestroy();
    }
}

