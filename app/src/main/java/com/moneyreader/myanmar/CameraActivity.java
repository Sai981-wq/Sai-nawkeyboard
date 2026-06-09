package com.moneyreader.myanmar;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
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
    private TextView instructionText;
    private Button confirmButton;
    private Button backButton;
    private BanknoteClassifier classifier;
    private TextToSpeech tts;
    private Handler handler;
    private boolean isProcessing = false;
    private String currentDetection = "";
    private int detectionCount = 0;
    private String lastStableDetection = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_camera);

            surfaceView = findViewById(R.id.surfaceView);
            resultText = findViewById(R.id.resultText);
            instructionText = findViewById(R.id.instructionText);
            confirmButton = findViewById(R.id.confirmButton);
            backButton = findViewById(R.id.backButton);
            handler = new Handler(Looper.getMainLooper());

            tts = new TextToSpeech(this, this);
            classifier = new BanknoteClassifier(this);

            surfaceView.getHolder().addCallback(this);

            confirmButton.setOnClickListener(v -> {
                if (!lastStableDetection.isEmpty()) {
                    Intent resultIntent = new Intent();
                    resultIntent.putExtra("detected_value", lastStableDetection);
                    setResult(RESULT_OK, resultIntent);
                    finish();
                }
            });

            backButton.setOnClickListener(v -> finish());
            confirmButton.setVisibility(View.GONE);
        } catch (Throwable t) {
            showErrorScreen(t.toString());
        }
    }

    private void showErrorScreen(String errorMsg) {
        if (instructionText != null) {
            instructionText.setText(errorMsg);
            instructionText.setTextColor(Color.RED);
            instructionText.setTextSize(14f);
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

        handler.post(() -> {
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

                String result = classifier.classify(rotated);
                processDetection(result);

                bitmap.recycle();
                rotated.recycle();
            } catch (Throwable t) {
                showErrorScreen(t.toString());
            }
            isProcessing = false;
        });
    }

    private void processDetection(String result) {
        if (result != null && !result.equals("unknown")) {
            if (result.equals(currentDetection)) {
                detectionCount++;
            } else {
                currentDetection = result;
                detectionCount = 1;
            }

            if (detectionCount >= 3 && !result.equals(lastStableDetection)) {
                lastStableDetection = result;
                String displayText = result + " ကျပ်";
                resultText.setText(displayText);
                confirmButton.setVisibility(View.VISIBLE);
                instructionText.setText("အတည်ပြုရန် ခလုတ်ကို နှိပ်ပါ");
                speakDetection(result);
            }
        } else {
            resultText.setText("ငွေစက္ကူကို ကင်မရာရှေ့တွင် ထားပါ");
            detectionCount = 0;
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
            tts.setLanguage(new Locale("my", "MM"));
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

