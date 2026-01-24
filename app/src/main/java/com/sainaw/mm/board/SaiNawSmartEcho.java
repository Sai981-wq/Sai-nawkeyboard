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
        if (cleanText.trim().isEmpty()) return;

        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(cleanText);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }

    public void onCharTyped(InputConnection ic, String typedChar) {
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(3, 0);
        if (text != null && text.toString().contains(typedChar)) {
             announceText(typedChar);
        }
    }

    public void onWordFinished(InputConnection ic) {
        if (ic == null) return;
        
        CharSequence text = ic.getTextBeforeCursor(100, 0);
        if (text == null || text.length() == 0) return;

        String s = text.toString();
        int lastSpace = s.lastIndexOf(' ');
        int lastEnter = s.lastIndexOf('\n');
        int cutIndex = Math.max(lastSpace, lastEnter);

        String wordToEcho = (cutIndex != -1) ? s.substring(cutIndex + 1) : s;
        wordToEcho = wordToEcho.replace(ZWSP, "").trim();

        if (!wordToEcho.isEmpty()) {
            announceText(wordToEcho);
        }
    }
}

