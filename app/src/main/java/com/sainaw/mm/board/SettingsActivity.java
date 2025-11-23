package com.sainaw.mm.board;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private TextView tvSelectedFile;

    // File Picker Result ကို ဖမ်းယူမည့် Launcher
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            saveDictionaryUri(uri);
                            tvSelectedFile.setText("Selected: " + uri.getLastPathSegment());
                        }
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        tvSelectedFile = findViewById(R.id.tv_selected_file);

        updateSelectedFileText();

        Button btnChoose = findViewById(R.id.btn_choose_dictionary);
        btnChoose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFilePicker();
            }
        });
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        filePickerLauncher.launch(intent);
    }

    private void saveDictionaryUri(Uri uri) {
        SharedPreferences prefs = getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        prefs.edit().putString("dictionary_uri", uri.toString()).apply();
    }

    private void updateSelectedFileText() {
        SharedPreferences prefs = getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        String uriString = prefs.getString("dictionary_uri", null);

        if (uriString != null) {
            Uri uri = Uri.parse(uriString);
            String fileName = uri.getLastPathSegment();
            tvSelectedFile.setText("Selected: " + (fileName != null ? fileName : uri.toString()));
        } else {
            tvSelectedFile.setText("No file selected.");
        }
    }
}
