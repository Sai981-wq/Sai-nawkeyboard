package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.Keyboard;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import java.util.List;

public class SaiNawTouchHandler {
    private final SaiNawKeyboardService service;
    private final SaiNawUIHelper uiHelper;
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    private boolean isLiftToType = true;
    private int lastHoverKeyIndex = -1;
    private boolean isLongPressHandled = false;
    private boolean isDeleteActive = false;

    private final Runnable spaceLongPressTask = new Runnable() {
        @Override
        public void run() {
            isLongPressHandled = true;
            uiHelper.playVibrate(VibrationEffect.EFFECT_HEAVY_CLICK);
            InputMethodManager imeManager = (InputMethodManager) service.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imeManager != null) imeManager.showInputMethodPicker();
        }
    };

    private final Runnable shiftLongPressTask = new Runnable() {
        @Override
        public void run() {
            isLongPressHandled = true;
            uiHelper.playVibrate(VibrationEffect.EFFECT_HEAVY_CLICK);
            uiHelper.announceText("Shift Locked");
            // Shift Lock Logic ကို UIHelper မှာ Public ဖွင့်မပေးထားရင် Toggle နဲ့ပဲ သုံးပါမယ်
            uiHelper.toggleShift(); 
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
                uiHelper.playVibrate(VibrationEffect.EFFECT_TICK);
                service.onRelease(-5); // Service ရဲ့ Release ကို လှမ်းခေါ်မယ်
                handler.postDelayed(this, 80);
            }
        }
    };

    public SaiNawTouchHandler(SaiNawKeyboardService service, SaiNawUIHelper uiHelper) {
        this.service = service;
        this.uiHelper = uiHelper;
    }

    public void loadSettings(SharedPreferences prefs) {
        isLiftToType = prefs.getBoolean("lift_to_type", true);
    }

    public void handleHover(MotionEvent event) {
        if (!isLiftToType || uiHelper.getCurrentKeyboard() == null) {
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

        List<Keyboard.Key> keys = uiHelper.getCurrentKeyboard().getKeys();

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                int newKeyIndex = getNearestKeyIndexFast(x, y, keys);
                if (newKeyIndex != -1 && newKeyIndex != lastHoverKeyIndex) {
                    cancelAllLongPress();
                    lastHoverKeyIndex = newKeyIndex;
                    uiHelper.playVibrate(VibrationEffect.EFFECT_TICK);

                    Keyboard.Key key = keys.get(newKeyIndex);
                    int code = key.codes[0];
                    
                    if (code == 32) { // SPACE
                        handler.postDelayed(spaceLongPressTask, 1500); 
                    } else if (code == -5) { // DELETE
                        handler.postDelayed(deleteStartTask, 800);
                    } else if (code == -1) { // SHIFT
                        handler.postDelayed(shiftLongPressTask, 1500);
                    }
                }
                break;

            case MotionEvent.ACTION_HOVER_EXIT:
                if (y < 0) { cancelAllLongPress(); lastHoverKeyIndex = -1; return; }
                if (!isLongPressHandled) {
                    if (lastHoverKeyIndex != -1 && lastHoverKeyIndex < keys.size()) {
                        Keyboard.Key key = keys.get(lastHoverKeyIndex);
                        if (key.codes[0] != -100) {
                            uiHelper.playVibrate(VibrationEffect.EFFECT_CLICK);
                            service.onRelease(key.codes[0]); // Input
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
        handler.removeCallbacks(spaceLongPressTask);
        handler.removeCallbacks(deleteStartTask);
        handler.removeCallbacks(deleteLoopTask);
        handler.removeCallbacks(shiftLongPressTask);
    }
    
    public void reset() { lastHoverKeyIndex = -1; }

    private int getNearestKeyIndexFast(float x, float y, List<Keyboard.Key> keys) {
        if (keys == null) return -1;
        
        // Check last key first for optimization
        if (lastHoverKeyIndex >= 0 && lastHoverKeyIndex < keys.size()) {
            Keyboard.Key lastKey = keys.get(lastHoverKeyIndex);
            if (lastKey.isInside((int)x, (int)y)) {
                return lastHoverKeyIndex;
            }
        }
        
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key k = keys.get(i);
            if (k.isInside((int)x, (int)y)) {
                return i;
            }
        }
        return -1;
    }
}

