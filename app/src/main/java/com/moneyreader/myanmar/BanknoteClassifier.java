package com.moneyreader.myanmar;

import android.content.Context;
import android.graphics.Bitmap;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

public class BanknoteClassifier {

    private final TextRecognizer textRecognizer;
    private long lastProcessingTimeMs = 0;

    public interface ClassificationCallback {
        void onResult(String result);
    }

    public BanknoteClassifier(Context context) {
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public void classify(Bitmap bitmap, ClassificationCallback callback) {
        if (bitmap == null) {
            callback.onResult("unknown");
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastProcessingTimeMs < 300) {
            callback.onResult(null); 
            return;
        }
        lastProcessingTimeMs = currentTime;

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        textRecognizer.process(image)
                .addOnSuccessListener(text -> {
                    String cleanText = text.getText().replaceAll("[,\\s\\.\\n]", "");
                    
                    if (cleanText.isEmpty()) {
                        callback.onResult("unknown");
                        return;
                    }

                    if (cleanText.contains("10000")) callback.onResult("10000");
                    else if (cleanText.contains("5000")) callback.onResult("5000");
                    else if (cleanText.contains("1000")) callback.onResult("1000");
                    else if (cleanText.contains("500")) callback.onResult("500");
                    else if (cleanText.contains("200")) callback.onResult("200");
                    else if (cleanText.contains("100")) callback.onResult("100");
                    else if (cleanText.contains("50")) callback.onResult("50");
                    else callback.onResult("partial");
                })
                .addOnFailureListener(e -> callback.onResult("unknown"));
    }

    public void close() {
        if (textRecognizer != null) {
            textRecognizer.close();
        }
    }
}

