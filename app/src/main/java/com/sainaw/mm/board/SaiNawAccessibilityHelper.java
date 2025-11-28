package com.sainaw.mm.board;

import android.graphics.Rect;
import android.inputmethodservice.Keyboard;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
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
    private OnAccessibilityKeyListener listener;
    
    // Performance: Reuse Rect to prevent Garbage Collection lag
    private final Rect mTempRect = new Rect();
    
    // Snap Threshold (100px squared) - Don't snap if finger is too far
    private static final int MAX_SNAP_DISTANCE_SQ = 10000; 

    public interface OnAccessibilityKeyListener {
        void onAccessibilityKeyClick(int primaryCode, Keyboard.Key key);
    }

    public SaiNawAccessibilityHelper(@NonNull View view, OnAccessibilityKeyListener listener) {
        super(view);
        this.view = view;
        this.listener = listener;
    }

    public void setKeyboard(Keyboard keyboard, boolean isShanOrMyanmar, boolean isCaps) {
        this.currentKeyboard = keyboard;
        this.isShanOrMyanmar = isShanOrMyanmar;
        this.isCaps = isCaps;
        invalidateRoot();
        view.post(() -> sendEventForVirtualView(HOST_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    }

    @Override
    protected int getVirtualViewAt(float x, float y) {
        if (currentKeyboard == null) return HOST_ID;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || keys.isEmpty()) return HOST_ID;

        // 1. Strict Check
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key.isInside((int) x, (int) y)) {
                if (key.codes[0] == -100) return HOST_ID; 
                return i;
            }
        }

        // 2. Nearest Key (Snap) with Distance Limit
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
        
        // Check threshold
        if (minDistSq > MAX_SNAP_DISTANCE_SQ) {
            return HOST_ID; 
        }
        
        return closestIndex;
    }

    @Override
    protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
        if (currentKeyboard == null) return;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null) return;

        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).codes[0] != -100) {
                virtualViewIds.add(i);
            }
        }
    }

    @Override
    protected void onPopulateNodeForVirtualView(int virtualViewId, @NonNull AccessibilityNodeInfoCompat node) {
        mTempRect.set(0, 0, 1, 1);

        if (currentKeyboard == null) {
            node.setContentDescription("");
            node.setBoundsInParent(mTempRect);
            return;
        }
        
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        
        if (keys == null || virtualViewId < 0 || virtualViewId >= keys.size()) {
            node.setContentDescription("");
            node.setBoundsInParent(mTempRect);
            return;
        }
        
        Keyboard.Key key = keys.get(virtualViewId);
        String description = getKeyDescription(key);

        node.setContentDescription(description);
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
        node.setClickable(true);
        
        int right = key.x + key.width;
        int bottom = key.y + key.height;
        
        if (right <= key.x || bottom <= key.y) {
             mTempRect.set(0, 0, 1, 1);
        } else {
             mTempRect.set(key.x, key.y, right, bottom);
        }
        node.setBoundsInParent(mTempRect);
    }

    @Override
    protected boolean onPerformActionForVirtualView(int virtualViewId, int action, @Nullable Bundle arguments) {
        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            if (currentKeyboard != null) {
                List<Keyboard.Key> keys = currentKeyboard.getKeys();
                if (keys != null && virtualViewId >= 0 && virtualViewId < keys.size()) {
                    Keyboard.Key key = keys.get(virtualViewId);
                    
                    // Better Feedback
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED);

                    if (listener != null) {
                        listener.onAccessibilityKeyClick(key.codes[0], key);
                    }
                    return true;
                }
            }
        }
        return false; 
    }

    private String getKeyDescription(Keyboard.Key key) {
        int code = key.codes[0];
        if (code == -5) return "Delete";
        if (code == -1) return isCaps ? "Shift On" : "Shift";
        if (code == 32) return "Space";
        if (code == -4) return "Enter";
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

