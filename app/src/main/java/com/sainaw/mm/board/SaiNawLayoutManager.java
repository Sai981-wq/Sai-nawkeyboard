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
    
    public Keyboard qwertyKeyboard, qwertyShiftKeyboard;
    public Keyboard myanmarKeyboard, myanmarShiftKeyboard;
    public Keyboard shanKeyboard, shanShiftKeyboard;
    public Keyboard symbolsEnKeyboard, symbolsMmKeyboard;
    public Keyboard symbolsEnShiftKeyboard, symbolsMmShiftKeyboard;
    public Keyboard numberKeyboard;
    public Keyboard emojiKeyboard;
    
    private Keyboard currentKeyboard;
    private EditorInfo currentEditorInfo;

    public boolean isCaps = false;
    public boolean isCapsLocked = false;
    public boolean isSymbols = false;
    public boolean isEmoji = false;
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
        enabledLanguages.add(0); 
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
            String suffix = showNumRow ? "_num" : "";
            
            qwertyKeyboard = new Keyboard(context, service.getResId("qwerty" + suffix));
            qwertyShiftKeyboard = new Keyboard(context, service.getResId("qwerty_shift"));
            
            myanmarKeyboard = new Keyboard(context, service.getResId("myanmar" + suffix));
            myanmarShiftKeyboard = new Keyboard(context, service.getResId("myanmar_shift"));
            
            shanKeyboard = new Keyboard(context, service.getResId("shan"));
            shanShiftKeyboard = new Keyboard(context, service.getResId("shan_shift"));
            
            int symEnId = service.getResId("symbols");
            int symMmId = service.getResId("symbols_mm");
            symbolsEnKeyboard = (symEnId != 0) ? new Keyboard(context, symEnId) : qwertyKeyboard;
            symbolsMmKeyboard = (symMmId != 0) ? new Keyboard(context, symMmId) : symbolsEnKeyboard;

            int symEnShiftId = service.getResId("symbols_shift");
            int symMmShiftId = service.getResId("symbols_mm_shift");
            symbolsEnShiftKeyboard = (symEnShiftId != 0) ? new Keyboard(context, symEnShiftId) : symbolsEnKeyboard;
            symbolsMmShiftKeyboard = (symMmShiftId != 0) ? new Keyboard(context, symMmShiftId) : symbolsMmKeyboard;

            int numPadId = service.getResId("number_pad");
            numberKeyboard = (numPadId != 0) ? new Keyboard(context, numPadId) : symbolsEnKeyboard;

            int emojiId = service.getResId("emoji");
            emojiKeyboard = (emojiId != 0) ? new Keyboard(context, emojiId) : symbolsEnKeyboard;
            
        } catch (Exception e) {
            e.printStackTrace();
            qwertyKeyboard = new Keyboard(context, service.getResId("qwerty"));
            myanmarKeyboard = new Keyboard(context, service.getResId("myanmar"));
        }
    }

    public void updateEditorInfo(EditorInfo info) {
        this.currentEditorInfo = info;
    }

    public void determineKeyboardForInputType() {
        isEmoji = false;

        if (currentEditorInfo == null) return;
        
        int inputType = currentEditorInfo.inputType & EditorInfo.TYPE_MASK_CLASS;
        if (inputType == EditorInfo.TYPE_CLASS_PHONE || 
            inputType == EditorInfo.TYPE_CLASS_NUMBER || 
            inputType == EditorInfo.TYPE_CLASS_DATETIME) {
            currentKeyboard = numberKeyboard;
            isSymbols = true;
        } else {
            if (currentKeyboard == numberKeyboard) isSymbols = false;
            updateKeyboardLayout();
            return;
        }
        applyKeyboard();
    }

    public void updateKeyboardLayout() {
        try {
            Keyboard nextKeyboard;
            if (isEmoji) {
                nextKeyboard = emojiKeyboard;
            } else if (isSymbols) {
                if (currentKeyboard == numberKeyboard) {
                    nextKeyboard = numberKeyboard;
                } else {
                    if (currentLanguageId == 1) {
                        nextKeyboard = isCaps ? symbolsMmShiftKeyboard : symbolsMmKeyboard;
                    } else {
                        nextKeyboard = isCaps ? symbolsEnShiftKeyboard : symbolsEnKeyboard;
                    }
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
            service.updateHelperState(); 
        }
    }

    private void updateEnterKeyLabel() {
        if (currentKeyboard == null || currentEditorInfo == null) return;
        
        boolean isMultiLine = (currentEditorInfo.inputType & EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        
        int action = currentEditorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        String label = "Enter"; 

        if (isMultiLine) {
            label = "Enter";
        } else {
            switch (action) {
                case EditorInfo.IME_ACTION_GO: label = "Go"; break;
                case EditorInfo.IME_ACTION_NEXT: label = "Next"; break;
                case EditorInfo.IME_ACTION_SEARCH: label = "Search"; break;
                case EditorInfo.IME_ACTION_SEND: label = "Send"; break;
                case EditorInfo.IME_ACTION_DONE: label = "Done"; break;
                default: label = "Enter"; break;
            }
        }

        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        for (Keyboard.Key key : keys) {
            if (key.codes[0] == -4) { 
                key.label = label; 
                key.icon = null; 
                key.iconPreview = null;
                break;
            }
        }
    }
    
    public void changeLanguage() {
        if (enabledLanguages.isEmpty()) return;
        int currentIndex = enabledLanguages.indexOf(currentLanguageId);
        int nextIndex = (currentIndex + 1) % enabledLanguages.size();
        currentLanguageId = enabledLanguages.get(nextIndex);
        
        isCaps = false; 
        isSymbols = false; 
        isCapsLocked = false;
        isEmoji = false;
        
        updateKeyboardLayout();
    }

    public Keyboard getCurrentKeyboard() { return currentKeyboard; }
    public List<Keyboard.Key> getCurrentKeys() { return currentKeyboard != null ? currentKeyboard.getKeys() : null; }
    public boolean isShanOrMyanmar() {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard;
    }
}

