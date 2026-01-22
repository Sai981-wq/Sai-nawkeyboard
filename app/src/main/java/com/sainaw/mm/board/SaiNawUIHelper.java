package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import java.util.ArrayList;
import java.util.List;

public class SaiNawUIHelper extends ExploreByTouchHelper {
    
    private final Context context;
    private final SaiNawKeyboardService service;
    private final SaiNawCoreLogic coreLogic;
    private KeyboardView keyboardView;
    private final AccessibilityManager accessibilityManager;
    private final AudioManager audioManager;
    private final Vibrator vibrator;
    private SharedPreferences prefs;
    
    private Keyboard qwerty, qwertyShift, myanmar, myanmarShift, shan, shanShift, symbols, symbolsMm, numberPad, emojiKeyboard;
    private Keyboard currentKeyboard;
    
    private boolean isCaps = false;
    private boolean isCapsLocked = false;
    private boolean isSymbols = false;
    private int currentLanguageId = 0; 
    private final List<Integer> enabledLanguages = new ArrayList<>();
    
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isLongPressed = false;
    private boolean isEmojiProcessing = false;
    private int pressedKeyCode = 0;
    private int lastHoverKeyIndex = -1;
    
    private final Runnable emojiLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            isLongPressed = true;
            String emoji = String.valueOf((char) pressedKeyCode);
            String meaning = coreLogic.getEmojiMeaning(emoji);
            announceText(meaning);
            playVibrate(VibrationEffect.EFFECT_HEAVY_CLICK);
        }
    };

    public SaiNawUIHelper(SaiNawKeyboardService service, SaiNawCoreLogic coreLogic) {
        super(service.onCreateInputView().findViewById(R.id.keyboard_view)); 
        this.service = service;
        this.context = service;
        this.coreLogic = coreLogic;
        
        this.accessibilityManager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        
        prefs = context.getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        initKeyboards();
    }

    public void attach(KeyboardView kv) {
        this.keyboardView = kv;
        ViewCompat.setAccessibilityDelegate(kv, this);
        updateKeyboardLayout();
    }

    public void onStartInputView(EditorInfo info) {
        loadSettings();
        updateEnterKeyLabel(info);
        determineKeyboardForInputType(info);
    }
    
    private void loadSettings() {
        enabledLanguages.clear();
        enabledLanguages.add(0);
        if (prefs.getBoolean("enable_mm", true)) enabledLanguages.add(1);
        if (prefs.getBoolean("enable_shan", true)) enabledLanguages.add(2);
        
        if (!enabledLanguages.contains(currentLanguageId)) currentLanguageId = 0;
    }

    private void initKeyboards() {
        boolean row = prefs.getBoolean("number_row", false);
        String suffix = row ? "_num" : "";
        qwerty = new Keyboard(context, getResId("qwerty" + suffix));
        qwertyShift = new Keyboard(context, getResId("qwerty_shift"));
        myanmar = new Keyboard(context, getResId("myanmar" + suffix));
        myanmarShift = new Keyboard(context, getResId("myanmar_shift"));
        shan = new Keyboard(context, getResId("shan" + suffix));
        shanShift = new Keyboard(context, getResId("shan_shift"));
        symbols = new Keyboard(context, getResId("symbols"));
        symbolsMm = new Keyboard(context, getResId("symbols_mm"));
        numberPad = new Keyboard(context, getResId("number_pad"));
        
        int emojiId = getResId("emoji");
        if (emojiId != 0) emojiKeyboard = new Keyboard(context, emojiId);
    }

    private int getResId(String name) {
        return context.getResources().getIdentifier(name, "xml", context.getPackageName());
    }

    public boolean handleHover(MotionEvent event) {
        dispatchHoverEvent(event);
        int action = event.getAction();
        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
            int keyIndex = getVirtualViewAt(event.getX(), event.getY());
            if (keyIndex != -1 && keyIndex != lastHoverKeyIndex) {
                lastHoverKeyIndex = keyIndex;
                handler.removeCallbacks(emojiLongPressRunnable);
                if (currentKeyboard == emojiKeyboard) {
                    Keyboard.Key key = currentKeyboard.getKeys().get(keyIndex);
                    if (isEmoji(key.codes[0])) {
                        pressedKeyCode = key.codes[0];
                        handler.postDelayed(emojiLongPressRunnable, 3000);
                    }
                }
            }
        } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
            handler.removeCallbacks(emojiLongPressRunnable);
            lastHoverKeyIndex = -1;
        }
        return true;
    }

    public void handlePress(int primaryCode) {
        playVibrate(VibrationEffect.EFFECT_TICK);
        if (isEmoji(primaryCode)) {
            pressedKeyCode = primaryCode;
            isLongPressed = false;
            isEmojiProcessing = true;
            handler.postDelayed(emojiLongPressRunnable, 3000);
        }
    }

    public boolean handleRelease(int primaryCode) {
        if (isEmojiProcessing && primaryCode == pressedKeyCode) {
            handler.removeCallbacks(emojiLongPressRunnable);
            isEmojiProcessing = false;
            return isLongPressed; 
        }
        return false;
    }

    private boolean isEmoji(int code) {
        return (code >= 0x1F600 && code <= 0x1F64F) || (code >= 0x1F300 && code <= 0x1F5FF) || 
               (code >= 0x1F680 && code <= 0x1F6FF) || (code >= 0x1F900 && code <= 0x1F9FF);
    }

    public void updateEditorInfo(EditorInfo info) {
        updateEnterKeyLabel(info);
    }

    public void determineKeyboardForInputType(EditorInfo info) {
        int inputType = info.inputType & EditorInfo.TYPE_MASK_CLASS;
        if (inputType == EditorInfo.TYPE_CLASS_PHONE || inputType == EditorInfo.TYPE_CLASS_NUMBER || inputType == EditorInfo.TYPE_CLASS_DATETIME) {
            currentKeyboard = numberPad;
        } else {
            updateKeyboardLayout();
        }
        if (keyboardView != null) {
            keyboardView.setKeyboard(currentKeyboard);
        }
    }

    public void updateKeyboardLayout() {
        Keyboard next;
        if (isSymbols) {
            next = (currentLanguageId == 1) ? symbolsMm : symbols;
        } else if (currentKeyboard == emojiKeyboard && isSymbols == false) {
            next = emojiKeyboard;
        } else {
            if (currentLanguageId == 1) next = isCaps ? myanmarShift : myanmar;
            else if (currentLanguageId == 2) next = isCaps ? shanShift : shan;
            else next = isCaps ? qwertyShift : qwerty;
        }
        currentKeyboard = next;
        if (keyboardView != null) {
            keyboardView.setKeyboard(currentKeyboard);
            invalidateRoot();
        }
    }

    private void updateEnterKeyLabel(EditorInfo info) {
        if (currentKeyboard == null) return;
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        String label = "Enter";
        switch (action) {
            case EditorInfo.IME_ACTION_GO: label = "Go"; break;
            case EditorInfo.IME_ACTION_SEARCH: label = "Search"; break;
            case EditorInfo.IME_ACTION_SEND: label = "Send"; break;
            case EditorInfo.IME_ACTION_DONE: label = "Done"; break;
        }
        for (Keyboard.Key key : currentKeyboard.getKeys()) {
            if (key.codes[0] == -4) {
                key.label = label;
                break;
            }
        }
    }

    public void toggleShift() {
        if (isCapsLocked) {
            isCapsLocked = false;
            isCaps = false;
        } else {
            isCaps = !isCaps;
        }
        updateKeyboardLayout();
    }

    public void toggleSymbols() {
        isSymbols = !isSymbols;
        currentKeyboard = null; 
        updateKeyboardLayout();
    }
    
    public void toggleAlpha() {
        isSymbols = false;
        currentKeyboard = null;
        updateKeyboardLayout();
    }
    
    public void toggleEmoji() {
        isSymbols = false;
        currentKeyboard = emojiKeyboard;
        updateKeyboardLayout();
    }

    public void changeLanguage() {
        if (enabledLanguages.isEmpty()) return;
        int index = enabledLanguages.indexOf(currentLanguageId);
        currentLanguageId = enabledLanguages.get((index + 1) % enabledLanguages.size());
        isCaps = false;
        isSymbols = false;
        currentKeyboard = null;
        updateKeyboardLayout();
        announceText(currentLanguageId == 1 ? "Myanmar" : (currentLanguageId == 2 ? "Shan" : "English"));
    }

    public void playClick(int code) {
        if (!prefs.getBoolean("sound_on", false)) return;
        int sound = AudioManager.FX_KEYPRESS_STANDARD;
        if (code == -5) sound = AudioManager.FX_KEYPRESS_DELETE;
        if (code == 32) sound = AudioManager.FX_KEYPRESS_SPACEBAR;
        if (code == -4) sound = AudioManager.FX_KEYPRESS_RETURN;
        audioManager.playSoundEffect(sound);
    }

    public void playVibrate(int effectId) {
        if (!prefs.getBoolean("vibrate_on", true) || vibrator == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(effectId));
        } else {
            vibrator.vibrate(20);
        }
    }

    public void announceText(String text) {
        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }
    
    public void smartEchoChar(InputConnection ic) {
        if (!prefs.getBoolean("smart_echo", false)) return;
        CharSequence text = ic.getTextBeforeCursor(100, 0);
        if (text != null) {
            String s = text.toString();
            int idx = s.lastIndexOf(' ');
            announceText(idx != -1 ? s.substring(idx + 1) : s);
        }
    }
    
    public void smartEchoSpace(InputConnection ic) {
        if (!prefs.getBoolean("smart_echo", false)) return;
        CharSequence text = ic.getTextBeforeCursor(2000, 0);
        if (text != null) {
            String s = text.toString().trim();
            int idx = s.lastIndexOf(' ');
            announceText(idx != -1 ? s.substring(idx + 1) : s);
        }
    }

    public boolean isCapsTemp() { return isCaps && !isCapsLocked; }
    public void resetCaps() { isCaps = false; updateKeyboardLayout(); }

    @Override protected int getVirtualViewAt(float x, float y) {
        if (currentKeyboard == null) return HOST_ID;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key.isInside((int)x, (int)y)) return i;
        }
        return HOST_ID;
    }

    @Override protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
        if (currentKeyboard == null) return;
        for (int i = 0; i < currentKeyboard.getKeys().size(); i++) virtualViewIds.add(i);
    }

    @Override protected void onPopulateNodeForVirtualView(int virtualViewId, @NonNull AccessibilityNodeInfoCompat node) {
        if (currentKeyboard == null) return;
        Keyboard.Key key = currentKeyboard.getKeys().get(virtualViewId);
        String desc = getKeyDescription(key);
        node.setContentDescription(desc);
        node.setBoundsInParent(new Rect(key.x, key.y, key.x + key.width, key.y + key.height));
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
        node.setClickable(true);
    }

    @Override protected boolean onPerformActionForVirtualView(int virtualViewId, int action, Bundle arguments) {
        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            Keyboard.Key key = currentKeyboard.getKeys().get(virtualViewId);
            service.onKey(key.codes[0], key.codes);
            return true;
        }
        return false;
    }

    private String getKeyDescription(Keyboard.Key key) {
        int code = key.codes[0];
        if (currentKeyboard == emojiKeyboard && isEmoji(code)) {
             String meaning = coreLogic.getEmojiMeaning(String.valueOf((char)code));
             return meaning;
        }
        if (coreLogic != null && currentLanguageId == 1) {
            String phonetic = coreLogic.getPhonetic(code);
            if (phonetic != null) return phonetic;
        }
        if (code == -5) return "Delete";
        if (code == -1) return isCaps ? "Shift On" : "Shift";
        if (code == 32) return "Space";
        if (code == -2) return "Symbols";
        if (code == -4) return "Enter";
        if (code == -101) return "Language";
        if (code == -7) return "Emoji";
        return key.label != null ? key.label.toString() : "Key";
    }
}
