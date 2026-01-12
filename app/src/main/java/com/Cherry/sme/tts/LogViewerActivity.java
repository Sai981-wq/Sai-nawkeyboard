package com.cherry.sme.tts;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class LogViewerActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView textView = new TextView(this);
        textView.setPadding(20, 20, 20, 20);
        textView.setMovementMethod(new ScrollingMovementMethod());
        setContentView(textView);

        try {
            // App ၏ ကိုယ်ပိုင် Error Log များကိုသာ ဆွဲထုတ်ရန်
            Process process = Runtime.getRuntime().exec("logcat -d *:E");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder log = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("com.cherry.sme.tts") || line.contains("CherryTTS")) {
                    log.append(line).append("\n\n");
                }
            }
            if (log.length() == 0) {
                textView.setText("No error logs found yet.");
            } else {
                textView.setText(log.toString());
            }
        } catch (Exception e) {
            textView.setText("Could not load logs: " + e.getMessage());
        }
    }
}

