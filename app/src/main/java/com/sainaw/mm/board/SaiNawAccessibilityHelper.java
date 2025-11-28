package com.sainaw.mm.board;

import android.content.Context;
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
    
    // New constants for improved functionality
    private static final int MAX_SNAP_DISTANCE_SQ = 10000; // 100px threshold squared

    public interface OnAccessibilityKeyListener {
        void onAccessibilityKeyClick(int primaryCode, Keyboard.Key key);
    }

    public SaiNawAccessibilityHelper(@NonNull View view, OnAccessibilityKeyListener listener) {
        super(view);
        this.view = view;
        this.listener = listener;
    }

    public void setKeyboard(Keyboard keyboard, boolean isShanOrMyanmar, boolean isCaps) {
        // Improved error handling without breaking existing flow
        if (keyboard == null || keyboard.getKeys() == null || keyboard.getKeys().isEmpty()) {
            this.currentKeyboard = null;
            invalidateRoot();
            return;
        }
        
        this.currentKeyboard = keyboard;
        this.isShanOrMyanmar = isShanOrMyanmar;
        this.isCaps = isCaps;
        // Refresh TalkBack tree
        invalidateRoot();
        sendEventForVirtualView(HOST_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    @Override
    protected int getVirtualViewAt(float x, float y) {
        if (currentKeyboard == null) return HOST_ID;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || keys.isEmpty()) return HOST_ID;

        // 1. Strict Check: Is the touch exactly inside a key?
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key.isInside((int) x, (int) y)) {
                if (key.codes[0] == -100) return HOST_ID; // Ignore dummy keys
                return i;
            }
        }

        // 2. Nearest Key Check with improved threshold
        return getNearestKeyIndex((int) x, (int) y);
    }

    // --- IMPROVED NEAREST KEY ALGORITHM ---
    private int getNearestKeyIndex(int x, int y) {
        if (currentKeyboard == null) return HOST_ID;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        
        int closestIndex = HOST_ID;
        int minDistSq = Integer.MAX_VALUE;

        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            
            // Skip dummy keys (-100) from snapping
            if (key.codes[0] == -100) continue;

            // Calculate center of the key
            int keyCenterX = key.x + (key.width / 2);
            int keyCenterY = key.y + (key.height / 2);

            // Euclidean distance calculation
            int dx = x - keyCenterX;
            int dy = y - keyCenterY;
            int distSq = (dx * dx) + (dy * dy);

            // Update if this key is closer than the previous best
            if (distSq < minDistSq) {
                minDistSq = distSq;
                closestIndex = i;
            }
        }
        
        // NEW: Only snap if within reasonable distance threshold
        if (minDistSq > MAX_SNAP_DISTANCE_SQ) {
            return HOST_ID; // Don't snap to very far keys
        }
        
        return closestIndex;
    }

    @Override
    protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
        if (currentKeyboard == null) return;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null) return;

        for (int i = 0; i < keys.size(); i++) {
            // Only expose non-dummy keys
            if (keys.get(i).codes[0] != -100) {
                virtualViewIds.add(i);
            }
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
        
        // NEW: Enhanced focus management for better navigation
        node.addAction(AccessibilityNodeInfoCompat.ACTION_FOCUS);
        node.addAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS);
        node.setFocusable(true);
        
        // NEW: Keyboard navigation support
        node.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT);
        node.addAction(AccessibilityNodeInfoCompat.ACTION_PREVIOUS_HTML_ELEMENT);
        
        int right = key.x + key.width;
        int bottom = key.y + key.height;
        if (right <= 0 || bottom <= 0) {
             node.setBoundsInParent(new Rect(0,0,1,1));
        } else {
             node.setBoundsInParent(new Rect(key.x, key.y, right, bottom));
        }
    }

    @Override
    protected boolean onPerformActionForVirtualView(int virtualViewId, int action, @Nullable Bundle arguments) {
        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            if (currentKeyboard != null) {
                List<Keyboard.Key> keys = currentKeyboard.getKeys();
                if (keys != null && virtualViewId >= 0 && virtualViewId < keys.size()) {
                    Keyboard.Key key = keys.get(virtualViewId);
                    
                    // NEW: Enhanced accessibility feedback
                    sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED);
                    
                    // NEW: Haptic feedback for visually impaired users
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    
                    if (listener != null) {
                        listener.onAccessibilityKeyClick(key.codes[0], key);
                    }
                    return true;
                }
            }
        }
        
        // NEW: Handle focus actions for better navigation
        if (action == AccessibilityNodeInfoCompat.ACTION_FOCUS || 
            action == AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS) {
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
            return true;
        }
        
        return false; 
    }

    private String getKeyDescription(Keyboard.Key key) {
        int code = key.codes[0];
        
        // NEW: Localization-ready approach (fallback to English for now)
        // You can replace these with resource strings later
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
    
    // NEW: Helper method for future localization implementation
    private String getLocalizedString(String englishString) {
        // Currently returns the English string
        // You can implement resource-based localization here later
        return englishString;
    }
}
