package com.sainaw.mm.board;

import android.content.SharedPreferences;
import android.inputmethodservice.Keyboard;
import android.view.MotionEvent;
import android.os.Handler;
import android.os.Looper;

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

    // Long Press Runnables
    private final Runnable spaceLongPressTask = () -> {
        isLongPressHandled = true;
        feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_LONG_PRESS);
        service.showInputMethodPicker();
    };

    private final Runnable shiftLongPressTask = () -> {
        isLongPressHandled = true;
        layoutManager.isCapsLocked = true;
        layoutManager.isCaps = true;
        feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_LONG_PRESS);
        layoutManager.updateKeyboardLayout();
        service.announceText("Shift Locked");
    };

    private final Runnable emojiLongPressTask = () -> {
        if (currentEmojiCode != 0) {
            String desc = emojiManager.getMmDescription(currentEmojiCode);
            if (desc != null) {
                isLongPressHandled = true;
                feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_LONG_PRESS);
                service.announceText(desc);
            }
        }
    };

    private final Runnable deleteStartTask = () -> {
        isLongPressHandled = true;
        isDeleteActive = true;
        handler.post(deleteLoopTask);
    };

    private final Runnable deleteLoopTask = new Runnable() {
        @Override
        public void run() {
            if (isDeleteActive) {
                feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                service.handleInput(-5, null);
                handler.postDelayed(this, 100);
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
        // Setting ကနေ Lift to Type အဖွင့်အပိတ်ကို ဖတ်ပါမယ်
        isLiftToType = prefs.getBoolean("lift_to_type", true);
    }

    public void handleHover(MotionEvent event) {
        // Lift to Type ပိတ်ထားရင် ဘာမှ ဆက်မလုပ်ပါ (Double tap ကို AccessibilityHelper က လုပ်ပါလိမ့်မယ်)
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
                        // လက်တင်လိုက်ရင် အသံမမြည်စေတော့ပါ (AccessibilityHelper က ဖတ်ပေးမှာမို့လို့)
                        // feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_FOCUS); 
                        
                        Keyboard.Key key = layoutManager.getCurrentKeys().get(newKeyIndex);
                        int code = key.codes[0];

                        // Long press logic
                        if (code == 32) handler.postDelayed(spaceLongPressTask, 1500);
                        else if (code == -5) handler.postDelayed(deleteStartTask, 1200);
                        else if (code == -1) handler.postDelayed(shiftLongPressTask, 1200);
                        else if (emojiManager.hasDescription(code)) {
                            currentEmojiCode = code;
                            handler.postDelayed(emojiLongPressTask, 800);
                        }
                    }
                }
                break;

            case MotionEvent.ACTION_HOVER_EXIT:
                if (!isLongPressHandled && lastHoverKeyIndex != -1 && y >= 0) {
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
        
        // အရင်ဆုံး လက်ရှိ Key ပေါ်မှာပဲ ရှိနေသေးလား စစ် (Performance ကောင်းအောင်)
        if (lastHoverKeyIndex >= 0 && lastHoverKeyIndex < layoutManager.getCurrentKeys().size()) {
            Keyboard.Key lastKey = layoutManager.getCurrentKeys().get(lastHoverKeyIndex);
            if (lastKey.isInside(x, y)) return lastHoverKeyIndex;
        }

        // မဟုတ်မှ Loop ပတ်ရှာ
        List<Keyboard.Key> keys = layoutManager.getCurrentKeys();
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key k = keys.get(i);
            if (k.isInside(x, y)) return (k.codes[0] == -100) ? -1 : i;
        }
        return -1;
    }
}

