package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.Keyboard;
import android.view.MotionEvent;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputMethodManager;

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

    private final Runnable spaceLongPressTask = new Runnable() {
        @Override
        public void run() {
            isLongPressHandled = true;
            feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_LONG_PRESS);
            InputMethodManager imeManager = (InputMethodManager) service.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imeManager != null) imeManager.showInputMethodPicker();
        }
    };

    private final Runnable shiftLongPressTask = new Runnable() {
        @Override
        public void run() {
            isLongPressHandled = true;
            layoutManager.isCapsLocked = true;
            layoutManager.isCaps = true;
            feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_LONG_PRESS);
            layoutManager.updateKeyboardLayout();
            service.announceText("Shift Locked");
        }
    };

    private final Runnable emojiLongPressTask = new Runnable() {
        @Override
        public void run() {
            if (currentEmojiCode != 0) {
                String desc = emojiManager.getMmDescription(currentEmojiCode);
                if (desc != null) {
                    isLongPressHandled = true;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_LONG_PRESS);
                    service.announceText(desc);
                }
            }
        }
    };

    private final Runnable deleteStartTask = new Runnable() {
        @Override
        public void run() {
            isLongPressHandled = true;
            isDeleteActive = true;
            handler.post(deleteLoopTask);
        }
    };

    private final Runnable deleteLoopTask = new Runnable() {
        @Override
        public void run() {
            if (isDeleteActive) {
                feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                service.handleInput(-5, null);
                handler.postDelayed(this, 80);
            }
        }
    };

    public SaiNawTouchHandler(SaiNawKeyboardService service, 
                              SaiNawLayoutManager layoutManager, 
                              SaiNawFeedbackManager feedbackManager,
                              SaiNawEmojiManager emojiManager) {
        this.service = service;
        this.layoutManager = layoutManager;
        this.feedbackManager = feedbackManager;
        this.emojiManager = emojiManager;
    }

    public void loadSettings(SharedPreferences prefs) {
        isLiftToType = prefs.getBoolean("lift_to_type", true);
    }

    public void handleHover(MotionEvent event) {
        if (!isLiftToType || layoutManager.getCurrentKeys() == null) {
            lastHoverKeyIndex = -1;
            return;
        }

        int action = event.getAction();
        float x = event.getX();
        float y = event.getY();

        if (y < 0) {
            cancelAllLongPress();
            lastHoverKeyIndex = -1;
            return;
        }

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                int newKeyIndex = getNearestKeyIndexFast((int) x, (int) y);
                if (newKeyIndex != -1 && newKeyIndex != lastHoverKeyIndex) {
                    cancelAllLongPress();
                    lastHoverKeyIndex = newKeyIndex;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_FOCUS);

                    Keyboard.Key key = layoutManager.getCurrentKeys().get(newKeyIndex);
                    int code = key.codes[0];

                    if (code == 32) { 
                        handler.postDelayed(spaceLongPressTask, 3000); 
                    } else if (code == -5) {
                        handler.postDelayed(deleteStartTask, 2000);
                    } else if (code == -1) {
                        handler.postDelayed(shiftLongPressTask, 2000);
                    } else if (emojiManager.hasDescription(code)) {
                        currentEmojiCode = code;
                        handler.postDelayed(emojiLongPressTask, 1000); 
                    }
                }
                break;

            case MotionEvent.ACTION_HOVER_EXIT:
                if (y < 0) { cancelAllLongPress(); lastHoverKeyIndex = -1; return; }
                if (!isLongPressHandled) {
                    if (lastHoverKeyIndex != -1 && lastHoverKeyIndex < layoutManager.getCurrentKeys().size()) {
                        Keyboard.Key key = layoutManager.getCurrentKeys().get(lastHoverKeyIndex);
                        if (key.codes[0] != -100) {
                            feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                            service.handleInput(key.codes[0], key);
                        }
                    }
                }
                cancelAllLongPress();
                lastHoverKeyIndex = -1;
                break;
        }
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
    
    public void reset() { lastHoverKeyIndex = -1; }

    private int getNearestKeyIndexFast(int x, int y) {
        if (layoutManager.getCurrentKeys() == null) return -1;
        if (lastHoverKeyIndex >= 0 && lastHoverKeyIndex < layoutManager.getCurrentKeys().size()) {
            Keyboard.Key lastKey = layoutManager.getCurrentKeys().get(lastHoverKeyIndex);
            if (lastKey.isInside(x, y)) {
                if (lastKey.codes[0] == -100) return -1;
                return lastHoverKeyIndex;
            }
        }
        for (int i = 0; i < layoutManager.getCurrentKeys().size(); i++) {
            Keyboard.Key k = layoutManager.getCurrentKeys().get(i);
            if (k.isInside(x, y)) {
                if (k.codes[0] == -100) return -1;
                return i;
            }
        }
        return -1;
    }
}

