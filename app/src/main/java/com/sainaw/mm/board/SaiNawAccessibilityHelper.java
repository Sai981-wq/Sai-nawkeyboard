package com.sainaw.mm.board;

import android.graphics.Rect;
import android.inputmethodservice.Keyboard;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import java.util.List;

public class SaiNawAccessibilityHelper extends ExploreByTouchHelper {
    private final View view;
    private Keyboard currentKeyboard;
    private boolean isShanOrMyanmar = false;
    private boolean isCaps = false;
    private boolean isPhoneticEnabled = true; // Setting variable
    private OnAccessibilityKeyListener listener;
    private SaiNawPhoneticManager phoneticManager;

    public interface OnAccessibilityKeyListener {
        void onAccessibilityKeyClick(int primaryCode, Keyboard.Key key);
    }

    // Constructor accepts PhoneticManager
    public SaiNawAccessibilityHelper(@NonNull View view, OnAccessibilityKeyListener listener, SaiNawPhoneticManager manager) {
        super(view);
        this.view = view;
        this.listener = listener;
        this.phoneticManager = manager;
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
        int closestIndex = HOST_ID;
        int minDistSq = Integer.MAX_VALUE; 
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key.codes[0] == -100) continue;
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
        return closestIndex;
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
        int right = key.x + key.width;
        int bottom = key.y + key.height;
        if (right <= 0 || bottom <= 0) node.setBoundsInParent(new Rect(0,0,1,1));
        else node.setBoundsInParent(new Rect(key.x, key.y, right, bottom));
    }

    @Override
    protected boolean onPerformActionForVirtualView(int virtualViewId, int action, @Nullable Bundle arguments) {
        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            if (currentKeyboard != null) {
                List<Keyboard.Key> keys = currentKeyboard.getKeys();
                if (keys != null && virtualViewId >= 0 && virtualViewId < keys.size()) {
                    Keyboard.Key key = keys.get(virtualViewId);
                    if (listener != null) listener.onAccessibilityKeyClick(key.codes[0], key);
                    return true;
                }
            }
        }
        return false; 
    }

    private String getKeyDescription(Keyboard.Key key) {
        int code = key.codes[0];

        // Dynamic Label logic
        if (code == -4 && key.label != null) return key.label.toString();

        // 1. If Phonetic Sounds are enabled, try mapping
        if (isPhoneticEnabled) {
            String phonetic = phoneticManager.getPronunciation(code);
            // If mapping exists, return it (e.g., "ကကြီး")
            if (!phonetic.equals(String.valueOf((char)code))) {
                return phonetic;
            }
        }

        // 2. Fallback to standard labels
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
