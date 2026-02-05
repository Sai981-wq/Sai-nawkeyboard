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
    private static final int KEY_DELETE = -5;
    private static final int KEY_SHIFT = -1;
    private static final int KEY_SPACE = 32;
    private static final int KEY_ENTER = -4;
    private static final int KEY_SYMBOL = -2;
    private static final int KEY_ALPHABET = -6;
    private static final int KEY_VOICE = -10;
    private static final int KEY_SWITCH_LANG = -101;
    private static final int KEY_NONE = -100;

    private final Vibrator vibrator;
    private final AccessibilityManager accessibilityManager;
    private final Rect tempRect = new Rect();
    private final int thresholdSq;

    private Keyboard currentKeyboard;
    private boolean isShanOrMyanmar = false;
    private boolean isCaps = false;
    private boolean isPhoneticEnabled = true;
    private final OnAccessibilityKeyListener listener;
    private final SaiNawPhoneticManager phoneticManager;
    private final SaiNawEmojiManager emojiManager;

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

        float density = view.getContext().getResources().getDisplayMetrics().density;
        int thresholdPx = (int) (60 * density);
        this.thresholdSq = thresholdPx * thresholdPx;
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
        return getNearestKeyIndex((int) x, (int) y);
    }

    private int getNearestKeyIndex(int x, int y) {
        if (currentKeyboard == null) return HOST_ID;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || keys.isEmpty()) return HOST_ID;

        int closestIndex = HOST_ID;
        int minDistSq = Integer.MAX_VALUE;

        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key.codes[0] == KEY_NONE) continue;
            
            int keyCenterX = key.x + (key.width / 2);
            int keyCenterY = key.y + (key.height / 2);
            int dx = x - keyCenterX;
            int dy = y - keyCenterY;
            int distSq = (dx * dx) + (dy * dy);
            
            if (distSq < minDistSq) {
                minDistSq = distSq;
                closestIndex = i;
            }
        }
        return (minDistSq <= thresholdSq) ? closestIndex : HOST_ID;
    }

    @Override
    protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
        if (currentKeyboard == null) return;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null) return;
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).codes[0] != KEY_NONE) virtualViewIds.add(i);
        }
    }

    @Override
    protected void onPopulateNodeForVirtualView(int virtualViewId, @NonNull AccessibilityNodeInfoCompat node) {
        if (currentKeyboard == null) {
            setEmptyNode(node);
            return;
        }
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || virtualViewId < 0 || virtualViewId >= keys.size()) {
            setEmptyNode(node);
            return;
        }

        Keyboard.Key key = keys.get(virtualViewId);
        node.setContentDescription(getKeyDescription(key));
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
        node.setClickable(true);
        node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
        node.setLongClickable(true);
        
        int right = key.x + key.width;
        int bottom = key.y + key.height;
        tempRect.set(key.x, key.y, right > 0 ? right : 1, bottom > 0 ? bottom : 1);
        node.setBoundsInParent(tempRect);
    }

    private void setEmptyNode(AccessibilityNodeInfoCompat node) {
        node.setContentDescription("");
        node.setBoundsInParent(new Rect(0, 0, 1, 1));
    }

    @Override
    protected boolean onPerformActionForVirtualView(int virtualViewId, int action, @Nullable Bundle arguments) {
        if (currentKeyboard == null) return false;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || virtualViewId < 0 || virtualViewId >= keys.size()) return false;
        
        Keyboard.Key key = keys.get(virtualViewId);
        int code = key.codes[0];

        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            forceHapticFeedback(code);
            if (listener != null) {
                listener.onAccessibilityKeyClick(code, key);
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
             if (code == KEY_SHIFT || code == KEY_DELETE) {
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
        if (vibrator == null || !vibrator.hasVibrator()) return;

        boolean isHeavy = (keyCode == KEY_DELETE || keyCode == KEY_SPACE || keyCode == KEY_ENTER || keyCode == KEY_VOICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                int effectId = isHeavy ? VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_CLICK;
                vibrator.vibrate(VibrationEffect.createPredefined(effectId));
            } catch (Exception e) {
                vibrator.vibrate(VibrationEffect.createOneShot(40, 200));
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(isHeavy ? 50 : 35, isHeavy ? 255 : 180));
        } else {
            vibrator.vibrate(40);
        }
    }

    private String getKeyDescription(Keyboard.Key key) {
        int code = key.codes[0];

        if (code == KEY_ENTER && key.label != null) return key.label.toString();

        if (isPhoneticEnabled) {
            String phonetic = phoneticManager.getPronunciation(code);
            if (phonetic != null && !phonetic.equals(String.valueOf((char)code))) {
                return phonetic;
            }
        }

        switch (code) {
            case KEY_DELETE: return "Delete";
            case KEY_SHIFT: return isCaps ? "Shift On" : "Shift";
            case KEY_SPACE: return "Space";
            case KEY_SYMBOL: return "Symbol Keyboard";
            case KEY_ALPHABET: return "Alphabet Keyboard";
            case KEY_SWITCH_LANG: return "Switch Language";
            case KEY_VOICE: return "Voice Typing";
            case KEY_NONE: return "";
        }

        String label = (key.label != null) ? key.label.toString() : 
                      (key.text != null) ? key.text.toString() : null;

        if (!isShanOrMyanmar && isCaps && label != null && label.length() == 1 && Character.isLetter(label.charAt(0))) {
             return "Capital " + label;
        }

        return label != null ? label : "Unlabeled Key";
    }
}

