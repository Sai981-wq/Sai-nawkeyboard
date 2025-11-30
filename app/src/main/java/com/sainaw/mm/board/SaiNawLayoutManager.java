package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.view.inputmethod.EditorInfo;
import java.util.List;

public class SaiNawLayoutManager {
    private final Context context;
    private final SaiNawKeyboardService service;
    
    // Keyboards
    public Keyboard qwertyKeyboard, qwertyShiftKeyboard;
    public Keyboard myanmarKeyboard, myanmarShiftKeyboard;
    public Keyboard shanKeyboard, shanShiftKeyboard;
    public Keyboard symbolsEnKeyboard, symbolsMmKeyboard;
    public Keyboard numberKeyboard;
    
    private Keyboard currentKeyboard;
    private EditorInfo currentEditorInfo;

    // States
    public boolean isCaps = false;
    public boolean isCapsLocked = false;
    public boolean isSymbols = false;
    public int currentLanguageId = 0; // 0=Eng, 1=MM, 2=Shan

    public SaiNawLayoutManager(SaiNawKeyboardService service) {
        this.service = service;
        this.context = service;
    }

    public void initKeyboards(SharedPreferences prefs) {
        try {
            boolean showNumRow = prefs.getBoolean("number_row", false);
            String engSuffix = showNumRow ? "_num" : "";
            
            qwertyKeyboard = new Keyboard(context, service.getResId("qwerty" + engSuffix));
            qwertyShiftKeyboard = new Keyboard(context, service.getResId("qwerty_shift"));
            myanmarKeyboard = new Keyboard(context, service.getResId("myanmar"));
            myanmarShiftKeyboard = new Keyboard(context, service.getResId("myanmar_shift"));
            shanKeyboard = new Keyboard(context, service.getResId("shan"));
            shanShiftKeyboard = new Keyboard(context, service.getResId("shan_shift"));
            
            int symEnId = service.getResId("symbols");
            int symMmId = service.getResId("symbols_mm");
            symbolsEnKeyboard = (symEnId != 0) ? new Keyboard(context, symEnId) : qwertyKeyboard;
            symbolsMmKeyboard = (symMmId != 0) ? new Keyboard(context, symMmId) : symbolsEnKeyboard;

            int numPadId = service.getResId("number_pad");
            numberKeyboard = (numPadId != 0) ? new Keyboard(context, numPadId) : symbolsEnKeyboard;
            
        } catch (Exception e) {
            e.printStackTrace();
            qwertyKeyboard = new Keyboard(context, service.getResId("qwerty"));
        }
    }

    public void updateEditorInfo(EditorInfo info) {
        this.currentEditorInfo = info;
    }

    public void determineKeyboardForInputType() {
        if (currentEditorInfo == null) return;
        
        int inputType = currentEditorInfo.inputType & EditorInfo.TYPE_MASK_CLASS;
        if (inputType == EditorInfo.TYPE_CLASS_PHONE || 
            inputType == EditorInfo.TYPE_CLASS_NUMBER || 
            inputType == EditorInfo.TYPE_CLASS_DATETIME) {
            currentKeyboard = numberKeyboard;
            isSymbols = true;
        } else {
            isSymbols = false;
            updateKeyboardLayout();
            return;
        }
        applyKeyboard();
    }

    public void updateKeyboardLayout() {
        try {
            Keyboard nextKeyboard;
            if (isSymbols) {
                if (currentKeyboard == numberKeyboard) {
                    nextKeyboard = numberKeyboard;
                } else {
                    nextKeyboard = (currentLanguageId == 1) ? symbolsMmKeyboard : symbolsEnKeyboard;
                }
            } else {
                if (currentLanguageId == 1) nextKeyboard = isCaps ? myanmarShiftKeyboard : myanmarKeyboard;
                else if (currentLanguageId == 2) nextKeyboard = isCaps ? shanShiftKeyboard : shanKeyboard;
                else nextKeyboard = isCaps ? qwertyShiftKeyboard : qwertyKeyboard;
            }
            currentKeyboard = nextKeyboard;
            applyKeyboard();
        } catch (Exception e) {
            currentKeyboard = qwertyKeyboard;
            applyKeyboard();
        }
    }

    private void applyKeyboard() {
        if (service.getKeyboardView() != null) {
            updateEnterKeyLabel();
            service.getKeyboardView().setKeyboard(currentKeyboard);
            service.getKeyboardView().invalidateAllKeys();
            service.updateHelperState(); // Update Accessibility Helper
        }
    }

    private void updateEnterKeyLabel() {
        if (currentKeyboard == null || currentEditorInfo == null) return;
        int action = currentEditorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        String label = "Enter";

        switch (action) {
            case EditorInfo.IME_ACTION_GO: label = "Go"; break;
            case EditorInfo.IME_ACTION_NEXT: label = "Next"; break;
            case EditorInfo.IME_ACTION_SEARCH: label = "Search"; break;
            case EditorInfo.IME_ACTION_SEND: label = "Send"; break;
            case EditorInfo.IME_ACTION_DONE: label = "Done"; break;
        }

        for (Keyboard.Key key : currentKeyboard.getKeys()) {
            if (key.codes[0] == -4) { // CODE_ENTER
                key.label = label;
                key.icon = null;
                key.iconPreview = null;
                break;
            }
        }
    }
    
    public void changeLanguage() {
        if (currentLanguageId == 0) currentLanguageId = 1;
        else if (currentLanguageId == 1) currentLanguageId = 2;
        else currentLanguageId = 0;
        
        isCaps = false; isSymbols = false; isCapsLocked = false;
        updateKeyboardLayout();
    }

    public Keyboard getCurrentKeyboard() { return currentKeyboard; }
    public List<Keyboard.Key> getCurrentKeys() { return currentKeyboard != null ? currentKeyboard.getKeys() : null; }
    public boolean isShanOrMyanmar() {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard;
    }
}
