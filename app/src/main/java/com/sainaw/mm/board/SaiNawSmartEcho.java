package com.sainaw.mm.board;

import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputConnection;

public class SaiNawSmartEcho {

    private final Context context;
    private final AccessibilityManager accessibilityManager;

    public SaiNawSmartEcho(Context context) {
        this.context = context;
        this.accessibilityManager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    public void announceText(String text) {
        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }

    // စာလုံးရိုက်နေချိန် (Accumulative Echo)
    public void onCharTyped(InputConnection ic) {
        if (ic == null) return;
        // Look back 2000 chars to handle long sentences without spaces
        CharSequence text = ic.getTextBeforeCursor(2000, 0);
        if (text == null || text.length() == 0) return;

        String s = text.toString();
        int lastSpaceIndex = s.lastIndexOf(' ');

        if (lastSpaceIndex != -1) {
            // Read from the last space
            announceText(s.substring(lastSpaceIndex + 1));
        } else {
            // No space found, read everything
            announceText(s);
        }
    }

    // Space ရိုက်လိုက်ချိန် (Last Word Echo)
    public void onSpaceTyped(InputConnection ic) {
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(2000, 0);
        if (text == null || text.length() == 0) {
            announceText("Space");
            return;
        }

        String s = text.toString();
        String trimmed = s.trim(); // Remove the trailing space to find the word
        
        if (trimmed.isEmpty()) {
            announceText("Space");
            return;
        }

        int lastSpaceIndex = trimmed.lastIndexOf(' ');
        if (lastSpaceIndex != -1) {
            announceText(trimmed.substring(lastSpaceIndex + 1));
        } else {
            announceText(trimmed);
        }
    }
}
