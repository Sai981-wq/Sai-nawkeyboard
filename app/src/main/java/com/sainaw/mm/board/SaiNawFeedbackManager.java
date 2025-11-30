package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.inputmethod.InputMethodService;

public class SaiNawFeedbackManager {
    // Constants
    public static final int HAPTIC_FOCUS = 0;
    public static final int HAPTIC_TYPE = 1;
    public static final int HAPTIC_LONG_PRESS = 2;

    private final Context context;
    private final AudioManager audioManager;
    private final Vibrator vibrator;
    
    // Settings
    private boolean isVibrateOn = true;
    private boolean isSoundOn = true;

    public SaiNawFeedbackManager(Context context) {
        this.context = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void loadSettings(SharedPreferences prefs) {
        isVibrateOn = prefs.getBoolean("vibrate_on", true);
        isSoundOn = prefs.getBoolean("sound_on", true);
    }

    public void playHaptic(int type) {
        if (!isVibrateOn || vibrator == null || !vibrator.hasVibrator()) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int effectId;
                switch (type) {
                    case HAPTIC_LONG_PRESS: effectId = VibrationEffect.EFFECT_HEAVY_CLICK; break;
                    case HAPTIC_TYPE: effectId = VibrationEffect.EFFECT_CLICK; break;
                    default: effectId = VibrationEffect.EFFECT_TICK; break;
                }
                vibrator.vibrate(VibrationEffect.createPredefined(effectId));
            } else {
                int duration = (type == HAPTIC_LONG_PRESS) ? 50 : ((type == HAPTIC_TYPE) ? 30 : 10);
                vibrator.vibrate(duration);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void playSound(int primaryCode) {
        if (!isSoundOn || audioManager == null) return;
        try {
            int soundEffect = AudioManager.FX_KEYPRESS_STANDARD;
            if (primaryCode == -5) soundEffect = AudioManager.FX_KEYPRESS_DELETE; // CODE_DELETE
            else if (primaryCode == 32) soundEffect = AudioManager.FX_KEYPRESS_SPACEBAR; // CODE_SPACE
            else if (primaryCode == -4) soundEffect = AudioManager.FX_KEYPRESS_RETURN; // CODE_ENTER
            audioManager.playSoundEffect(soundEffect, 1.0f);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
