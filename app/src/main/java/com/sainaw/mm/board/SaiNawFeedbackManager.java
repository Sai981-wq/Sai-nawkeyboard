package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

public class SaiNawFeedbackManager {
    public static final int HAPTIC_TYPE = 1;
    public static final int HAPTIC_LONG_PRESS = 2;
    public static final int HAPTIC_FOCUS = 3;

    private final Vibrator vibrator;
    private final AudioManager audioManager;

    private boolean vibrateOn;
    private boolean soundOn;
    private int vibrateStrength; 
    private int soundVolume;     

    public SaiNawFeedbackManager(Context context) {
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void loadSettings(SharedPreferences prefs) {
        vibrateOn = prefs.getBoolean("vibrate_on", true);
        soundOn = prefs.getBoolean("sound_on", false);
        vibrateStrength = prefs.getInt("vibrate_strength", 0); 
        soundVolume = prefs.getInt("sound_volume", 1); 
    }

    public void playHaptic(int type) {
        if (!vibrateOn || vibrator == null || !vibrator.hasVibrator()) return;

        long duration;
        int amplitude;

        if (vibrateStrength == 1) {
            duration = 20; amplitude = 80;
        } else if (vibrateStrength == 2) {
            duration = 40; amplitude = 150;
        } else if (vibrateStrength == 3) {
            duration = 60; amplitude = 255;
        } else {
            duration = 30; amplitude = VibrationEffect.DEFAULT_AMPLITUDE;
        }

        if (type == HAPTIC_FOCUS) {
            duration = Math.max(10, duration / 2);
            if (amplitude != VibrationEffect.DEFAULT_AMPLITUDE) {
                amplitude = Math.max(10, amplitude / 2);
            }
        } else if (type == HAPTIC_LONG_PRESS) {
            duration = (long) (duration * 1.5);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (vibrateStrength == 0) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
            }
        } else {
            vibrator.vibrate(duration);
        }
    }

    public void playSound(int primaryCode) {
        if (!soundOn || audioManager == null) return;
        
        float vol;
        if (soundVolume == 0) vol = 0.3f;
        else if (soundVolume == 2) vol = 1.0f;
        else vol = 0.6f;

        int soundEffect = AudioManager.FX_KEYPRESS_STANDARD;
        if (primaryCode == 32) soundEffect = AudioManager.FX_KEYPRESS_SPACEBAR;
        else if (primaryCode == -5) soundEffect = AudioManager.FX_KEYPRESS_DELETE;
        else if (primaryCode == -4) soundEffect = AudioManager.FX_KEYPRESS_RETURN;

        audioManager.playSoundEffect(soundEffect, vol);
    }
}

