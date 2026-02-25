package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.inputmethodservice.Keyboard;
import android.view.MotionEvent;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputMethodManager;
import java.util.List;

public class SaiNawTouchHandler {
    private final SaiNawKeyboardService service;
    private final SaiNawLayoutManager layoutManager;
    private final SaiNawFeedbackManager feedbackManager;
    private final SaiNawEmojiManager emojiManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    private boolean isLiftToType = true;
    private int lastHoverKeyIndex = -1;
    private boolean isLongPressHandled = false;
    private boolean isDeleteActive = false;
    private int currentEmojiCode = 0;

    private final Runnable spaceLongPressTask;
    private final Runnable shiftLongPressTask;
    private final Runnable emojiLongPressTask;
    private final Runnable deleteStartTask;
    private final Runnable deleteLoopTask;

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
    }

    public void handleHover(MotionEvent event) {
        if (!isLiftToType) return;

        int action = event.getAction();
        if (action == MotionEvent.ACTION_HOVER_EXIT) {
            handleHoverExit(event);
            return;
        }

        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
            int newKeyIndex = getNearestKeyIndexFast((int) event.getX(), (int) event.getY());
            if (newKeyIndex != lastHoverKeyIndex) {
                cancelAllLongPress();
                lastHoverKeyIndex = newKeyIndex;
                if (newKeyIndex != -1) {
                    onKeyFocused(newKeyIndex);
                }
            }
        }
    }

    private void onKeyFocused(int index) {
        List<Keyboard.Key> keys = layoutManager.getCurrentKeys();
        if (keys == null || index >= keys.size()) return;

        feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_FOCUS);
        Keyboard.Key key = keys.get(index);
        int code = key.codes[0];

        if (code == 32) handler.postDelayed(spaceLongPressTask, 1200);
        else if (code == -5) handler.postDelayed(deleteStartTask, 1000);
        else if (code == -1) handler.postDelayed(shiftLongPressTask, 1000);
        else {
            int resolvedEmojiCode = resolveEmojiCode(key);
            if (resolvedEmojiCode != 0) {
                currentEmojiCode = resolvedEmojiCode;
                handler.postDelayed(emojiLongPressTask, 800);
            }
        }
    }

    private void handleHoverExit(MotionEvent event) {
        List<Keyboard.Key> keys = layoutManager.getCurrentKeys();
        if (keys == null || lastHoverKeyIndex == -1 || lastHoverKeyIndex >= keys.size()) {
            cancelAllLongPress();
            lastHoverKeyIndex = -1;
            return;
        }

        if (event.getY() >= 0 && !isLongPressHandled) {
            Keyboard.Key key = keys.get(lastHoverKeyIndex);
            if (key.codes[0] != -100) {
                feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                service.handleInput(key.codes[0], key);
            }
        }
        cancelAllLongPress();
        lastHoverKeyIndex = -1;
    }

    private int resolveEmojiCode(Keyboard.Key key) {
        int code = key.codes[0];
        if (emojiManager.hasDescription(code)) return code;
        if (key.label != null && key.label.length() > 0) {
            int labelCode = Character.codePointAt(key.label, 0);
            if (emojiManager.hasDescription(labelCode)) return labelCode;
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
    }
    
    public void reset() { 
        lastHoverKeyIndex = -1; 
        cancelAllLongPress();
    }

    private int getNearestKeyIndexFast(int x, int y) {
        List<Keyboard.Key> keys = layoutManager.getCurrentKeys();
        if (keys == null || y < 0) return -1;

        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key == null || key.codes[0] == -100) continue;

            int left = key.x;
            int top = key.y;
            int right = key.x + key.width;
            int bottom = key.y + key.height;

            if (key.codes[0] == -5) {
                top -= 25; 
                left -= 10;
                right += 10;
            } else if (key.codes[0] != -1 && key.codes[0] != -4 && key.codes[0] != -2 && key.codes[0] != -101) {
                bottom -= 5; 
            }

            if (x >= left && x < right && y >= top && y < bottom) return i;
        }
        return -1;
    }
}

