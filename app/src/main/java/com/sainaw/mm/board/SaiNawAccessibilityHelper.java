package com.sainaw.mm.board;

import android.graphics.Rect;
import android.inputmethodservice.Keyboard;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityEvent; // New Import
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
    }

    // *** (၂) Echo Fix: TalkBack ကို Click Event ပို့ပေးမယ့် Method ***
    public void simulateClick(int virtualViewId) {
        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED);
    }

    @Override
    protected int getVirtualViewAt(float x, float y) {
        if (currentKeyboard == null) return HOST_ID;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key.isInside((int) x, (int) y)) {
                if (key.codes[0] == -100) return HOST_ID; 
                return i;
            }
        }
        return HOST_ID;
    }

    @Override
    protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
        if (currentKeyboard == null) return;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).codes[0] != -100) {
                virtualViewIds.add(i);
            }
        }
    }

    @Override
    protected void onPopulateNodeForVirtualView(int virtualViewId, @NonNull AccessibilityNodeInfoCompat node) {
        if (currentKeyboard == null) return;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (virtualViewId >= keys.size()) return;
        
        Keyboard.Key key = keys.get(virtualViewId);
        String description = getKeyDescription(key);

        node.setContentDescription(description);
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
        node.setClickable(true);
        
        Rect bounds = new Rect(key.x, key.y, key.x + key.width, key.y + key.height);
        node.setBoundsInParent(bounds); 
    }

    @Override
    protected boolean onPerformActionForVirtualView(int virtualViewId, int action, @Nullable Bundle arguments) {
        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            if (currentKeyboard != null && virtualViewId < currentKeyboard.getKeys().size()) {
                Keyboard.Key key = currentKeyboard.getKeys().get(virtualViewId);
                if (listener != null) {
                    listener.onAccessibilityKeyClick(key.codes[0], key);
                }
                return true;
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
        if (code == -101) return "Switch Language";
        if (code == -10) return "Voice Typing";
        if (code == -100) return ""; 

        String label = null;
        if (key.label != null) label = key.label.toString();
        else if (key.text != null) label = key.text.toString();

        // Capital Logic
        if (!isShanOrMyanmar && isCaps && label != null && label.length() == 1 && Character.isLetter(label.charAt(0))) {
             return "Capital " + label;
        }

        return label != null ? label : "Unlabeled Key";
    }
}

