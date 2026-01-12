package com.cherry.sme.tts;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.TextView;

public class LogViewerActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView textView = new TextView(this);
        textView.setPadding(30, 30, 30, 30);
        textView.setMovementMethod(new ScrollingMovementMethod());
        textView.setTextSize(14f);
        setContentView(textView);
        textView.setText(LogCollector.getLogs());
    }
}

