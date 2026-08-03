package com.cherry.sme.tts;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class LogViewerActivity extends Activity {

    private TextView logTextView;
    private Handler autoRefreshHandler;
    private Runnable autoRefreshRunnable;
    private boolean showErrorsOnly = false;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1E1E1E"));
        root.setPadding(0, 0, 0, 0);

        TextView titleView = new TextView(this);
        titleView.setText("Cherry SME TTS - Error Logs");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18f);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(30, 40, 30, 20);
        titleView.setBackgroundColor(Color.parseColor("#2D2D2D"));
        root.addView(titleView);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(10, 10, 10, 10);
        buttonRow.setBackgroundColor(Color.parseColor("#2D2D2D"));
        buttonRow.setGravity(Gravity.CENTER);

        Button btnAll = new Button(this);
        btnAll.setText("All Logs");
        btnAll.setTextSize(12f);
        btnAll.setAllCaps(false);
        btnAll.setPadding(20, 10, 20, 10);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnParams.setMargins(5, 0, 5, 0);
        btnAll.setLayoutParams(btnParams);

        Button btnErrors = new Button(this);
        btnErrors.setText("Errors Only");
        btnErrors.setTextSize(12f);
        btnErrors.setAllCaps(false);
        btnErrors.setPadding(20, 10, 20, 10);
        btnErrors.setLayoutParams(btnParams);

        Button btnCopy = new Button(this);
        btnCopy.setText("Copy");
        btnCopy.setTextSize(12f);
        btnCopy.setAllCaps(false);
        btnCopy.setPadding(20, 10, 20, 10);
        btnCopy.setLayoutParams(btnParams);

        Button btnShare = new Button(this);
        btnShare.setText("Share");
        btnShare.setTextSize(12f);
        btnShare.setAllCaps(false);
        btnShare.setPadding(20, 10, 20, 10);
        btnShare.setLayoutParams(btnParams);

        Button btnClear = new Button(this);
        btnClear.setText("Clear");
        btnClear.setTextSize(12f);
        btnClear.setAllCaps(false);
        btnClear.setPadding(20, 10, 20, 10);
        btnClear.setLayoutParams(btnParams);

        buttonRow.addView(btnAll);
        buttonRow.addView(btnErrors);
        buttonRow.addView(btnCopy);
        buttonRow.addView(btnShare);
        buttonRow.addView(btnClear);
        root.addView(buttonRow);

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollView.setLayoutParams(scrollParams);

        logTextView = new TextView(this);
        logTextView.setPadding(30, 20, 30, 20);
        logTextView.setTextSize(12f);
        logTextView.setTypeface(Typeface.MONOSPACE);
        logTextView.setTextColor(Color.parseColor("#E0E0E0"));
        logTextView.setMovementMethod(new ScrollingMovementMethod());
        scrollView.addView(logTextView);
        root.addView(scrollView);

        TextView statusBar = new TextView(this);
        statusBar.setPadding(30, 10, 30, 10);
        statusBar.setTextSize(11f);
        statusBar.setBackgroundColor(Color.parseColor("#2D2D2D"));
        statusBar.setTextColor(Color.parseColor("#AAAAAA"));
        statusBar.setText("Auto-refresh: ON | Long press KBZPay to open");
        root.addView(statusBar);

        setContentView(root);

        refreshLogs();

        btnAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showErrorsOnly = false;
                refreshLogs();
                Toast.makeText(LogViewerActivity.this, "Showing all logs", Toast.LENGTH_SHORT).show();
            }
        });

        btnErrors.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showErrorsOnly = true;
                refreshLogs();
                Toast.makeText(LogViewerActivity.this, "Showing errors only", Toast.LENGTH_SHORT).show();
            }
        });

        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = logTextView.getText().toString();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("TTS Logs", text);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(LogViewerActivity.this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = logTextView.getText().toString();
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Cherry SME TTS Logs");
                shareIntent.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(shareIntent, "Share logs via"));
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogCollector.clear();
                refreshLogs();
                Toast.makeText(LogViewerActivity.this, "Logs cleared", Toast.LENGTH_SHORT).show();
            }
        });

        autoRefreshHandler = new Handler();
        autoRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                refreshLogs();
                autoRefreshHandler.postDelayed(this, 3000);
            }
        };
        autoRefreshHandler.postDelayed(autoRefreshRunnable, 3000);
    }

    private void refreshLogs() {
        String logs;
        if (showErrorsOnly) {
            logs = LogCollector.getErrorsOnly();
        } else {
            logs = LogCollector.getLogs();
        }
        logTextView.setText(logs);
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                scrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (autoRefreshHandler != null && autoRefreshRunnable != null) {
            autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        }
        super.onDestroy();
    }
}
