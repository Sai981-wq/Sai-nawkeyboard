package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.io.File;
import java.util.HashMap;

public class SaiNawFeedbackManager {
    public static final int HAPTIC_TYPE = 1;
    public static final int HAPTIC_LONG_PRESS = 2;
    public static final int HAPTIC_FOCUS = 3;

    private final Context context;
    private final Vibrator vibrator;
    private final AudioManager audioManager;
    private SoundPool soundPool;
    private final HashMap<String, Integer> soundMap = new HashMap<>();

    private boolean vibrateOn;
    private boolean soundOn;
    private int vibrateStrength;
    private int soundVolume;
    private String selectedSoundPack;

    public SaiNawFeedbackManager(Context context) {
        this.context = context;
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        initSoundPool();
    }

    private void initSoundPool() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder().setMaxStreams(5).setAudioAttributes(attributes).build();
        } else {
            soundPool = new SoundPool(5, AudioManager.STREAM_SYSTEM, 0);
        }
    }

    public void loadSettings(SharedPreferences prefs) {
        vibrateOn = prefs.getBoolean("vibrate_on", true);
        soundOn = prefs.getBoolean("sound_on", false);
        vibrateStrength = prefs.getInt("vibrate_strength", 0);
        soundVolume = prefs.getInt("sound_volume", 1);
        String newPack = prefs.getString("selected_sound_pack", "System Default");
        
        if (selectedSoundPack == null || !selectedSoundPack.equals(newPack)) {
            selectedSoundPack = newPack;
            loadCustomSounds();
        }
    }

    private void loadCustomSounds() {
        if (soundPool != null) {
            for (int id : soundMap.values()) soundPool.unload(id);
        }
        soundMap.clear();
        if (selectedSoundPack == null || selectedSoundPack.equals("System Default")) return;

        File dir = new File(new File(context.getFilesDir(), "custom_sound_packs"), selectedSoundPack);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    int dotIdx = name.lastIndexOf('.');
                    if (dotIdx > 0) {
                        String keyName = name.substring(0, dotIdx);
                        int soundId = soundPool.load(f.getAbsolutePath(), 1);
                        soundMap.put(keyName, soundId);
                    }
                }
            }
        }
    }

    public void playHaptic(int type) {
        if (!vibrateOn || vibrator == null || !vibrator.hasVibrator()) return;
        long duration;
        int amplitude;
        if (vibrateStrength == 1) { duration = 20; amplitude = 80; }
        else if (vibrateStrength == 2) { duration = 40; amplitude = 150; }
        else if (vibrateStrength == 3) { duration = 60; amplitude = 255; }
        else { duration = 30; amplitude = VibrationEffect.DEFAULT_AMPLITUDE; }

        if (type == HAPTIC_FOCUS) {
            duration = Math.max(10, duration / 2);
            if (amplitude != VibrationEffect.DEFAULT_AMPLITUDE) amplitude = Math.max(10, amplitude / 2);
        } else if (type == HAPTIC_LONG_PRESS) {
            duration = (long) (duration * 1.5);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (vibrateStrength == 0) vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
        } else {
            vibrator.vibrate(duration);
        }
    }

    public void playSound(int primaryCode) {
        if (!soundOn) return;

        float vol = (soundVolume == 0) ? 0.3f : ((soundVolume == 2) ? 1.0f : 0.6f);

        if (!"System Default".equals(selectedSoundPack) && !soundMap.isEmpty() && soundPool != null) {
            String keyStr = String.valueOf((char) primaryCode);
            if (primaryCode == 32) keyStr = "space";
            else if (primaryCode == -5) keyStr = "delete";
            else if (primaryCode == -4) keyStr = "enter";
            else if (primaryCode == -1) keyStr = "shift";
            else if (primaryCode == -101) keyStr = "language";
            else if (primaryCode == -2 || primaryCode == -6) keyStr = "symbols";
            else if (primaryCode == -7 || primaryCode == -102 || primaryCode == -103) keyStr = "emoji";

            Integer soundId = soundMap.get(keyStr);
            if (soundId == null) soundId = soundMap.get("default");

            if (soundId != null) {
                soundPool.play(soundId, vol, vol, 1, 0, 1.0f);
                return;
            }
        }

        if (audioManager != null) {
            int soundEffect = AudioManager.FX_KEYPRESS_STANDARD;
            if (primaryCode == 32) soundEffect = AudioManager.FX_KEYPRESS_SPACEBAR;
            else if (primaryCode == -5) soundEffect = AudioManager.FX_KEYPRESS_DELETE;
            else if (primaryCode == -4) soundEffect = AudioManager.FX_KEYPRESS_RETURN;
            audioManager.playSoundEffect(soundEffect, vol);
        }
    }
}

