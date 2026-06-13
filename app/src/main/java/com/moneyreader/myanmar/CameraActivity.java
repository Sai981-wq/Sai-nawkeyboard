package com.moneyreader.myanmar;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

public class CameraActivity extends AppCompatActivity implements SurfaceHolder.Callback, Camera.PreviewCallback, TextToSpeech.OnInitListener {

    private Camera camera;
    private SurfaceView surfaceView;
    private TextView resultText;
    private Button backButton;
    private Button flashlightButton;
    private BanknoteClassifier classifier;
    private TextToSpeech tts;
    private Handler handler;
    private Vibrator vibrator;
    private boolean isProcessing = false;
    private boolean isFlashlightOn = false;
    private String lastStableDetection = "";
    private int detectionCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_camera);

            surfaceView = findViewById(R.id.surfaceView);
            resultText = findViewById(R.id.resultText);
            backButton = findViewById(R.id.backButton);
            flashlightButton = findViewById(R.id.flashlightButton);
            
            handler = new Handler(Looper.getMainLooper());
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

            tts = new TextToSpeech(this, this);
            classifier = new BanknoteClassifier(this);

            surfaceView.getHolder().addCallback(this);

            backButton.setOnClickListener(v -> finish());
            
            if (flashlightButton != null) {
                flashlightButton.setOnClickListener(v -> toggleFlashlight());
            }

        } catch (Throwable t) {
            showErrorScreen(t.toString());
        }
    }

    private void toggleFlashlight() {
        if (camera != null) {
            Camera.Parameters params = camera.getParameters();
            if (params.getSupportedFlashModes() != null) {
                if (isFlashlightOn) {
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                    isFlashlightOn = false;
                    if (flashlightButton != null) {
                        flashlightButton.setText("💡 မီးဖွင့်ရန်");
                    }
                    if (tts != null) {
                        tts.speak("မီးပိတ်လိုက်ပါပြီ", TextToSpeech.QUEUE_FLUSH, null, "flash_off");
                    }
                } else {
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                    isFlashlightOn = true;
                    if (flashlightButton != null) {
                        flashlightButton.setText("💡 မီးပိတ်ရန်");
                    }
                    if (tts != null) {
                        tts.speak("မီးဖွင့်လိုက်ပါပြီ", TextToSpeech.QUEUE_FLUSH, null, "flash_on");
                    }
                }
                camera.setParameters(params);
            }
        }
    }

    private void showErrorScreen(String errorMsg) {
        if (resultText != null) {
            resultText.setText("Error: " + errorMsg);
            resultText.setTextColor(Color.RED);
            resultText.setTextSize(14f);
        } else {
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            if (camera == null) {
                showErrorScreen("Camera not found");
                return;
            }

            Camera.Parameters params = camera.getParameters();
            if (params.getSupportedFocusModes() != null && 
                params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            
            if (isFlashlightOn && params.getSupportedFlashModes() != null) {
                params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
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
        isProcessing = true;

        new Thread(() -> {
            try {
                Camera.Size size = camera.getParameters().getPreviewSize();
                YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, size.width, size.height, null);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, size.width, size.height), 80, out);
                byte[] jpegBytes = out.toByteArray();
                Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

                Matrix matrix = new Matrix();
                matrix.postRotate(90);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

                classifier.classify(rotated, result -> {
                    handler.post(() -> {
                        processDetection(result);
                        isProcessing = false;
                        bitmap.recycle();
                        rotated.recycle();
                    });
                });
            } catch (Throwable t) {
                handler.post(() -> {
                    showErrorScreen(t.toString());
                    isProcessing = false;
                });
            }
        }).start();
    }

    private void processDetection(String result) {
        if (result != null && !result.equals("unknown")) {
            
            if (result.equals(lastStableDetection)) {
                detectionCount++;
            } else {
                lastStableDetection = result;
                detectionCount = 1;
            }

            if (detectionCount == 2) {
                String displayText = result + " ကျပ်";
                if (resultText != null) resultText.setText(displayText);
                speakDetection(result);
                triggerVibration();
            }
            
        } else {
            if (resultText != null) resultText.setText("");
            detectionCount = 0;
            lastStableDetection = "";
        }
    }

    private void triggerVibration() {
        SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
        boolean vibrationEnabled = prefs.getBoolean("vibration", true);
        
        if (vibrationEnabled && vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(150);
            }
        }
    }

    private void speakDetection(String value) {
        if (tts != null) {
            String text = convertToMyanmar(value);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "detection");
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
            int result = tts.isLanguageAvailable(myanmarLocale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US);
            } else {
                tts.setLanguage(myanmarLocale);
            }
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
    protected void onDestroy() {
        releaseCamera();
        if (classifier != null) classifier.close();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}

