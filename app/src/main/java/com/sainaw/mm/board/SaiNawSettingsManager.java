package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.UserManager;

public class SaiNawSettingsManager {

    private static final String PREF_NAME = "KeyboardPrefs";
    private static final String KEY_VIBRATE = "vibrate_on";
    private static final String KEY_SOUND = "sound_on";
    private static final String KEY_THEME = "dark_theme";
    private static final String KEY_NUMBER_ROW = "number_row";
    private static final String KEY_LIFT_TO_TYPE = "lift_to_type";
    private static final String KEY_SMART_ECHO = "smart_echo";
    private static final String KEY_PHONETIC_SOUNDS = "use_phonetic_sounds";
    
    public static final String KEY_ENABLE_MM = "enable_mm";
    public static final String KEY_ENABLE_SHAN = "enable_shan";

    private boolean vibrateOn;
    private boolean soundOn;
    private boolean darkTheme;
    private boolean showNumberRow;
    private boolean liftToType;
    private boolean smartEcho;
    private boolean phoneticSounds;

    private final Context context;
    private SharedPreferences prefs;

    public SaiNawSettingsManager(Context context) {
        this.context = context;
        loadSettings();
    }

    public void loadSettings() {
        Context safeContext = getSafeContext(context);
        prefs = safeContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        vibrateOn = prefs.getBoolean(KEY_VIBRATE, true);
        soundOn = prefs.getBoolean(KEY_SOUND, false);
        darkTheme = prefs.getBoolean(KEY_THEME, false);
        showNumberRow = prefs.getBoolean(KEY_NUMBER_ROW, false);
        
        liftToType = prefs.getBoolean(KEY_LIFT_TO_TYPE, true);
        smartEcho = prefs.getBoolean(KEY_SMART_ECHO, false);
        phoneticSounds = prefs.getBoolean(KEY_PHONETIC_SOUNDS, true);
    }

    private Context getSafeContext(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (um != null && !um.isUserUnlocked()) {
                return context.createDeviceProtectedStorageContext();
            }
        }
        return context;
    }

    public boolean isVibrateOn() { return vibrateOn; }
    public boolean isSoundOn() { return soundOn; }
    public boolean isDarkTheme() { return darkTheme; }
    public boolean isShowNumberRow() { return showNumberRow; }
    public boolean isLiftToType() { return liftToType; }
    public boolean isSmartEcho() { return smartEcho; }
    public boolean isPhoneticSounds() { return phoneticSounds; }
    
    public SharedPreferences getPrefs() { return prefs; }
}

