package com.sainaw.mm.board;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.media.AudioManager;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.os.Vibrator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

public class SaiNawKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    private KeyboardView keyboardView;
    private LinearLayout candidateContainer;
    private SuggestionDB suggestionDB;
    private StringBuilder currentWord = new StringBuilder();

    private Keyboard qwertyKeyboard;
    private Keyboard qwertyShiftKeyboard;
    private Keyboard myanmarKeyboard;
    private Keyboard myanmarShiftKeyboard;
    private Keyboard shanKeyboard;
    private Keyboard shanShiftKeyboard;
    private Keyboard symbolsKeyboard; 
    
    private Keyboard currentKeyboard;
    private boolean isCaps = false;
    private AccessibilityManager accessibilityManager;
    private AudioManager audioManager;
    private SharedPreferences prefs;
    
    private int lastHoverKeyIndex = -1;
    private boolean isVibrateOn = true;
    private boolean isSoundOn = true;

    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isSpaceLongPressed = false;
    private Runnable spaceLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            isSpaceLongPressed = true;
            InputMethodManager imeManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imeManager != null) {
                imeManager.showInputMethodPicker();
                playHaptic();
            }
        }
    };

    @Override
    public View onCreateInputView() {
        prefs = getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        loadSettings();

        boolean isDarkTheme = prefs.getBoolean("dark_theme", false);
        int layoutRes = isDarkTheme ? R.layout.input_view_dark : R.layout.input_view;
        View layout = getLayoutInflater().inflate(layoutRes, null);
        
        keyboardView = layout.findViewById(R.id.keyboard_view);
        candidateContainer = layout.findViewById(R.id.candidates_container);
        
        accessibilityManager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        suggestionDB = new SuggestionDB(this);

        initKeyboards();

        currentKeyboard = qwertyKeyboard;
        keyboardView.setKeyboard(currentKeyboard);
        keyboardView.setOnKeyboardActionListener(this);

        keyboardView.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, MotionEvent event) {
                return handleHover(event);
            }
        });

        keyboardView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return accessibilityManager.isEnabled(); 
            }
        });

        return layout;
    }

    private void initKeyboards() {
        boolean showNumRow = prefs.getBoolean("number_row", false);
        if (showNumRow) {
            qwertyKeyboard = new Keyboard(this, R.xml.qwerty_num);
            myanmarKeyboard = new Keyboard(this, R.xml.myanmar_num);
            shanKeyboard = new Keyboard(this, R.xml.shan_num);
        } else {
            qwertyKeyboard = new Keyboard(this, R.xml.qwerty);
            myanmarKeyboard = new Keyboard(this, R.xml.myanmar);
            shanKeyboard = new Keyboard(this, R.xml.shan);
        }
        qwertyShiftKeyboard = new Keyboard(this, R.xml.qwerty_shift);
        myanmarShiftKeyboard = new Keyboard(this, R.xml.myanmar_shift);
        shanShiftKeyboard = new Keyboard(this, R.xml.shan_shift);
        symbolsKeyboard = new Keyboard(this, R.xml.symbols);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        loadSettings();
        currentWord.setLength(0);
        updateCandidates();
    }

    private void loadSettings() {
        isVibrateOn = prefs.getBoolean("vibrate_on", true);
        isSoundOn = prefs.getBoolean("sound_on", true);
    }

    // --- Helper Methods (Moved Up for Visibility) ---
    
    private boolean isConsonant(int code) {
        // Myanmar & Shan Consonants Range
        return (code >= 4096 && code <= 4138) || (code >= 4213 && code <= 4225) || code == 4100 || code == 4101;
    }

    private boolean isShanOrMyanmar() {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard;
    }

    // --- Text Input ---
    @Override
    public void onText(CharSequence text) {
        if (accessibilityManager.isEnabled()) return;

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        
        ic.commitText(text, 1);
        playSound();
        
        if (isCaps) {
            isCaps = false;
            updateKeyboardLayout();
        }
    }

    // --- TalkBack Logic ---
    private boolean handleHover(MotionEvent event) {
        if (!accessibilityManager.isEnabled()) return false;

        int action = event.getAction();
        int touchX = (int) event.getX();
        int touchY = (int) event.getY();
        int keyIndex = getNearestKeyIndex(touchX, touchY);

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                if (keyIndex != -1 && keyIndex != lastHoverKeyIndex) {
                    lastHoverKeyIndex = keyIndex;
                    playHaptic();
                    announceKeyText(currentKeyboard.getKeys().get(keyIndex));
                }
                break;

            case MotionEvent.ACTION_HOVER_EXIT:
                if (lastHoverKeyIndex != -1) {
                    List<Keyboard.Key> keys = currentKeyboard.getKeys();
                    if (lastHoverKeyIndex < keys.size()) {
                        Keyboard.Key key = keys.get(lastHoverKeyIndex);
                        if (key.codes.length > 0) {
                            handleInput(key.codes[0], key);
                        }
                    }
                    lastHoverKeyIndex = -1;
                }
                break;
        }
        return true; 
    }

    @Override
    public void onPress(int primaryCode) {
        if (accessibilityManager.isEnabled()) return;
        if (primaryCode == 32) {
            isSpaceLongPressed = false;
            handler.postDelayed(spaceLongPressRunnable, 600);
        }
        playHaptic(); 
    }

    @Override
    public void onRelease(int primaryCode) {
        if (accessibilityManager.isEnabled()) return;
        if (primaryCode == 32) {
            handler.removeCallbacks(spaceLongPressRunnable);
        }
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        if (accessibilityManager.isEnabled()) return;
        handleInput(primaryCode, null);
    }

    // --- Main Logic ---
    private void handleInput(int primaryCode, Keyboard.Key key) {
        playSound(); 

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        String textToSpeak = null;

        if (key != null && key.text != null) {
            ic.commitText(key.text, 1);
            textToSpeak = key.text.toString();
            speakSystem(textToSpeak);
            return;
        }

        switch (primaryCode) {
            case -10: 
                startVoiceInput(); 
                textToSpeak = "Voice Typing";
                break;
            case -1: 
                isCaps = !isCaps;
                updateKeyboardLayout(); 
                textToSpeak = isCaps ? "Shift On" : "Shift Off";
                break;
            case -2: 
                currentKeyboard = symbolsKeyboard;
                updateKeyboardLayout();
                textToSpeak = "Symbols";
                break;
            case -6: 
                currentKeyboard = qwertyKeyboard;
                updateKeyboardLayout();
                textToSpeak = "Alphabet";
                break;
            case -101: 
                changeLanguage();
                break;
            case -4: 
                int options = getCurrentInputEditorInfo().imeOptions;
                int action = options & EditorInfo.IME_MASK_ACTION;
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_GO) {
                    sendDefaultEditorAction(true);
                } else {
                    ic.commitText("\n", 1);
                }
                saveCurrentWordToDB();
                textToSpeak = "Enter";
                break;
            case -5: 
                ic.deleteSurroundingText(1, 0);
                if (currentWord.length() > 0) {
                    currentWord.deleteCharAt(currentWord.length() - 1);
                    updateCandidates();
                }
                textToSpeak = "Delete";
                break;
            case 32: 
                if (!isSpaceLongPressed) {
                    ic.commitText(" ", 1);
                    saveCurrentWordToDB();
                    textToSpeak = "Space";
                }
                isSpaceLongPressed = false;
                break;
            case 0: break;
            default:
                if (isShanOrMyanmar() && handleSmartReordering(ic, primaryCode)) {
                    char code = (char) primaryCode;
                    currentWord.append(String.valueOf(code));
                } else {
                    if (key != null && key.label != null && key.label.length() > 1) {
                         ic.commitText(key.label, 1);
                         currentWord.append(key.label);
                         textToSpeak = key.label.toString();
                    } else {
                        char code = (char) primaryCode;
                        String charStr = String.valueOf(code);
                        ic.commitText(charStr, 1);
                        currentWord.append(charStr);
                        textToSpeak = charStr; 
                    }
                }
                if (isCaps) {
                    isCaps = false;
                    updateKeyboardLayout();
                }
                updateCandidates();
        }

        if (textToSpeak != null) speakSystem(textToSpeak);
    }

    private boolean handleSmartReordering(InputConnection ic, int primaryCode) {
        if (isConsonant(primaryCode)) { 
             CharSequence before = ic.getTextBeforeCursor(1, 0);
             if (before != null && before.length() > 0) {
                 char preChar = before.charAt(0);
                 if (preChar == 4145 || preChar == 4228) { 
                     ic.deleteSurroundingText(1, 0);
                     ic.commitText(String.valueOf((char)primaryCode), 1);
                     ic.commitText(String.valueOf(preChar), 1);
                     return true;
                 }
             }
        }
        if (primaryCode == 4141) { 
             CharSequence before = ic.getTextBeforeCursor(1, 0);
             if (before != null && before.length() > 0) {
                 char prev = before.charAt(0);
                 if (prev == 4143 || prev == 4144) { 
                     ic.deleteSurroundingText(1, 0);
                     ic.commitText(String.valueOf((char)4141), 1);
                     ic.commitText(String.valueOf(prev), 1);
                     return true;
                 }
             }
        }
        return false;
    }

    private void saveCurrentWordToDB() {
        if (currentWord.length() > 0) {
            suggestionDB.saveWord(currentWord.toString());
            currentWord.setLength(0);
            updateCandidates();
        }
    }

    private void updateCandidates() {
        if (candidateContainer == null) return;
        candidateContainer.removeAllViews();
        if (currentWord.length() > 0) {
            List<String> suggestions = suggestionDB.getSuggestions(currentWord.toString());
            for (final String suggestion : suggestions) {
                TextView tv = new TextView(this);
                tv.setText(suggestion);
                tv.setTextSize(18);
                tv.setTextColor(prefs.getBoolean("dark_theme", false) ? Color.WHITE : Color.BLACK);
                tv.setPadding(30, 10, 30, 10);
                tv.setGravity(Gravity.CENTER);
                tv.setBackgroundResource(android.R.drawable.btn_default);
                tv.setFocusable(true);
                tv.setOnClickListener(v -> pickSuggestion(suggestion));
                candidateContainer.addView(tv);
            }
        }
    }

    private void pickSuggestion(String suggestion) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.deleteSurroundingText(currentWord.length(), 0);
        ic.commitText(suggestion + " ", 1);
        suggestionDB.saveWord(suggestion);
        currentWord.setLength(0);
        updateCandidates();
        speakSystem("Selected " + suggestion);
    }

    private void updateKeyboardLayout() {
        lastHoverKeyIndex = -1; 
        if (currentKeyboard == symbolsKeyboard) { } 
        else if (isCaps) {
            if (currentKeyboard == qwertyKeyboard) currentKeyboard = qwertyShiftKeyboard;
            else if (currentKeyboard == myanmarKeyboard) currentKeyboard = myanmarShiftKeyboard;
            else if (currentKeyboard == shanKeyboard) currentKeyboard = shanShiftKeyboard;
        } else {
            if (currentKeyboard == qwertyShiftKeyboard) currentKeyboard = qwertyKeyboard;
            else if (currentKeyboard == myanmarShiftKeyboard) currentKeyboard = myanmarKeyboard;
            else if (currentKeyboard == shanShiftKeyboard) currentKeyboard = shanKeyboard;
        }
        keyboardView.setKeyboard(currentKeyboard);
        keyboardView.invalidateAllKeys();
    }

    private void changeLanguage() {
        lastHoverKeyIndex = -1;
        if (currentKeyboard == qwertyKeyboard || currentKeyboard == qwertyShiftKeyboard || currentKeyboard == symbolsKeyboard) {
            currentKeyboard = myanmarKeyboard;
            speakSystem("Myanmar");
        } else if (currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard) {
            currentKeyboard = shanKeyboard;
            speakSystem("Shan");
        } else {
            currentKeyboard = qwertyKeyboard;
            speakSystem("English");
        }
        isCaps = false;
        keyboardView.setKeyboard(currentKeyboard);
    }

    private int getNearestKeyIndex(int x, int y) {
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).isInside(x, y)) return i;
        }
        int closestIndex = -1;
        double minDistance = Double.MAX_VALUE;
        int threshold = 150; 
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            int centerX = key.x + key.width / 2;
            int centerY = key.y + key.height / 2;
            double dist = Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2));
            if (dist < minDistance && dist < threshold) {
                minDistance = dist;
                closestIndex = i;
            }
        }
        return closestIndex;
    }

    private void announceKeyText(Keyboard.Key key) {
        if (!accessibilityManager.isEnabled()) return;
        String text = null;
        int code = key.codes[0];
        if (code == -5) text = "Delete";
        else if (code == -1) text = isCaps ? "Shift On" : "Shift";
        else if (code == 32) text = "Space";
        else if (code == -4) text = "Enter";
        else if (code == -2) text = "Numbers";
        else if (code == -6) text = "Alphabet";
        else if (code == -101) text = "Next Language";
        else if (code == -10) text = "Voice Typing";
        if (text == null && key.label != null) text = key.label.toString();
        if (text == null && key.text != null) text = key.text.toString();
        if (text != null) speakSystem(text);
    }

    private void startVoiceInput() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            speakSystem("Voice typing not supported");
        }
    }

    private void speakSystem(String text) {
        if (accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }

    private void playHaptic() {
        if (isVibrateOn) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(40);
                    }
                }
            } catch (Exception e) {}
        }
    }
    
    private void playSound() {
        if (isSoundOn) {
            try {
                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 1.0f);
            } catch (Exception e) {}
        }
    }
    
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}

