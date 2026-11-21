package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SaiNawFeedbackManager {
    public static final int HAPTIC_TYPE = 1;
    public static final int HAPTIC_LONG_PRESS = 2;
    public static final int HAPTIC_FOCUS = 3;

    private static final long FOCUS_THROTTLE_MS = 30L;

    private final Context context;
    private final Vibrator vibrator;
    private final boolean hasVibrator;
    private final AudioManager audioManager;
    private volatile SoundPool soundPool;
    private final ConcurrentHashMap<String, Integer> soundMap = new ConcurrentHashMap<>();

    private final ExecutorService hapticExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService soundExecutor = Executors.newSingleThreadExecutor();

    // Latest-wins haptic state so rapid typing/hovering never builds up delayed vibrations
    private final AtomicInteger pendingHapticType = new AtomicInteger(0);
    private final AtomicBoolean hapticTaskScheduled = new AtomicBoolean(false);
    private volatile long lastFocusHapticMs = 0L;

    private volatile boolean vibrateOn = true;
    private volatile boolean soundOn = false;
    private volatile int vibrateStrength = 0;
    private volatile int soundVolume = 1;
    private volatile String selectedSoundPack;
    private volatile boolean released = false;

    // Pre-built VibrationEffect cache (typed as Object for API < 26 class verification safety)
    private volatile Object cachedEffectType;
    private volatile Object cachedEffectLongPress;
    private volatile Object cachedEffectFocus;
    private volatile long cachedDurType = 30L;
    private volatile long cachedDurLongPress = 45L;
    private volatile long cachedDurFocus = 15L;

    private final Runnable hapticDispatcher = this::dispatchHaptic;

    public SaiNawFeedbackManager(Context context) {
        this.context = context.getApplicationContext();
        this.vibrator = (Vibrator) this.context.getSystemService(Context.VIBRATOR_SERVICE);
        this.hasVibrator = (this.vibrator != null && this.vibrator.hasVibrator());
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        initSoundPool();
        rebuildVibrationEffects(0);
    }

    private void dispatchHaptic() {
        hapticTaskScheduled.set(false);
        if (released || !vibrateOn || !hasVibrator) {
            pendingHapticType.set(0);
            return;
        }
        int type = pendingHapticType.getAndSet(0);
        if (type == 0) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Object effectObj;
                if (type == HAPTIC_FOCUS) effectObj = cachedEffectFocus;
                else if (type == HAPTIC_LONG_PRESS) effectObj = cachedEffectLongPress;
                else effectObj = cachedEffectType;

                if (effectObj instanceof VibrationEffect) {
                    vibrator.vibrate((VibrationEffect) effectObj);
                }
            } else {
                long dur = (type == HAPTIC_FOCUS) ? cachedDurFocus
                        : ((type == HAPTIC_LONG_PRESS) ? cachedDurLongPress : cachedDurType);
                vibrator.vibrate(dur);
            }
        } catch (Exception ignored) { }

        if (pendingHapticType.get() != 0 && hapticTaskScheduled.compareAndSet(false, true)) {
            if (!hapticExecutor.isShutdown()) {
                hapticExecutor.execute(hapticDispatcher);
            } else {
                hapticTaskScheduled.set(false);
            }
        }
    }

    private void initSoundPool() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attributes).build();
        } else {
            soundPool = new SoundPool(6, AudioManager.STREAM_SYSTEM, 0);
        }
    }

    public void loadSettings(SharedPreferences prefs) {
        if (prefs == null || released) return;
        vibrateOn = prefs.getBoolean("vibrate_on", true);
        soundOn = prefs.getBoolean("sound_on", false);

        int newStrength = prefs.getInt("vibrate_strength", 0);
        if (newStrength != vibrateStrength || cachedEffectType == null) {
            vibrateStrength = newStrength;
            rebuildVibrationEffects(newStrength);
        }

        soundVolume = prefs.getInt("sound_volume", 1);
        String newPack = prefs.getString("selected_sound_pack", "System Default");

        if (selectedSoundPack == null || !selectedSoundPack.equals(newPack)) {
            selectedSoundPack = newPack;
            loadCustomSoundsAsync(newPack);
        }
    }

    private void rebuildVibrationEffects(int strength) {
        long baseDur;
        int baseAmp;
        if (strength == 1) { baseDur = 20L; baseAmp = 80; }
        else if (strength == 2) { baseDur = 40L; baseAmp = 150; }
        else if (strength == 3) { baseDur = 60L; baseAmp = 255; }
        else { baseDur = 30L; baseAmp = -1; }

        long focusDur = Math.max(10L, baseDur / 2L);
        long longDur = (long) (baseDur * 1.5);

        cachedDurType = baseDur;
        cachedDurFocus = focusDur;
        cachedDurLongPress = longDur;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int typeAmp = (strength == 0) ? VibrationEffect.DEFAULT_AMPLITUDE : baseAmp;
            int focusAmp = (strength == 0) ? VibrationEffect.DEFAULT_AMPLITUDE : Math.max(10, baseAmp / 2);

            cachedEffectType = VibrationEffect.createOneShot(baseDur, typeAmp);
            cachedEffectFocus = VibrationEffect.createOneShot(focusDur, focusAmp);
            cachedEffectLongPress = VibrationEffect.createOneShot(longDur, typeAmp);
        }
    }

    private void loadCustomSoundsAsync(final String packName) {
        if (released || soundExecutor.isShutdown()) return;
        soundExecutor.execute(() -> {
            SoundPool pool = soundPool;
            if (released || pool == null) return;

            for (Integer id : soundMap.values()) {
                if (id != null) {
                    try { pool.unload(id); } catch (Exception ignored) { }
                }
            }
            soundMap.clear();

            if (packName == null || "System Default".equals(packName)) return;

            File dir = new File(new File(context.getFilesDir(), "custom_sound_packs"), packName);
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (released || !packName.equals(selectedSoundPack)) return;
                        String name = f.getName();
                        int dotIdx = name.lastIndexOf('.');
                        if (dotIdx > 0) {
                            String keyName = name.substring(0, dotIdx);
                            try {
                                int soundId = pool.load(f.getAbsolutePath(), 1);
                                soundMap.put(keyName, soundId);
                            } catch (Exception ignored) { }
                        }
                    }
                }
            }
        });
    }

    public void playHaptic(int type) {
        if (released || !vibrateOn || !hasVibrator || hapticExecutor.isShutdown()) return;

        if (type == HAPTIC_FOCUS) {
            long now = SystemClock.uptimeMillis();
            if (now - lastFocusHapticMs < FOCUS_THROTTLE_MS) return;
            lastFocusHapticMs = now;
        }

        while (true) {
            int cur = pendingHapticType.get();
            if (type == HAPTIC_FOCUS && (cur == HAPTIC_TYPE || cur == HAPTIC_LONG_PRESS)) {
                break;
            }
            if (pendingHapticType.compareAndSet(cur, type)) {
                break;
            }
        }

        if (hapticTaskScheduled.compareAndSet(false, true)) {
            try {
                hapticExecutor.execute(hapticDispatcher);
            } catch (Exception ignored) {
                hapticTaskScheduled.set(false);
            }
        }
    }

    public void playSound(final int primaryCode) {
        if (released || !soundOn || soundExecutor.isShutdown()) return;

        final float vol = (soundVolume == 0) ? 0.3f : ((soundVolume == 2) ? 1.0f : 0.6f);
        final String pack = selectedSoundPack;
        final SoundPool pool = soundPool;

        if (pack != null && !"System Default".equals(pack) && !soundMap.isEmpty() && pool != null) {
            final String keyStr;
            if (primaryCode == 32) keyStr = "space";
            else if (primaryCode == -5) keyStr = "delete";
            else if (primaryCode == -4) keyStr = "enter";
            else if (primaryCode == -1) keyStr = "shift";
            else if (primaryCode == -101) keyStr = "language";
            else if (primaryCode == -2 || primaryCode == -6) keyStr = "symbols";
            else if (primaryCode == -7 || primaryCode == -102 || primaryCode == -103) keyStr = "emoji";
            else keyStr = String.valueOf((char) primaryCode);

            Integer soundId = soundMap.get(keyStr);
            if (soundId == null) soundId = soundMap.get("default");

            if (soundId != null) {
                final int idToPlay = soundId;
                try {
                    soundExecutor.execute(() -> {
                        if (!released) {
                            try { pool.play(idToPlay, vol, vol, 1, 0, 1.0f); } catch (Exception ignored) { }
                        }
                    });
                } catch (Exception ignored) { }
                return;
            }
        }

        if (audioManager != null) {
            final int soundEffect;
            if (primaryCode == 32) soundEffect = AudioManager.FX_KEYPRESS_SPACEBAR;
            else if (primaryCode == -5) soundEffect = AudioManager.FX_KEYPRESS_DELETE;
            else if (primaryCode == -4) soundEffect = AudioManager.FX_KEYPRESS_RETURN;
            else soundEffect = AudioManager.FX_KEYPRESS_STANDARD;

            try {
                soundExecutor.execute(() -> {
                    if (!released) {
                        try { audioManager.playSoundEffect(soundEffect, vol); } catch (Exception ignored) { }
                    }
                });
            } catch (Exception ignored) { }
        }
    }

    public void release() {
        if (released) return;
        released = true;
        pendingHapticType.set(0);
        if (!hapticExecutor.isShutdown()) hapticExecutor.shutdownNow();
        if (!soundExecutor.isShutdown()) soundExecutor.shutdownNow();
        SoundPool pool = soundPool;
        soundPool = null;
        soundMap.clear();
        if (pool != null) {
            try { pool.release(); } catch (Exception ignored) { }
        }
    }
}

