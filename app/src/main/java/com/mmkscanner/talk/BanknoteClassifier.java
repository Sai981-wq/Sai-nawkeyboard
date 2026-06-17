package com.mmkscanner.talk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

public class BanknoteClassifier {

    private final TextRecognizer textRecognizer;
    private long lastProcessingTimeMs = 0;

    public interface ClassificationCallback {
        void onResult(String result, String direction);
    }

    public BanknoteClassifier(Context context) {
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public void classify(Bitmap bitmap, ClassificationCallback callback) {
        if (bitmap == null) {
            callback.onResult("unknown", "");
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastProcessingTimeMs < 300) {
            callback.onResult(null, "");
            return;
        }
        lastProcessingTimeMs = currentTime;

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        textRecognizer.process(image)
                .addOnSuccessListener(text -> {
                    String rawText = text.getText().toUpperCase();
                    String resultVal = "unknown";
                    String dirVal = "";

                    if (rawText.isEmpty()) {
                        callback.onResult("unknown", "");
                        return;
                    }

                    if (rawText.contains("TEN THOUSAND")) { resultVal = "10000"; }
                    else if (rawText.contains("FIVE THOUSAND")) { resultVal = "5000"; }
                    else if (rawText.contains("ONE THOUSAND")) { resultVal = "1000"; }
                    else if (rawText.contains("FIVE HUNDRED")) { resultVal = "500"; }
                    else if (rawText.contains("TWO HUNDRED")) { resultVal = "200"; }
                    else if (rawText.contains("ONE HUNDRED")) { resultVal = "100"; }
                    else {
                        String noCommaText = rawText.replaceAll("[,\\.]", "");
                        if (noCommaText.matches(".*\\b10000\\b.*") || noCommaText.contains("10000")) { resultVal = "10000"; }
                        else if (noCommaText.matches(".*\\b5000\\b.*") || noCommaText.contains("5000")) { resultVal = "5000"; }
                        else if (noCommaText.matches(".*\\b1000\\b.*")) { resultVal = "1000"; }
                        else if (noCommaText.matches(".*\\b500\\b.*")) { resultVal = "500"; }
                        else if (noCommaText.matches(".*\\b200\\b.*")) { resultVal = "200"; }
                        else if (noCommaText.matches(".*\\b100\\b.*")) { resultVal = "100"; }
                        else if (noCommaText.matches(".*\\b50\\b.*")) { resultVal = "50"; }
                        else { resultVal = "partial"; }
                    }

                    if (resultVal.equals("partial") || resultVal.equals("unknown")) {
                        Text.TextBlock largestBlock = null;
                        int maxArea = 0;
                        for (Text.TextBlock block : text.getTextBlocks()) {
                            Rect box = block.getBoundingBox();
                            if (box != null) {
                                int area = box.width() * box.height();
                                if (area > maxArea) {
                                    maxArea = area;
                                    largestBlock = block;
                                }
                            }
                        }

                        if (largestBlock != null && largestBlock.getBoundingBox() != null) {
                            Rect box = largestBlock.getBoundingBox();
                            int imgW = bitmap.getWidth();
                            int imgH = bitmap.getHeight();
                            int centerX = box.centerX();
                            int centerY = box.centerY();

                            if (centerX < imgW * 0.3) dirVal = "Left";
                            else if (centerX > imgW * 0.7) dirVal = "Right";
                            else if (centerY < imgH * 0.3) dirVal = "Up";
                            else if (centerY > imgH * 0.7) dirVal = "Down";
                        }
                    }

                    callback.onResult(resultVal, dirVal);
                })
                .addOnFailureListener(e -> callback.onResult("unknown", ""));
    }

    public void close() {
        if (textRecognizer != null) {
            textRecognizer.close();
        }
    }
}

