package com.sainaw.mm.board;

import android.inputmethodservice.Keyboard;
import android.view.inputmethod.InputConnection;

public class SaiNawInputLogic {

    private final SaiNawTextProcessor textProcessor;
    private final SaiNawLayoutManager layoutManager;

    // Unicode Constants
    private static final int MM_THWAY_HTOE = 4145;
    private static final int SHAN_E = 4228;
    private static final char ZWSP = '\u200B';
    private static final int MM_I = 4141;
    private static final int MM_U = 4143;
    private static final int MM_UU = 4144;
    private static final int MM_ANUSVARA = 4150;

    // Tracks whether a ZWSP + pre-vowel could precede the cursor to avoid blocking IPC on every keystroke
    private boolean mayHavePreVowel = false;

    public SaiNawInputLogic(SaiNawTextProcessor textProcessor, SaiNawLayoutManager layoutManager) {
        this.textProcessor = textProcessor;
        this.layoutManager = layoutManager;
    }

    public void onCursorOrContextChanged() {
        mayHavePreVowel = true;
    }

    public void resetPreVowelState() {
        mayHavePreVowel = false;
    }

    /**
     * Commits input and performs Myanmar/Shan reordering.
     * Returns true if text before the cursor was modified (reordered/prefixed with ZWSP),
     * or false if the character was simply appended directly.
     */
    public boolean processInput(InputConnection ic, int primaryCode, Keyboard.Key key) {
        if (ic == null) return false;

        String charStr = (key != null && key.label != null && key.label.length() > 1)
                ? key.label.toString() : String.valueOf((char) primaryCode);

        if ((primaryCode >= 48 && primaryCode <= 57) || !layoutManager.isShanOrMyanmar()) {
            mayHavePreVowel = false;
            ic.commitText(charStr, 1);
            return false;
        }

        // 1. Thway Htoe / Shan E (Insert ZWSP + Char)
        if (primaryCode == MM_THWAY_HTOE || primaryCode == SHAN_E) {
            ic.commitText(String.valueOf(ZWSP) + charStr, 1);
            mayHavePreVowel = true;
            return true;
        }

        boolean needMedialCheck = textProcessor.isMedial(primaryCode);
        boolean needVowelStackCheck = (primaryCode == MM_I || primaryCode == MM_U);

        // Fast path: zero synchronous InputConnection IPC for normal consonants/vowels/marks
        if (!mayHavePreVowel && !needMedialCheck && !needVowelStackCheck) {
            ic.commitText(charStr, 1);
            return false;
        }

        // Single IPC read reused across all reordering checks
        CharSequence lastTwo = ic.getTextBeforeCursor(2, 0);
        int len = (lastTwo != null) ? lastTwo.length() : 0;

        // 2. Check existing ZWSP + Thway Htoe / Shan E to reorder behind consonant
        if (mayHavePreVowel) {
            mayHavePreVowel = false;
            if (len == 2 && lastTwo.charAt(0) == ZWSP
                    && (lastTwo.charAt(1) == MM_THWAY_HTOE || lastTwo.charAt(1) == SHAN_E)) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(2, 0);
                ic.commitText(charStr + lastTwo.charAt(1), 1);
                ic.endBatchEdit();
                return true;
            }
        }

        // 3. Medial Reordering
        if (needMedialCheck && len >= 1) {
            char prev = lastTwo.charAt(len - 1);
            if (prev == MM_THWAY_HTOE || prev == SHAN_E) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(1, 0);
                ic.commitText(charStr + prev, 1);
                ic.endBatchEdit();
                return true;
            }
        }

        // 4. Vowel Stacking
        if (needVowelStackCheck && len >= 1) {
            char prev = lastTwo.charAt(len - 1);
            if (primaryCode == MM_I && (prev == MM_U || prev == MM_UU)) {
                swapChars(ic, charStr, prev);
                return true;
            } else if (primaryCode == MM_U && prev == MM_ANUSVARA) {
                swapChars(ic, charStr, prev);
                return true;
            }
        }

        ic.commitText(charStr, 1);
        return false;
    }

    private void swapChars(InputConnection ic, String current, char prev) {
        ic.beginBatchEdit();
        ic.deleteSurroundingText(1, 0);
        ic.commitText(current + prev, 1);
        ic.endBatchEdit();
    }
}

