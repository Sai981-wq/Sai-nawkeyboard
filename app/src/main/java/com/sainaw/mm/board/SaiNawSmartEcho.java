package com.sainaw.mm.board;

import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputConnection;

public class SaiNawSmartEcho {

    private final Context context;
    private final AccessibilityManager accessibilityManager;
    private static final String ZWSP = "\u200B";

    public SaiNawSmartEcho(Context context) {
        this.context = context;
        this.accessibilityManager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    public void announceText(String text) {
        if (text == null || text.isEmpty()) return;
        String cleanText = text.replace(ZWSP, "");
        if (cleanText.isEmpty()) return;

        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(cleanText);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }

    public void onCharTyped(InputConnection ic) {
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(100, 0);
        if (text == null || text.length() == 0) return;

        String s = text.toString();
        int lastSpaceIndex = s.lastIndexOf(' ');
        
        String wordToEcho = (lastSpaceIndex != -1) ? s.substring(lastSpaceIndex + 1) : s;
        if (!wordToEcho.isEmpty()) {
            announceText(wordToEcho);
        }
    }

    public void onSpaceTyped(InputConnection ic) {
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(500, 0);
        if (text == null || text.length() == 0) {
            announceText("Space");
            return;
        }

        String s = text.toString();
        String trimmed = s.trim();
        
        if (trimmed.isEmpty()) {
            announceText("Space");
            return;
        }

        int lastSpaceIndex = trimmed.lastIndexOf(' ');
        String lastWord = (lastSpaceIndex != -1) ? trimmed.substring(lastSpaceIndex + 1) : trimmed;
        
        if (!lastWord.isEmpty()) {
            announceText(lastWord);
        } else {
            announceText("Space");
        }
    }
}

