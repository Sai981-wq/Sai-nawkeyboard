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

    public void onCharTyped(InputConnection ic) {
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(2000, 0);
        if (text == null || text.length() == 0) return;

        String s = text.toString();
        
        int lastNewLineIndex = s.lastIndexOf('\n');
        if (lastNewLineIndex != -1) {
            s = s.substring(lastNewLineIndex + 1);
        }

        if (s.isEmpty()) return;

        int lastSpaceIndex = s.lastIndexOf(' ');
        if (lastSpaceIndex != -1) {
            announceText(s.substring(lastSpaceIndex + 1));
        } else {
            announceText(s);
        }
    }

    public void onSpaceTyped(InputConnection ic) {
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(2000, 0);
        if (text == null || text.length() == 0) {
            announceText("Space");
            return;
        }

        String s = text.toString();
        
        int lastNewLineIndex = s.lastIndexOf('\n');
        if (lastNewLineIndex != -1) {
            s = s.substring(lastNewLineIndex + 1);
        }

        String trimmed = s.trim();
        
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

    public void onEnterTyped(InputConnection ic) {
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(2000, 0);
        if (text == null || text.length() == 0) {
            announceText("Enter");
            return;
        }

        String s = text.toString();
        String trimmed = s.trim();
        
        if (trimmed.isEmpty()) {
            announceText("Enter");
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

