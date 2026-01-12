package com.cherry.sme.tts;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class LogViewerActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView textView = new TextView(this);
        setContentView(textView);

        try {
            Process process = Runtime.getRuntime().exec("logcat -d *:E");
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder log = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if (line.contains("com.cherry.sme.tts")) {
                    log.append(line).append("\n\n");
                }
            }
            textView.setText(log.toString());
        } catch (Exception e) {
            textView.setText("Error reading logs: " + e.getMessage());
        }
    }
}

