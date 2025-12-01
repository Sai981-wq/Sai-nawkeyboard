package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.Keyboard;
import android.view.inputmethod.EditorInfo;
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
    public int currentLanguageId = 0; // 0=Eng, 1=MM, 2=Shan
    
    // Language Management
    private List<Integer> enabledLanguages = new ArrayList<>();

    public SaiNawLayoutManager(SaiNawKeyboardService service) {
        this.service = service;
        this.context = service;
    }

    // Load Language Settings from Prefs
    public void loadLanguageSettings(SharedPreferences prefs) {
        boolean useMm = prefs.getBoolean("enable_mm", true);   
        boolean useShan = prefs.getBoolean("enable_shan", true); 

        enabledLanguages.clear();
        
        // 1. English (0) is ALWAYS added first (Mandatory)
        enabledLanguages.add(0);

        // 2. Add others if enabled
        if (useMm) enabledLanguages.add(1);
        if (useShan) enabledLanguages.add(2);

        // Validation
        if (!enabledLanguages.contains(currentLanguageId)) {
            currentLanguageId = 0; // Reset to English
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

    // *** အရေးကြီး: Input Field အချက်အလက်ကို လက်ခံရယူခြင်း ***
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
            // *** ဒီနေရာမှာ Enter စာသားကို အခြေအနေလိုက် ပြောင်းပေးပါတယ် ***
            updateEnterKeyLabel(); 
            
            service.getKeyboardView().setKeyboard(currentKeyboard);
            service.getKeyboardView().invalidateAllKeys();
            service.updateHelperState(); 
        }
    }

    // *** Dynamic Enter Label Logic ***
    private void updateEnterKeyLabel() {
        if (currentKeyboard == null || currentEditorInfo == null) return;
        
        int action = currentEditorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        String label = "Enter"; // Default text

        // အခြေအနေပေါ်မူတည်ပြီး စာသားပြောင်းခြင်း
        switch (action) {
            case EditorInfo.IME_ACTION_GO: label = "Go"; break;
            case EditorInfo.IME_ACTION_NEXT: label = "Next"; break;
            case EditorInfo.IME_ACTION_SEARCH: label = "Search"; break;
            case EditorInfo.IME_ACTION_SEND: label = "Send"; break;
            case EditorInfo.IME_ACTION_DONE: label = "Done"; break;
            default: label = "Enter"; break;
        }

        // Keyboard ပေါ်က Enter ခလုတ် (-4) ကိုလိုက်ရှာပြီး စာသားပြောင်းခြင်း
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        for (Keyboard.Key key : keys) {
            if (key.codes[0] == -4) { 
                key.label = label;
                key.icon = null; // Icon ရှိရင် ဖျောက်လိုက်မှ စာသားပေါ်မယ်
                break;
            }
        }
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

