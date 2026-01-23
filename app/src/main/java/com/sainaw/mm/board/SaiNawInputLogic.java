package com.sainaw.mm.board;

import android.inputmethodservice.Keyboard;
import android.view.inputmethod.InputConnection;

public class SaiNawInputLogic {

    private final SaiNawTextProcessor textProcessor;
    private final SaiNawLayoutManager layoutManager;

    private static final int MM_THWAY_HTOE = 4145;
    private static final int SHAN_E = 4228;
    private static final char ZWSP = '\u200B';
    private static final int MM_I = 4141;
    private static final int MM_U = 4143;
    private static final int MM_UU = 4144;
    private static final int MM_ANUSVARA = 4150;

    public SaiNawInputLogic(SaiNawTextProcessor textProcessor, SaiNawLayoutManager layoutManager) {
        this.textProcessor = textProcessor;
        this.layoutManager = layoutManager;
    }

    public void processInput(InputConnection ic, int primaryCode, Keyboard.Key key) {
        if (ic == null) return;

        String charStr = (key != null && key.label != null && key.label.length() > 1) 
                ? key.label.toString() : String.valueOf((char) primaryCode);
        
        if ((primaryCode >= 48 && primaryCode <= 57) || !layoutManager.isShanOrMyanmar()) {
            ic.commitText(charStr, 1);
            return;
        }

        boolean handled = false;

        // 1. Reordering: Consonant typed after Thway Htoe (ေ + က -> က + ေ)
        if (textProcessor.isConsonant(primaryCode)) {
            CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
            if (lastOne != null && lastOne.length() > 0) {
                char prev = lastOne.charAt(0);
                if (prev == MM_THWAY_HTOE || prev == SHAN_E) {
                    ic.beginBatchEdit();
                    ic.deleteSurroundingText(1, 0);
                    ic.commitText(charStr, 1);
                    ic.commitText(String.valueOf(prev), 1);
                    ic.endBatchEdit();
                    handled = true;
                }
            }
        }
        
        // 2. Medial Reordering
        if (!handled && textProcessor.isMedial(primaryCode)) {
            CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
            if (lastOne != null && lastOne.length() > 0 && (lastOne.charAt(0) == MM_THWAY_HTOE || lastOne.charAt(0) == SHAN_E)) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(1, 0);
                ic.commitText(charStr, 1);
                ic.commitText(String.valueOf(lastOne.charAt(0)), 1);
                ic.endBatchEdit();
                handled = true;
            }
        }
        
        // 3. Vowel Stacking
        if (!handled) {
            CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
            if (lastOne != null && lastOne.length() > 0) {
                 char prev = lastOne.charAt(0);
                 if (primaryCode == MM_I && (prev == MM_U || prev == MM_UU)) handled = swapChars(ic, charStr, prev);
                 else if (primaryCode == MM_U && prev == MM_ANUSVARA) handled = swapChars(ic, charStr, prev);
            }
        }
        
        if (!handled) {
            ic.commitText(charStr, 1);
        }
    }

    private boolean swapChars(InputConnection ic, String current, char prev) {
        ic.beginBatchEdit();
        ic.deleteSurroundingText(1, 0);
        ic.commitText(current, 1);
        ic.commitText(String.valueOf(prev), 1);
        ic.endBatchEdit();
        return true;
    }
}

