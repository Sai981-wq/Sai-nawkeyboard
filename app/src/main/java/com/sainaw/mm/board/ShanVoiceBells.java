package com.sainaw.mm.board;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.Set;

/** Preloaded short bells. Audio failure must never crash or prevent typing. */
public final class ShanVoiceBells {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Integer> loaded = new HashSet<>();
    private final Set<Integer> failed = new HashSet<>();
    private final SoundPool pool;
    private int startId, successId, errorId;
    private boolean released;

    public ShanVoiceBells(Context context) {
        SoundPool created = null;
        try {
            created = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            created.setOnLoadCompleteListener((p, id, status) -> {
                if (status == 0) loaded.add(id); else failed.add(id);
            });
            startId = created.load(context, R.raw.voice_start, 1);
            successId = created.load(context, R.raw.voice_success, 1);
            errorId = created.load(context, R.raw.voice_error, 1);
        } catch (RuntimeException | LinkageError e) {
            if (created != null) try { created.release(); } catch (RuntimeException ignored) { }
            created = null;
        }
        pool = created;
    }
    public void start() { playWhenReady(startId, null, 0); }
    public void start(Runnable afterBell) { playWhenReady(startId, afterBell, 0); }
    public void success() { playWhenReady(successId, null, 0); }
    public void error() { playWhenReady(errorId, null, 0); }
    private void playWhenReady(int id, Runnable after, int attempt) {
        if (released) return;
        if (pool != null && id != 0 && !loaded.contains(id) && !failed.contains(id) && attempt < 25) {
            main.postDelayed(() -> playWhenReady(id, after, attempt + 1), 20);
            return;
        }
        if (pool != null && loaded.contains(id)) {
            try { pool.play(id, 1f, 1f, 1, 0, 1f); }
            catch (RuntimeException | LinkageError ignored) { }
        }
        // All samples are under 240ms; do not capture the app's own bell.
        if (after != null) main.postDelayed(after, 300);
    }
    public void cancelPending() { main.removeCallbacksAndMessages(null); }
    public void release() {
        if (released) return;
        released = true;
        main.removeCallbacksAndMessages(null);
        if (pool != null) try { pool.release(); } catch (RuntimeException | LinkageError ignored) { }
    }
}
