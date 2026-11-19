package com.sainaw.mm.board;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class FeedbackSettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private TextView tvVibrateStrengthVal;
    private TextView tvSoundVolumeVal;
    private TextView tvSoundPackVal;
    private AlertDialog packDialog;
    private String packToExport;

    private final String[] vibrateOptions = {"System Default", "Light", "Medium", "Strong"};
    private final String[] soundOptions = {"Low", "Normal", "High"};

    private final ActivityResultLauncher<Intent> importZipLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        String packName = SaiNawZipHelper.extractZip(this, uri);
                        if (packName != null) {
                            prefs.edit().putString("selected_sound_pack", packName).apply();
                            updateSoundPackText();
                            Toast.makeText(this, "Sound Pack Imported!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Failed to import", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> exportZipLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null && packToExport != null) {
                        File sourceDir = new File(new File(getFilesDir(), "custom_sound_packs"), packToExport);
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (SaiNawZipHelper.zipFolder(sourceDir, os)) {
                                Toast.makeText(this, "Sound Pack Exported!", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Sound & Vibration");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        prefs = getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);

        tvVibrateStrengthVal = findViewById(R.id.tv_vibrate_strength_val);
        tvSoundVolumeVal = findViewById(R.id.tv_sound_volume_val);
        tvSoundPackVal = findViewById(R.id.tv_sound_pack_val);

        setupSwitch(R.id.switch_vibrate, "vibrate_on", true);
        setupSwitch(R.id.switch_sound, "sound_on", false);

        updateVibrateText();
        updateSoundText();
        updateSoundPackText();

        findViewById(R.id.layout_vibrate_strength).setOnClickListener(v -> showVibrateDialog());
        findViewById(R.id.layout_sound_volume).setOnClickListener(v -> showSoundDialog());
        findViewById(R.id.layout_sound_pack).setOnClickListener(v -> showSoundPackDialog());
    }

    private void setupSwitch(int id, final String key, boolean def) {
        SwitchCompat s = findViewById(id);
        if (s != null) {
            s.setChecked(prefs.getBoolean(key, def));
            s.setOnCheckedChangeListener((buttonView, isChecked) -> 
                prefs.edit().putBoolean(key, isChecked).apply()
            );
        }
    }

    private void showVibrateDialog() {
        int current = prefs.getInt("vibrate_strength", 0);
        new AlertDialog.Builder(this)
            .setTitle("Vibration Strength")
            .setSingleChoiceItems(vibrateOptions, current, (dialog, which) -> {
                prefs.edit().putInt("vibrate_strength", which).apply();
                updateVibrateText();
                dialog.dismiss();
            }).show();
    }

    private void showSoundDialog() {
        int current = prefs.getInt("sound_volume", 1);
        new AlertDialog.Builder(this)
            .setTitle("Sound Volume")
            .setSingleChoiceItems(soundOptions, current, (dialog, which) -> {
                prefs.edit().putInt("sound_volume", which).apply();
                updateSoundText();
                dialog.dismiss();
            }).show();
    }

    private void showSoundPackDialog() {
        File baseDir = new File(getFilesDir(), "custom_sound_packs");
        if (!baseDir.exists()) baseDir.mkdirs();

        List<String> packs = new ArrayList<>();
        packs.add("System Default");
        File[] files = baseDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) packs.add(f.getName());
            }
        }
        packs.add("+ Import New Pack (.zip)");

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, packs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                String item = getItem(position);
                String current = prefs.getString("selected_sound_pack", "System Default");
                
                if (item.equals(current)) {
                    view.setTextColor(Color.parseColor("#2196F3"));
                    view.setTypeface(null, Typeface.BOLD);
                } else if (item.startsWith("+")) {
                    view.setTextColor(Color.parseColor("#4CAF50"));
                    view.setTypeface(null, Typeface.BOLD);
                } else {
                    view.setTextColor(Color.BLACK);
                    view.setTypeface(null, Typeface.NORMAL);
                }
                return view;
            }
        };

        ListView listView = new ListView(this);
        listView.setAdapter(adapter);

        packDialog = new AlertDialog.Builder(this)
                .setTitle("Select Sound Pack")
                .setView(listView)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String item = packs.get(position);
            if (item.startsWith("+")) {
                packDialog.dismiss();
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("application/zip");
                importZipLauncher.launch(intent);
            } else {
                prefs.edit().putString("selected_sound_pack", item).apply();
                updateSoundPackText();
                packDialog.dismiss();
            }
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String item = packs.get(position);
            if (!item.equals("System Default") && !item.startsWith("+")) {
                showPackOptionsDialog(item);
            }
            return true;
        });

        packDialog.show();
    }

    private void showPackOptionsDialog(String packName) {
        new AlertDialog.Builder(this)
                .setTitle(packName)
                .setItems(new String[]{"Share / Export", "Delete"}, (dialog, which) -> {
                    if (which == 0) {
                        packDialog.dismiss();
                        packToExport = packName;
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/zip");
                        intent.putExtra(Intent.EXTRA_TITLE, packName + ".zip");
                        exportZipLauncher.launch(intent);
                    } else if (which == 1) {
                        deletePack(packName);
                        packDialog.dismiss();
                        showSoundPackDialog(); 
                    }
                }).show();
    }

    private void deletePack(String packName) {
        File dir = new File(new File(getFilesDir(), "custom_sound_packs"), packName);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
            dir.delete();
        }
        if (prefs.getString("selected_sound_pack", "").equals(packName)) {
            prefs.edit().putString("selected_sound_pack", "System Default").apply();
            updateSoundPackText();
        }
        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
    }

    private void updateVibrateText() {
        tvVibrateStrengthVal.setText(vibrateOptions[prefs.getInt("vibrate_strength", 0)]);
    }

    private void updateSoundText() {
        tvSoundVolumeVal.setText(soundOptions[prefs.getInt("sound_volume", 1)]);
    }

    private void updateSoundPackText() {
        tvSoundPackVal.setText(prefs.getString("selected_sound_pack", "System Default"));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}

