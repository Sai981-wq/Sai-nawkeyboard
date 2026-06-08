package com.moneyreader.myanmar;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class HistoryActivity extends AppCompatActivity {

    private ListView historyList;
    private TextView emptyText;
    private Button clearButton;
    private Button backButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        historyList = findViewById(R.id.historyList);
        emptyText = findViewById(R.id.emptyText);
        clearButton = findViewById(R.id.clearButton);
        backButton = findViewById(R.id.backButton);

        backButton.setOnClickListener(v -> finish());
        clearButton.setOnClickListener(v -> clearHistory());

        loadHistory();
    }

    private void loadHistory() {
        SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
        String history = prefs.getString("history", "");

        if (history.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            historyList.setVisibility(View.GONE);
            clearButton.setVisibility(View.GONE);
            return;
        }

        ArrayList<String> items = new ArrayList<>();
        String[] lines = history.split("\n");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|");
            if (parts.length >= 2) {
                try {
                    long time = Long.parseLong(parts[1].trim());
                    String date = sdf.format(new Date(time));
                    items.add(parts[0].trim() + " - " + date);
                } catch (NumberFormatException e) {
                    items.add(parts[0].trim());
                }
            } else {
                items.add(line.trim());
            }
        }

        if (items.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            historyList.setVisibility(View.GONE);
        } else {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
            historyList.setAdapter(adapter);
            emptyText.setVisibility(View.GONE);
            historyList.setVisibility(View.VISIBLE);
        }
    }

    private void clearHistory() {
        SharedPreferences prefs = getSharedPreferences("money_reader", MODE_PRIVATE);
        prefs.edit().remove("history").apply();
        loadHistory();
    }
}
