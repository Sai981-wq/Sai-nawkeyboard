package com.sainaw.mm.board;

import android.content.Context;
import android.graphics.Rect;
import android.inputmethodservice.Keyboard;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import java.util.List;

public class SaiNawAccessibilityHelper extends ExploreByTouchHelper {
    private final Vibrator vibrator;
    private final AccessibilityManager accessibilityManager;
    private final Rect tempRect = new Rect();
    private Keyboard currentKeyboard;
    private boolean isShanOrMyanmar = false;
    private boolean isCaps = false;
    private boolean isPhoneticEnabled = true;
    private OnAccessibilityKeyListener listener;
    private SaiNawPhoneticManager phoneticManager;
    private SaiNawEmojiManager emojiManager;
    private int lastFoundIndex = HOST_ID;

    public interface OnAccessibilityKeyListener {
        void onAccessibilityKeyClick(int primaryCode, Keyboard.Key key);
    }

    public SaiNawAccessibilityHelper(@NonNull View view, OnAccessibilityKeyListener listener, 
                                     SaiNawPhoneticManager manager, SaiNawEmojiManager emojiManager) {
        super(view);
        this.listener = listener;
        this.phoneticManager = manager;
        this.emojiManager = emojiManager;
        this.vibrator = (Vibrator) view.getContext().getSystemService(Context.VIBRATOR_SERVICE);
        this.accessibilityManager = (AccessibilityManager) view.getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    public void setKeyboard(Keyboard keyboard, boolean isShanOrMyanmar, boolean isCaps) {
        this.currentKeyboard = keyboard;
        this.isShanOrMyanmar = isShanOrMyanmar;
        this.isCaps = isCaps;
        invalidateRoot();
        sendEventForVirtualView(HOST_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    public void setPhoneticEnabled(boolean enabled) {
        this.isPhoneticEnabled = enabled;
    }

    @Override
    protected int getVirtualViewAt(float x, float y) {
        if (currentKeyboard == null) return HOST_ID;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || keys.isEmpty()) return HOST_ID;
        return getNearestKeyIndex((int) x, (int) y);
    }

    private int getNearestKeyIndex(int x, int y) {
        if (currentKeyboard == null) return HOST_ID;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || keys.isEmpty()) return HOST_ID;
        
        int touchY = y;
        int bestKeyIndex = HOST_ID;
        int minDistance = Integer.MAX_VALUE;
        int stickinessThreshold = 20;
        int verticalSlipTolerance = 40;

        int size = keys.size();
        for (int i = 0; i < size; i++) {
            if (i >= keys.size()) break;
            
            Keyboard.Key key = keys.get(i);
            if (key == null || key.codes == null || key.codes.length == 0 || key.codes[0] == -100) continue;

            Rect touchRect = new Rect(key.x, key.y, key.x + key.width, key.y + key.height);

            if (isFunctionalKey(key.codes[0])) {
                touchRect.inset(15, 20, 15, 15);
            } else {
                if (key.y < 10) {
                    touchRect.top -= verticalSlipTolerance; 
                    touchRect.inset(-5, 0, -5, -20);
                } else {
                    touchRect.inset(-5, 0, -5, -20);
                }
            }

            if (touchRect.contains(x, touchY)) {
                if (i == lastFoundIndex) {
                    return i;
                }
                
                if (lastFoundIndex != HOST_ID && lastFoundIndex < keys.size()) {
                    Keyboard.Key lastKey = keys.get(lastFoundIndex);
                    if (lastKey != null && isFunctionalKey(key.codes[0]) && !isFunctionalKey(lastKey.codes[0])) {
                         if (Math.abs(x - lastKey.x) < stickinessThreshold ||
                             Math.abs(touchY - lastKey.y) < stickinessThreshold) {
                             continue;
                         }
                    }
                }

                bestKeyIndex = i;
                
                if (!isFunctionalKey(key.codes[0])) {
                    lastFoundIndex = i;
                    return i;
                }
            }
        }

        if (bestKeyIndex == HOST_ID) {
            if (y < -verticalSlipTolerance) {
                return HOST_ID;
            }

            int maxDist = 50 * 50;
            for (int i = 0; i < size; i++) {
                if (i >= keys.size()) break;
                
                Keyboard.Key key = keys.get(i);
                if (key == null || key.codes == null || key.codes.length == 0) continue;

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
        
        lastFoundIndex = bestKeyIndex;
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

    @Override
    protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
        if (currentKeyboard == null) return;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null) return;
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).codes[0] != -100) virtualViewIds.add(i);
        }
    }

    @Override
    protected void onPopulateNodeForVirtualView(int virtualViewId, @NonNull AccessibilityNodeInfoCompat node) {
        if (currentKeyboard == null) {
            node.setContentDescription("");
            node.setBoundsInParent(new Rect(0, 0, 1, 1));
            return;
        }
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || virtualViewId < 0 || virtualViewId >= keys.size()) {
            node.setContentDescription("");
            node.setBoundsInParent(new Rect(0, 0, 1, 1));
            return;
        }
        Keyboard.Key key = keys.get(virtualViewId);
        String description = getKeyDescription(key);
        node.setContentDescription(description);
        
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
        node.setClickable(true);
        
        node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
        node.setLongClickable(true);
        
        int right = key.x + key.width;
        int bottom = key.y + key.height;
        if (right <= 0 || bottom <= 0) {
            tempRect.set(0, 0, 1, 1);
        } else {
            tempRect.set(key.x, key.y, right, bottom);
        }
        node.setBoundsInParent(tempRect);
    }

    @Override
    protected boolean onPerformActionForVirtualView(int virtualViewId, int action, @Nullable Bundle arguments) {
        if (currentKeyboard == null) return false;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || virtualViewId < 0 || virtualViewId >= keys.size()) return false;
        
        Keyboard.Key key = keys.get(virtualViewId);

        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            forceHapticFeedback(key.codes[0]);
            if (listener != null) {
                listener.onAccessibilityKeyClick(key.codes[0], key);
            }
            return true;
        } else if (action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK) {
            int emojiCode = resolveEmojiCode(key);
            if (emojiCode != 0 && emojiManager != null) {
                String desc = emojiManager.getMmDescription(emojiCode);
                if (desc != null) {
                    performLongPressFeedback();
                    announceTextImmediate(desc);
                    return true;
                }
            }
             if (key.codes[0] == -1 || key.codes[0] == -5) {
                 performLongPressFeedback();
                 return true;
             }
        }
        return false;
    }

    private void announceTextImmediate(String text) {
        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }

    private int resolveEmojiCode(Keyboard.Key key) {
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

    private void performLongPressFeedback() {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(80);
        }
    }

    private void forceHapticFeedback(int keyCode) {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                int effectId;
                if (keyCode == -5 || keyCode == 32 || keyCode == -4 || keyCode == 10) {
                    effectId = VibrationEffect.EFFECT_HEAVY_CLICK;
                } else {
                    effectId = VibrationEffect.EFFECT_CLICK;
                }
                vibrator.vibrate(VibrationEffect.createPredefined(effectId));
            } catch (Exception e) {
                vibrator.vibrate(VibrationEffect.createOneShot(40, 200));
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                long duration;
                int amplitude;
                
                if (keyCode == -5 || keyCode == 32 || keyCode == -4) {
                    duration = 50; 
                    amplitude = 255; 
                } else {
                    duration = 35; 
                    amplitude = 180; 
                }
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
            } catch (Exception e) {
                vibrator.vibrate(40);
            }
        } else {
            vibrator.vibrate(40);
        }
    }

    private String getKeyDescription(Keyboard.Key key) {
        int code = key.codes[0];

        if (code == -4 && key.label != null) return key.label.toString();

        if (isPhoneticEnabled) {
            String phonetic = phoneticManager.getPronunciation(code);
            if (phonetic != null && !phonetic.equals(String.valueOf((char)code))) {
                return phonetic;
            }
        }

        if (code == -5) return "Delete";
        if (code == -1) return isCaps ? "Shift On" : "Shift";
        if (code == 32) return "Space";
        if (code == -2) return "Symbol Keyboard";
        if (code == -6) return "Alphabet Keyboard";
        if (code == -101) return "Switch Language";
        if (code == -10) return "Voice Typing";
        if (code == -100) return ""; 

        String label = null;
        if (key.label != null) label = key.label.toString();
        else if (key.text != null) label = key.text.toString();

        if (!isShanOrMyanmar && isCaps && label != null && label.length() == 1 && Character.isLetter(label.charAt(0))) {
             return "Capital " + label;
        }

        return label != null ? label : "Unlabeled Key";
    }
}

