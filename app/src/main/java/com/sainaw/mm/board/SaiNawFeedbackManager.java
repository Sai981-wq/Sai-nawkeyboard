package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SaiNawFeedbackManager {

    public static final int HAPTIC_TYPE = 1;
    public static final int HAPTIC_FOCUS = 2;
    public static final int HAPTIC_LONG_PRESS = 3;

    private final Vibrator vibrator;
    private final AudioManager audioManager;
    private final ExecutorService feedbackExecutor;
    private boolean isVibrationEnabled = true;
    private boolean isSoundEnabled = false;

    public SaiNawFeedbackManager(Context context) {
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.feedbackExecutor = Executors.newSingleThreadExecutor();
    }

    public void loadSettings(SharedPreferences prefs) {
        isVibrationEnabled = prefs.getBoolean("vibrate_on", true);
        isSoundEnabled = prefs.getBoolean("sound_on", false);
    }

    public void playHaptic(int type) {
        if (!isVibrationEnabled || vibrator == null || !vibrator.hasVibrator()) return;

        feedbackExecutor.execute(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    int effectId;
                    switch (type) {
                        case HAPTIC_FOCUS: effectId = VibrationEffect.EFFECT_TICK; break;
                        case HAPTIC_LONG_PRESS: effectId = VibrationEffect.EFFECT_HEAVY_CLICK; break;
                        default: effectId = VibrationEffect.EFFECT_CLICK; break;
                    }
                    vibrator.vibrate(VibrationEffect.createPredefined(effectId));
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    long duration;
                    int amplitude;
                    switch (type) {
                        case HAPTIC_FOCUS: duration = 10; amplitude = 40; break;
                        case HAPTIC_LONG_PRESS: duration = 60; amplitude = 255; break;
                        default: duration = 20; amplitude = 120; break;
                    }
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
                } else {
                    long duration;
                    switch (type) {
                        case HAPTIC_FOCUS: duration = 15; break;
                        case HAPTIC_LONG_PRESS: duration = 70; break;
                        default: duration = 30; break;
                    }
                    vibrator.vibrate(duration);
                }
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    public void playSound(int primaryCode) {
        if (!isSoundEnabled || audioManager == null) return;
        
        feedbackExecutor.execute(() -> {
            try {
                int sound;
                switch (primaryCode) {
                    case -5: sound = AudioManager.FX_KEYPRESS_DELETE; break;
                    case -4: sound = AudioManager.FX_KEYPRESS_RETURN; break;
                    case 32: sound = AudioManager.FX_KEYPRESS_SPACEBAR; break;
                    default: sound = AudioManager.FX_KEYPRESS_STANDARD; break;
                }
                audioManager.playSoundEffect(sound, 1.0f);
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    public void shutdown() {
        if (feedbackExecutor != null) feedbackExecutor.shutdown();
    }
}

