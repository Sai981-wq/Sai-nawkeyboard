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
                    String rawText = text.getText().toUpperCase();
                    
                    if (rawText.isEmpty()) {
                        callback.onResult("unknown");
                        return;
                    }

                    // ၁။ အင်္ဂလိပ်စာသားများဖြင့် အရင်စစ်ဆေးခြင်း (လက်ကွယ်၍ မရနိုင်သောကြောင့် အတိကျဆုံးဖြစ်သည်)
                    if (rawText.contains("TEN THOUSAND")) { callback.onResult("10000"); return; }
                    if (rawText.contains("FIVE THOUSAND")) { callback.onResult("5000"); return; }
                    if (rawText.contains("ONE THOUSAND")) { callback.onResult("1000"); return; }
                    if (rawText.contains("FIVE HUNDRED")) { callback.onResult("500"); return; }
                    if (rawText.contains("TWO HUNDRED")) { callback.onResult("200"); return; }
                    if (rawText.contains("ONE HUNDRED")) { callback.onResult("100"); return; }

                    // ၂။ ဂဏန်းများကို တိတိကျကျ စစ်ဆေးခြင်း (Substring မှားမဖတ်စေရန် Regex Word Boundary \\b ကိုသုံးခြင်း)
                    String noCommaText = rawText.replaceAll("[,\\.]", "");
                    
                    // တန်ဖိုးအကြီးဆုံးများသည် အခြားဂဏန်းများ၏ အစိတ်အပိုင်း (Substring) မဖြစ်နိုင်ပါ
                    if (noCommaText.matches(".*\\b10000\\b.*") || noCommaText.contains("10000")) { callback.onResult("10000"); return; }
                    if (noCommaText.matches(".*\\b5000\\b.*") || noCommaText.contains("5000")) { callback.onResult("5000"); return; }
                    
                    // အငယ်များကိုမူ သီးသန့်ဂဏန်း (Standalone Word) ဖြစ်မှသာ လက်ခံပါမည်
                    if (noCommaText.matches(".*\\b1000\\b.*")) { callback.onResult("1000"); return; }
                    if (noCommaText.matches(".*\\b500\\b.*")) { callback.onResult("500"); return; }
                    if (noCommaText.matches(".*\\b200\\b.*")) { callback.onResult("200"); return; }
                    if (noCommaText.matches(".*\\b100\\b.*")) { callback.onResult("100"); return; }
                    if (noCommaText.matches(".*\\b50\\b.*")) { callback.onResult("50"); return; }

                    callback.onResult("partial");
                })
                .addOnFailureListener(e -> callback.onResult("unknown"));
    }

    public void close() {
        if (textRecognizer != null) {
            textRecognizer.close();
        }
    }
}

