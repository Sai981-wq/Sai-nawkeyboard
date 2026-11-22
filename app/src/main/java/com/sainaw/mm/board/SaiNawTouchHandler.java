package com.sainaw.mm.board;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.inputmethodservice.Keyboard;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import java.util.List;

public class SaiNawTouchHandler {
    private static final long FOCUS_HAPTIC_THROTTLE_MS = 32L;

    // Swipe / gesture rejection constants for Screen Reader Lift-to-Type
    private static final int HOVER_SAMPLE_CAPACITY = 12;
    private static final long RECENT_MOTION_WINDOW_MS = 130L;
    private static final long FAST_SWIPE_MAX_DURATION_MS = 260L;
    private static final long MIN_DWELL_AFTER_UPWARD_SWIPE_MS = 140L;
    private static final long MIN_DWELL_AFTER_FAST_SLIDE_MS = 110L;
    private static final float FLICK_SPEED_DP_PER_SEC = 300f;

    // Hover-exit click ownership window to consume CSR/TalkBack's follow-up ACTION_CLICK
    private static final long HOVER_OWNED_SAME_KEY_WINDOW_MS = 350L;
    private static final long HOVER_OWNED_ADJACENT_KEY_WINDOW_MS = 180L;

    private final SaiNawKeyboardService service;
    private final SaiNawLayoutManager layoutManager;
    private final SaiNawFeedbackManager feedbackManager;
    private final SaiNawEmojiManager emojiManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final float density;
    private final float strongUpwardSwipePx;
    private final float moderateUpwardSwipePx;
    private final float fastUpwardTotalPx;
    private final float strongSlideDistPx;
    private final float fastSlideDistPx;
    private final float flickMinDistPx;
    private final float topRowZonePx;
    private final float topRowUpwardPx;
    private final float topRowTotalUpwardPx;

    // Zero-allocation ring buffer tracking recent hover coordinates
    private final float[] sampleX = new float[HOVER_SAMPLE_CAPACITY];
    private final float[] sampleY = new float[HOVER_SAMPLE_CAPACITY];
    private final long[] sampleTime = new long[HOVER_SAMPLE_CAPACITY];
    private int sampleHead = 0;
    private int sampleCount = 0;

    private float hoverEnterX = 0f;
    private float hoverEnterY = 0f;
    private long hoverEnterTime = 0L;
    private long lastKeyEnterTime = 0L;
    private int keysVisitedInSession = 0;

    private boolean isLiftToType = true;
    private int lastHoverKeyIndex = -1;
    private boolean isLongPressHandled = false;
    private boolean isDeleteActive = false;
    private int currentEmojiCode = 0;
    private long lastFocusHapticTime = 0L;

    // One-shot ownership token for the hover-exit just handled by this touch handler
    private boolean pendingHoverExitConsume = false;
    private int lastHoverExitVirtualId = -1;
    private long lastHoverExitHandledMs = 0L;

    private final Runnable spaceLongPressTask;
    private final Runnable shiftLongPressTask;
    private final Runnable emojiLongPressTask;
    private final Runnable deleteStartTask;
    private final Runnable deleteLoopTask;
    private final Runnable enterLongPressTask;

    public SaiNawTouchHandler(SaiNawKeyboardService service,
                              SaiNawLayoutManager layoutManager,
                              SaiNawFeedbackManager feedbackManager,
                              SaiNawEmojiManager emojiManager) {
        this.service = service;
        this.layoutManager = layoutManager;
        this.feedbackManager = feedbackManager;
        this.emojiManager = emojiManager;

        this.density = service.getResources().getDisplayMetrics().density;
        this.strongUpwardSwipePx = 24f * density;
        this.moderateUpwardSwipePx = 16f * density;
        this.fastUpwardTotalPx = 24f * density;
        this.strongSlideDistPx = 30f * density;
        this.fastSlideDistPx = 22f * density;
        this.flickMinDistPx = 16f * density;
        this.topRowZonePx = 48f * density;
        this.topRowUpwardPx = 6f * density;
        this.topRowTotalUpwardPx = 18f * density;

        this.spaceLongPressTask = () -> {
            isLongPressHandled = true;
            feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_LONG_PRESS);
            InputMethodManager imeManager = (InputMethodManager) service.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imeManager != null) imeManager.showInputMethodPicker();
        };

        this.shiftLongPressTask = () -> {
            isLongPressHandled = true;
            layoutManager.isCapsLocked = true;
            layoutManager.isCaps = true;
            feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_LONG_PRESS);
            layoutManager.updateKeyboardLayout();
            service.announceText("Shift Locked");
        };

        this.enterLongPressTask = () -> {
            isLongPressHandled = true;
            feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_LONG_PRESS);
            Intent intent = new Intent(service, SettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
        };

        this.emojiLongPressTask = () -> {
            if (currentEmojiCode != 0) {
                String desc = emojiManager.getMmDescription(currentEmojiCode);
                if (desc != null) {
                    isLongPressHandled = true;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_LONG_PRESS);
                    service.announceText(desc);
                }
            }
        };

        this.deleteLoopTask = new Runnable() {
            @Override
            public void run() {
                if (isDeleteActive) {
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    service.handleInput(-5, null);
                    handler.postDelayed(this, 100);
                }
            }
        };

        this.deleteStartTask = () -> {
            isLongPressHandled = true;
            isDeleteActive = true;
            handler.post(deleteLoopTask);
        };
    }

    public void loadSettings(SharedPreferences prefs) {
        isLiftToType = prefs.getBoolean("lift_to_type", true);
        if (!isLiftToType) {
            clearHoverExitOwnership();
        }
    }

    public void handleStandardPress(int primaryCode) {
        cancelAllLongPress();
        if (primaryCode == 32) handler.postDelayed(spaceLongPressTask, 1500);
        else if (primaryCode == -5) handler.postDelayed(deleteStartTask, 600);
        else if (primaryCode == -1) handler.postDelayed(shiftLongPressTask, 1200);
        else if (primaryCode == -4) handler.postDelayed(enterLongPressTask, 3000);
        else {
            Keyboard.Key key = findKeyByCode(primaryCode);
            if (key != null) {
                int resolvedEmojiCode = resolveEmojiCode(key);
                if (resolvedEmojiCode != 0) {
                    currentEmojiCode = resolvedEmojiCode;
                    handler.postDelayed(emojiLongPressTask, 1000);
                }
            }
        }
    }

    public boolean performLongPressAction(int primaryCode) {
        if (primaryCode == 32) {
            InputMethodManager imeManager = (InputMethodManager) service.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imeManager != null) imeManager.showInputMethodPicker();
            return true;
        } else if (primaryCode == -1) {
            layoutManager.isCapsLocked = true;
            layoutManager.isCaps = true;
            layoutManager.updateKeyboardLayout();
            service.announceText("Shift Locked");
            return true;
        } else if (primaryCode == -4) {
            Intent intent = new Intent(service, SettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
            return true;
        }
        return false;
    }

    public boolean isLongPressHandled() {
        return isLongPressHandled;
    }

    private void markHoverExitHandled(int virtualViewId) {
        pendingHoverExitConsume = true;
        lastHoverExitVirtualId = virtualViewId;
        lastHoverExitHandledMs = SystemClock.uptimeMillis();
    }

    private void clearHoverExitOwnership() {
        pendingHoverExitConsume = false;
        lastHoverExitVirtualId = -1;
        lastHoverExitHandledMs = 0L;
    }

    /**
     * Consumes the single follow-up ACTION_CLICK dispatched by CSR (when Double tap to type is unchecked)
     * or TalkBack right after ACTION_HOVER_EXIT was already handled directly by Lift-to-Type.
     */
    public boolean consumeHoverOwnedClick(int virtualViewId) {
        if (!pendingHoverExitConsume) {
            return false;
        }
        long elapsed = SystemClock.uptimeMillis() - lastHoverExitHandledMs;
        int handledId = lastHoverExitVirtualId;
        clearHoverExitOwnership();

        if (virtualViewId == handledId && elapsed <= HOVER_OWNED_SAME_KEY_WINDOW_MS) {
            return true;
        }
        return elapsed <= HOVER_OWNED_ADJACENT_KEY_WINDOW_MS;
    }

    private Keyboard.Key findKeyByCode(int primaryCode) {
        List<Keyboard.Key> keys = layoutManager.getCurrentKeys();
        if (keys == null) return null;
        for (Keyboard.Key k : keys) {
            if (k != null && k.codes != null && k.codes.length > 0 && k.codes[0] == primaryCode) {
                return k;
            }
        }
        return null;
    }

    private void resetHoverTracking() {
        sampleHead = 0;
        sampleCount = 0;
        hoverEnterX = 0f;
        hoverEnterY = 0f;
        hoverEnterTime = 0L;
        lastKeyEnterTime = 0L;
        keysVisitedInSession = 0;
    }

    private void recordHoverSample(float rawX, float rawY, long eventTime) {
        sampleX[sampleHead] = rawX;
        sampleY[sampleHead] = rawY;
        sampleTime[sampleHead] = eventTime;
        sampleHead = (sampleHead + 1) % HOVER_SAMPLE_CAPACITY;
        if (sampleCount < HOVER_SAMPLE_CAPACITY) {
            sampleCount++;
        }
    }

    private boolean isSwipeOrEdgeExit(float rawX, float rawY, long exitTime, View hostView) {
        if (rawY < 0f || rawX < 0f) return true;
        if (hostView != null) {
            int width = hostView.getWidth();
            int height = hostView.getHeight();
            if ((width > 0 && rawX > width) || (height > 0 && rawY > height)) {
                return true;
            }
        }

        if (sampleCount == 0) return false;

        int latestIdx = (sampleHead - 1 + HOVER_SAMPLE_CAPACITY) % HOVER_SAMPLE_CAPACITY;
        int refIdx = latestIdx;
        for (int i = 0; i < sampleCount; i++) {
            int idx = (sampleHead - 1 - i + HOVER_SAMPLE_CAPACITY) % HOVER_SAMPLE_CAPACITY;
            if (exitTime - sampleTime[idx] <= RECENT_MOTION_WINDOW_MS) {
                refIdx = idx;
            } else {
                break;
            }
        }

        float recentDx = rawX - sampleX[refIdx];
        float recentDy = rawY - sampleY[refIdx];
        long recentDt = Math.max(1L, exitTime - sampleTime[refIdx]);
        float recentDist = (float) Math.hypot(recentDx, recentDy);

        float totalDx = rawX - hoverEnterX;
        float totalDy = rawY - hoverEnterY;
        long totalDuration = Math.max(1L, exitTime - hoverEnterTime);
        long dwellOnExitKey = Math.max(0L, exitTime - lastKeyEnterTime);

        if (rawY < topRowZonePx
                && (recentDy < -topRowUpwardPx || (keysVisitedInSession >= 2 && totalDy < -topRowTotalUpwardPx))
                && dwellOnExitKey < 180L) {
            return true;
        }

        if (recentDy < -strongUpwardSwipePx) {
            return true;
        }
        if (recentDy < -moderateUpwardSwipePx && dwellOnExitKey < MIN_DWELL_AFTER_UPWARD_SWIPE_MS) {
            return true;
        }
        if (totalDuration < FAST_SWIPE_MAX_DURATION_MS
                && totalDy < -fastUpwardTotalPx
                && Math.abs(totalDy) >= Math.abs(totalDx)) {
            return true;
        }

        if (recentDist > strongSlideDistPx) {
            return true;
        }
        if (recentDist > fastSlideDistPx && dwellOnExitKey < MIN_DWELL_AFTER_FAST_SLIDE_MS) {
            return true;
        }
        float speedDpPerSec = (recentDist / density) * 1000f / recentDt;
        if (recentDist > flickMinDistPx
                && speedDpPerSec > FLICK_SPEED_DP_PER_SEC
                && dwellOnExitKey < MIN_DWELL_AFTER_FAST_SLIDE_MS) {
            return true;
        }

        if (keysVisitedInSession >= 3
                && totalDuration < FAST_SWIPE_MAX_DURATION_MS
                && dwellOnExitKey < MIN_DWELL_AFTER_FAST_SLIDE_MS) {
            return true;
        }

        return false;
    }

    public void handleHover(MotionEvent event) {
        List<Keyboard.Key> keys = layoutManager.getCurrentKeys();
        if (!isLiftToType || keys == null || keys.isEmpty()) {
            lastHoverKeyIndex = -1;
            resetHoverTracking();
            clearHoverExitOwnership();
            return;
        }

        int action = event.getActionMasked();
        View hostView = service.getKeyboardView();
        float rawX = event.getX();
        float rawY = event.getY();
        long eventTime = event.getEventTime();

        float keyX = rawX;
        float keyY = rawY;
        if (hostView != null) {
            keyX -= hostView.getPaddingLeft();
            keyY -= hostView.getPaddingTop();
        }

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
                clearHoverExitOwnership();
                cancelAllLongPress();
                lastHoverKeyIndex = -1;
                resetHoverTracking();
                hoverEnterX = rawX;
                hoverEnterY = rawY;
                hoverEnterTime = eventTime;
                lastKeyEnterTime = eventTime;
                recordHoverSample(rawX, rawY, eventTime);
                // fall through
            case MotionEvent.ACTION_HOVER_MOVE:
                if (action == MotionEvent.ACTION_HOVER_MOVE) {
                    if (sampleCount == 0) {
                        hoverEnterX = rawX;
                        hoverEnterY = rawY;
                        hoverEnterTime = eventTime;
                        lastKeyEnterTime = eventTime;
                    }
                    recordHoverSample(rawX, rawY, eventTime);
                }

                int newKeyIndex = layoutManager.findKeyIndexAt((int) keyX, (int) keyY);
                if (newKeyIndex != lastHoverKeyIndex) {
                    cancelAllLongPress();
                    lastHoverKeyIndex = newKeyIndex;
                    lastKeyEnterTime = eventTime;
                    if (newKeyIndex >= 0) {
                        keysVisitedInSession++;
                    }

                    if (newKeyIndex >= 0 && newKeyIndex < keys.size()) {
                        Keyboard.Key key = keys.get(newKeyIndex);
                        if (key != null && key.codes != null && key.codes.length > 0) {
                            long now = SystemClock.uptimeMillis();
                            if (now - lastFocusHapticTime >= FOCUS_HAPTIC_THROTTLE_MS) {
                                lastFocusHapticTime = now;
                                feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_FOCUS);
                            }

                            int code = key.codes[0];
                            if (code == 32) handler.postDelayed(spaceLongPressTask, 1500);
                            else if (code == -5) handler.postDelayed(deleteStartTask, 1200);
                            else if (code == -1) handler.postDelayed(shiftLongPressTask, 1200);
                            else if (code == -4) handler.postDelayed(enterLongPressTask, 3000);
                            else {
                                int resolvedEmojiCode = resolveEmojiCode(key);
                                if (resolvedEmojiCode != 0) {
                                    currentEmojiCode = resolvedEmojiCode;
                                    handler.postDelayed(emojiLongPressTask, 1000);
                                }
                            }
                        }
                    }
                }
                break;

            case MotionEvent.ACTION_HOVER_EXIT:
                boolean wasLongPress = isLongPressHandled;
                int exitKeyIndex = lastHoverKeyIndex;
                boolean suppressAsSwipe = isSwipeOrEdgeExit(rawX, rawY, eventTime, hostView);

                cancelAllLongPress();
                lastHoverKeyIndex = -1;
                resetHoverTracking();

                if (suppressAsSwipe || keyY < -12) {
                    if (exitKeyIndex >= 0) {
                        markHoverExitHandled(exitKeyIndex);
                    }
                    service.stopSpeech();
                    return;
                }

                if (wasLongPress) {
                    if (exitKeyIndex >= 0) {
                        markHoverExitHandled(exitKeyIndex);
                    }
                    return;
                }

                if (exitKeyIndex >= 0 && exitKeyIndex < keys.size()) {
                    Keyboard.Key key = keys.get(exitKeyIndex);
                    if (key != null && key.codes != null && key.codes.length > 0 && key.codes[0] != -100) {
                        markHoverExitHandled(exitKeyIndex);
                        feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                        service.stopSpeech();
                        service.handleInput(key.codes[0], key);
                    }
                }
                break;
        }
    }

    private int resolveEmojiCode(Keyboard.Key key) {
        if (key == null || key.codes == null || key.codes.length == 0) return 0;
        int code = key.codes[0];
        if (emojiManager != null && emojiManager.hasDescription(code)) {
            return code;
        }
        if (key.label != null && key.label.length() > 0 && emojiManager != null) {
            int labelCode = Character.codePointAt(key.label, 0);
            if (emojiManager.hasDescription(labelCode)) {
                return labelCode;
            }
        }
        return 0;
    }

    public void cancelAllLongPress() {
        isLongPressHandled = false;
        isDeleteActive = false;
        currentEmojiCode = 0;
        handler.removeCallbacks(spaceLongPressTask);
        handler.removeCallbacks(deleteStartTask);
        handler.removeCallbacks(deleteLoopTask);
        handler.removeCallbacks(shiftLongPressTask);
        handler.removeCallbacks(emojiLongPressTask);
        handler.removeCallbacks(enterLongPressTask);
    }

    public void reset() {
        lastHoverKeyIndex = -1;
        resetHoverTracking();
        clearHoverExitOwnership();
        cancelAllLongPress();
    }
}

