package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.Keyboard;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast; // For Debugging
import java.util.ArrayList;
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
    public int currentLanguageId = 0; 
    
    private List<Integer> enabledLanguages = new ArrayList<>();

    public SaiNawLayoutManager(SaiNawKeyboardService service) {
        this.service = service;
        this.context = service;
    }

    public void loadLanguageSettings(SharedPreferences prefs) {
        boolean useMm = prefs.getBoolean("enable_mm", true);   
        boolean useShan = prefs.getBoolean("enable_shan", true); 

        enabledLanguages.clear();
        enabledLanguages.add(0); // English
        if (useMm) enabledLanguages.add(1);
        if (useShan) enabledLanguages.add(2);

        if (!enabledLanguages.contains(currentLanguageId)) {
            currentLanguageId = 0; 
        }
    }

    public void initKeyboards(SharedPreferences prefs) {
        loadLanguageSettings(prefs);
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
            // Only force reset symbols if switching away from number pad
            if (currentKeyboard == numberKeyboard) isSymbols = false;
            updateKeyboardLayout();
            return;
        }
        applyKeyboard();
    }

    public void updateKeyboardLayout() {
        try {
            Keyboard nextKeyboard;
            if (isSymbols) {
                if (currentKeyboard == numberKeyboard) nextKeyboard = numberKeyboard;
                else nextKeyboard = (currentLanguageId == 1) ? symbolsMmKeyboard : symbolsEnKeyboard;
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
            updateEnterKeyLabel(); // Call label update
            service.getKeyboardView().setKeyboard(currentKeyboard);
            service.getKeyboardView().invalidateAllKeys();
            service.updateHelperState(); 
        }
    }

    // *** DEBUG VERSION ***
    private void updateEnterKeyLabel() {
        if (currentKeyboard == null || currentEditorInfo == null) return;
        
        int action = currentEditorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        String label = "Normal"; 

        switch (action) {
            case EditorInfo.IME_ACTION_GO: label = "Go"; break;
            case EditorInfo.IME_ACTION_NEXT: label = "Next"; break;
            case EditorInfo.IME_ACTION_SEARCH: label = "Search"; break;
            case EditorInfo.IME_ACTION_SEND: label = "Send"; break;
            case EditorInfo.IME_ACTION_DONE: label = "Done"; break;
            default: label = "Enter"; break; // Fallback
        }

        // *** DEBUG: Show Toast to see if logic is working ***
        // ဒီ Toast ပေါ်လာရင် Logic အလုပ်လုပ်တယ်လို့ မှတ်ယူနိုင်ပါတယ်
        // Toast.makeText(context, "Action: " + action + " -> " + label, Toast.LENGTH_SHORT).show();

        boolean keyFound = false;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        for (Keyboard.Key key : keys) {
            if (key.codes[0] == -4) { 
                key.label = label; 
                key.icon = null; 
                key.iconPreview = null;
                keyFound = true;
                break;
            }
        }
        
        // Key ရှာမတွေ့ရင် Log ထုတ်ကြည့်လို့ရပါတယ် (Optional)
    }
    
    public void changeLanguage() {
        if (enabledLanguages.isEmpty()) return;
        int currentIndex = enabledLanguages.indexOf(currentLanguageId);
        int nextIndex = (currentIndex + 1) % enabledLanguages.size();
        currentLanguageId = enabledLanguages.get(nextIndex);
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

