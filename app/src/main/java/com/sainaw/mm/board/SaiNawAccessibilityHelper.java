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

    // Adjusted Threshold: Reduced to -60 to be more sensitive for Shan/Myanmar/Emoji layouts
    private static final int CANCEL_THRESHOLD_Y = -60;

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
        if (!isLiftToType || layoutManager.getCurrentKeys() == null) {
            lastHoverKeyIndex = -1;
            return;
        }

        int action = event.getAction();
        float x = event.getX();
        float y = event.getY();

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                int newKeyIndex = getNearestKeyIndexFast((int) x, (int) y);
                if (newKeyIndex != lastHoverKeyIndex) {
                    cancelAllLongPress();
                    lastHoverKeyIndex = newKeyIndex;
                    
                    if (newKeyIndex != -1) {
                        feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_FOCUS);
                        
                        if (newKeyIndex < layoutManager.getCurrentKeys().size()) {
                            Keyboard.Key key = layoutManager.getCurrentKeys().get(newKeyIndex);
                            int code = key.codes[0];

                            if (code == 32) handler.postDelayed(spaceLongPressTask, 1500);
                            else if (code == -5) handler.postDelayed(deleteStartTask, 1200);
                            else if (code == -1) handler.postDelayed(shiftLongPressTask, 1200);
                            
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
                if (y < CANCEL_THRESHOLD_Y) {
                    cancelAllLongPress();
                    lastHoverKeyIndex = -1;
                    return; 
                }

                if (!isLongPressHandled && lastHoverKeyIndex != -1) {
                    if (lastHoverKeyIndex < layoutManager.getCurrentKeys().size()) {
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

    private int resolveEmojiCode(Keyboard.Key key) {
        int code = key.codes[0];
        
        if (emojiManager.hasDescription(code)) {
            return code;
        }
        
        if (key.label != null && key.label.length() > 0) {
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
    }
    
    public void reset() { 
        lastHoverKeyIndex = -1; 
        cancelAllLongPress();
    }

    private int getNearestKeyIndexFast(int x, int y) {
        if (layoutManager.getCurrentKeys() == null) return -1;
        List<Keyboard.Key> keys = layoutManager.getCurrentKeys();
        
        if (y < CANCEL_THRESHOLD_Y) {
            return -1;
        }

        int touchY = Math.max(0, y);
        int bestKeyIndex = -1;
        int minDistance = Integer.MAX_VALUE;
        int stickinessThreshold = 20;

        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key.codes[0] == -100) continue;

            Rect touchRect = new Rect(key.x, key.y, key.x + key.width, key.y + key.height);

            if (isFunctionalKey(key.codes[0])) {
                touchRect.inset(15, 20, 15, 15);
            } else {
                touchRect.inset(-5, 0, -5, -20);
            }

            if (touchRect.contains(x, touchY)) {
                if (i == lastHoverKeyIndex) {
                    return i;
                }
                
                if (lastHoverKeyIndex != -1 && lastHoverKeyIndex < keys.size() &&
                    isFunctionalKey(key.codes[0]) && 
                    !isFunctionalKey(keys.get(lastHoverKeyIndex).codes[0])) {
                     
                     Keyboard.Key lastKey = keys.get(lastHoverKeyIndex);
                     if (Math.abs(x - lastKey.x) < stickinessThreshold ||
                         Math.abs(touchY - lastKey.y) < stickinessThreshold) {
                         continue;
                     }
                }

                bestKeyIndex = i;
                
                if (!isFunctionalKey(key.codes[0])) {
                    return i;
                }
            }
        }

        if (bestKeyIndex == -1) {
            int maxDist = 50 * 50;
            for (int i = 0; i < keys.size(); i++) {
                Keyboard.Key key = keys.get(i);
                int dist = getDistanceSq(key, x, touchY);
                
                if (isFunctionalKey(key.codes[0])) {
                    dist += 2000;
                }

                if (dist < minDistance && dist < maxDist) {
                    minDistance = dist;
                    bestKeyIndex = i;
                }
            }
        }
        
        return bestKeyIndex;
    }

    private boolean isFunctionalKey(int code) {
        return code == -5 || code == -1 || code == -4 || code == -2 || code == -101;
    }

    private int getDistanceSq(Keyboard.Key key, int x, int y) {
        int dx = 0;
        int dy = 0;
        if (x < key.x) dx = key.x - x;
        else if (x > key.x + key.width) dx = x - (key.x + key.width);
        
        if (y < key.y) dy = key.y - y;
        else if (y > key.y + key.height) dy = y - (key.y + key.height);
        
        return (dx * dx) + (dy * dy);
    }
}

