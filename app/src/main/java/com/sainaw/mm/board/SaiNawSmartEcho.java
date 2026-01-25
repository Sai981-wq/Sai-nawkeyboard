package com.sainaw.mm.board;

import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputConnection;

public class SaiNawSmartEcho {

    private final AccessibilityManager accessibilityManager;

    public SaiNawSmartEcho(Context context) {
        this.accessibilityManager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    private String getCurrentLine(InputConnection ic) {
        if (ic == null) return "";
        CharSequence text = ic.getTextBeforeCursor(2000, 0);
        if (text == null || text.length() == 0) return "";
        
        String s = text.toString();
        int lastNewLineIndex = s.lastIndexOf('\n');
        if (lastNewLineIndex != -1) {
            return s.substring(lastNewLineIndex + 1);
        }
        return s;
    }

    public void announceText(String text) {
        if (accessibilityManager != null && accessibilityManager.isEnabled() && !text.isEmpty()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }

    public void onCharTyped(InputConnection ic) {
        String currentLine = getCurrentLine(ic);
        if (currentLine.isEmpty()) return;

        int lastSpaceIndex = currentLine.lastIndexOf(' ');
        if (lastSpaceIndex != -1) {
            announceText(currentLine.substring(lastSpaceIndex + 1));
        } else {
            announceText(currentLine);
        }
    }

    public void onSpaceTyped(InputConnection ic) {
        String currentLine = getCurrentLine(ic);
        String trimmed = currentLine.trim();

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
        announceText("Enter");
    }
}

