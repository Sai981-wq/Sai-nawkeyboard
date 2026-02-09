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
        List<Keyboard.Key> keys = layoutManager.getCurrentKeys();
        if (!isLiftToType || keys == null || keys.isEmpty()) {
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
                    
                    if (newKeyIndex != -1 && newKeyIndex < keys.size()) {
                        feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_FOCUS);
                        
                        Keyboard.Key key = keys.get(newKeyIndex);
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
                break;

            case MotionEvent.ACTION_HOVER_EXIT:
                // y < 0 ဖြစ်တာနဲ့ ချက်ချင်း Cancel လုပ်မယ် (QWERTY လိုမျိုး)
                if (y < 0) {
                    cancelAllLongPress();
                    lastHoverKeyIndex = -1;
                    return; 
                }

                if (!isLongPressHandled && lastHoverKeyIndex != -1) {
                    if (lastHoverKeyIndex < keys.size()) {
                        Keyboard.Key key = keys.get(lastHoverKeyIndex);
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
        List<Keyboard.Key> keys = layoutManager.getCurrentKeys();
        if (keys == null || keys.isEmpty()) return -1;
        
        // Strict Check: အပေါ်ဘောင်ကျော်တာနဲ့ ချက်ချင်း -1 ပြန်မယ်
        if (y < 0) return -1;

        int bestKeyIndex = -1;
        // Tolerance မသုံးတော့ပါ (Strict Touch)
        int stickinessThreshold = 20;

        int size = keys.size();
        for (int i = 0; i < size; i++) {
            if (i >= keys.size()) break;

            Keyboard.Key key = keys.get(i);
            if (key == null || key.codes[0] == -100) continue;

            Rect touchRect = new Rect(key.x, key.y, key.x + key.width, key.y + key.height);

            if (isFunctionalKey(key.codes[0])) {
                touchRect.inset(15, 20, 15, 15);
            } else {
                if (key.y < 10) {
                    // Top Row အတွက် အပေါ်ဘက်ကို လုံးဝ မချဲ့တော့ပါ (Strict Top Boundary)
                    // ဒါမှ Slide Up လုပ်ရင် ချက်ချင်းလွတ်သွားမှာ ဖြစ်ပါတယ်
                    touchRect.inset(-5, 0, -5, -20);
                } else {
                    touchRect.inset(-5, 0, -5, -20);
                }
            }

            if (touchRect.contains(x, y)) {
                if (i == lastHoverKeyIndex) {
                    return i;
                }
                
                if (lastHoverKeyIndex != -1 && lastHoverKeyIndex < keys.size() &&
                    isFunctionalKey(key.codes[0]) && 
                    !isFunctionalKey(keys.get(lastHoverKeyIndex).codes[0])) {
                     
                     Keyboard.Key lastKey = keys.get(lastHoverKeyIndex);
                     if (Math.abs(x - lastKey.x) < stickinessThreshold ||
                         Math.abs(y - lastKey.y) < stickinessThreshold) {
                         continue;
                     }
                }

                bestKeyIndex = i;
                
                if (!isFunctionalKey(key.codes[0])) {
                    return i;
                }
            }
        }
        
        // အကယ်၍ ဘယ် Key မှ မထိရင် -1 ပဲ ပြန်မယ် (Fallback Distance Check မလုပ်တော့ပါ)
        // ဒါမှ Cancel လုပ်ချင်လို့ လွတ်နေတဲ့နေရာ ဆွဲလိုက်ရင် ဘေးကကောင် ပါမလာမှာပါ
        return bestKeyIndex;
    }

    private boolean isFunctionalKey(int code) {
        return code == -5 || code == -1 || code == -4 || code == -2 || code == -101;
    }
}

