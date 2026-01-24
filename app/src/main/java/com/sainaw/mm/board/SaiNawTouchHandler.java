package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
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

    // Runnables
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

        // --- Emoji Long Press Logic ---
        this.emojiLongPressTask = () -> {
            if (currentEmojiCode != 0) {
                String desc = emojiManager.getMmDescription(currentEmojiCode);
                if (desc != null) {
                    isLongPressHandled = true; // လက်ကြွရင် စာမရိုက်တော့ပါ (Cancel)
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_LONG_PRESS);
                    service.announceText(desc); // မြန်မာလို ဖတ်ပြမည်
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
                        
                        Keyboard.Key key = layoutManager.getCurrentKeys().get(newKeyIndex);
                        int code = key.codes[0];

                        if (code == 32) handler.postDelayed(spaceLongPressTask, 1500);
                        else if (code == -5) handler.postDelayed(deleteStartTask, 1200);
                        else if (code == -1) handler.postDelayed(shiftLongPressTask, 1200);
                        
                        else {
                            // *** FIX: Label ကနေ Code ရှာပြီးမှ စစ်ဆေးခြင်း ***
                            int resolvedEmojiCode = resolveEmojiCode(key);
                            if (resolvedEmojiCode != 0) {
                                currentEmojiCode = resolvedEmojiCode;
                                // 1.5 စက္ကန့် ဖိထားမှ အလုပ်လုပ်မည်
                                handler.postDelayed(emojiLongPressTask, 1500);
                            }
                        }
                    }
                }
                break;

            case MotionEvent.ACTION_HOVER_EXIT:
                if (!isLongPressHandled && lastHoverKeyIndex != -1 && y >= 0) {
                    if (lastHoverKeyIndex < layoutManager.getCurrentKeys().size()) {
                        Keyboard.Key key = layoutManager.getCurrentKeys().get(lastHoverKeyIndex);
                        if (key.codes[0] != -100) {
                            service.handleInput(key.codes[0], key);
                        }
                    }
                }
                cancelAllLongPress();
                lastHoverKeyIndex = -1;
                break;
        }
    }

    // *** Helper Function: Emoji Code ရှာဖွေခြင်း ***
    private int resolveEmojiCode(Keyboard.Key key) {
        int code = key.codes[0];
        
        // 1. Code အတိုင်း Mapping ရှိလား စစ်မယ်
        if (emojiManager.hasDescription(code)) {
            return code;
        }
        
        // 2. မရှိရင် Label (ဥပမာ "😀") ကနေ Code ပြန်ထုတ်ပြီး စစ်မယ်
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
        
        if (lastHoverKeyIndex >= 0 && lastHoverKeyIndex < layoutManager.getCurrentKeys().size()) {
            Keyboard.Key lastKey = layoutManager.getCurrentKeys().get(lastHoverKeyIndex);
            if (lastKey.isInside(x, y)) return lastHoverKeyIndex;
        }

        List<Keyboard.Key> keys = layoutManager.getCurrentKeys();
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key k = keys.get(i);
            if (k.isInside(x, y)) return (k.codes[0] == -100) ? -1 : i;
        }
        return -1;
    }
}

