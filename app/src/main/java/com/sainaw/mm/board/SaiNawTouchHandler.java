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

    private final SaiNawKeyboardService service;
    private final SaiNawLayoutManager layoutManager;
    private final SaiNawFeedbackManager feedbackManager;
    private final SaiNawEmojiManager emojiManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int lastHoverKeyIndex = -1;
    private boolean isLongPressHandled = false;
    private boolean consumeNextA11yClick = false;
    private boolean isDeleteActive = false;
    private int currentEmojiCode = 0;
    private long lastFocusHapticTime = 0L;

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
        // Screen Reader typing mode (lift-to-type vs double-tap) is natively controlled by TalkBack/CSR via ACTION_CLICK.
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

    public boolean consumeHoverLongPressClick() {
        if (consumeNextA11yClick) {
            consumeNextA11yClick = false;
            return true;
        }
        return false;
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

    /**
     * Handles hover focus haptics and hold-to-long-press during Screen Reader exploration.
     * Key activation itself is never triggered on ACTION_HOVER_EXIT; TalkBack and CSR own
     * key typing strictly through ACTION_CLICK, eliminating double-typing and swipe triggers.
     */
    public void handleHover(MotionEvent event) {
        List<Keyboard.Key> keys = layoutManager.getCurrentKeys();
        if (keys == null || keys.isEmpty()) {
            lastHoverKeyIndex = -1;
            return;
        }

        int action = event.getActionMasked();
        View hostView = service.getKeyboardView();
        float keyX = event.getX();
        float keyY = event.getY();
        if (hostView != null) {
            keyX -= hostView.getPaddingLeft();
            keyY -= hostView.getPaddingTop();
        }

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
                consumeNextA11yClick = false;
                cancelAllLongPress();
                lastHoverKeyIndex = -1;
                // fall through
            case MotionEvent.ACTION_HOVER_MOVE:
                int newKeyIndex = layoutManager.findKeyIndexAt((int) keyX, (int) keyY);
                if (newKeyIndex != lastHoverKeyIndex) {
                    cancelAllLongPress();
                    lastHoverKeyIndex = newKeyIndex;

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
                cancelAllLongPress();
                lastHoverKeyIndex = -1;
                if (wasLongPress) {
                    consumeNextA11yClick = true;
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
        consumeNextA11yClick = false;
        cancelAllLongPress();
    }
}

