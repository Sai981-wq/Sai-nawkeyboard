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
    
    // TalkBack Optimization Variables
    private int lastHoverKeyIndex = -1;
    private float lastTouchX = 0;
    private float lastTouchY = 0;
    
    // Preferences
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

        // --- TalkBack Handling ---
        keyboardView.setOnHoverListener((v, event) -> handleHover(event));

        // --- Normal Touch Handling ---
        // IMPORTANT: We do NOT block touch here anymore. We control it inside onKey.
        // This ensures normal typing is fast and responsive.
        
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

    // ====================================================================================
    // SECTION 1: CENTRALIZED INPUT DISPATCHER (The Brain)
    // ====================================================================================
    // All inputs (Normal Touch, TalkBack Hover, Text Strings) must pass through here.
    
    private void commitChar(int primaryCode, String keyLabel) {
        playSound();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        String textToSpeak = null;

        // 1. Handle Special Keys
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
                // Language change speaks inside its own method
                break;
            case -4: // Enter
                sendEnter(ic);
                saveCurrentWordToDB();
                textToSpeak = "Enter";
                break;
            case -5: // Delete
                ic.deleteSurroundingText(1, 0);
                if (currentWord.length() > 0) {
                    currentWord.deleteCharAt(currentWord.length() - 1);
                    updateCandidates();
                }
                textToSpeak = "Delete";
                break;
            case 32: // Space
                if (!isSpaceLongPressed) {
                    ic.commitText(" ", 1);
                    saveCurrentWordToDB();
                    textToSpeak = "Space";
                }
                isSpaceLongPressed = false;
                break;
            case 0: 
                // Dummy code, do nothing
                break;
                
            default:
                // 2. Handle Text Characters
                String textToCommit;
                
                // Check 1: Is it a Compound Character from XML? (e.g., ေႃ)
                if (keyLabel != null && keyLabel.length() > 1) {
                    textToCommit = keyLabel;
                } 
                // Check 2: Normal Character
                else {
                    textToCommit = String.valueOf((char) primaryCode);
                }

                // 3. Smart Reordering Logic
                if (isShanOrMyanmar()) {
                    if (handleSmartReordering(ic, primaryCode)) {
                        // Reordering handled the commit already
                        char code = (char) primaryCode;
                        currentWord.append(String.valueOf(code));
                        // For reordering, we just speak the character pressed
                        textToSpeak = textToCommit; 
                    } else {
                        ic.commitText(textToCommit, 1);
                        currentWord.append(textToCommit);
                        textToSpeak = textToCommit;
                    }
                } else {
                    // Standard Typing
                    ic.commitText(textToCommit, 1);
                    currentWord.append(textToCommit);
                    textToSpeak = textToCommit;
                }

                // Auto Unshift
                if (isCaps) {
                    isCaps = false;
                    updateKeyboardLayout();
                }
                updateCandidates();
        }

        // TalkBack Feedback (Speak what was typed)
        if (textToSpeak != null) speakSystem(textToSpeak);
    }

    // ====================================================================================
    // SECTION 2: EVENT LISTENERS (Triggers)
    // ====================================================================================

    // --- A. Normal Typing Triggers ---
    
    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        // TalkBack users use Hover, so we ignore standard onKey ONLY if TalkBack is ON
        // to prevent double typing.
        if (accessibilityManager.isEnabled()) return;
        
        // For normal typing, we pass the code. Label is null here, 
        // but for compound text, onText will be called instead.
        commitChar(primaryCode, null);
    }

    @Override
    public void onText(CharSequence text) {
        if (accessibilityManager.isEnabled()) return;
        // Normal typing for compound characters (e.g. from XML keyOutputText)
        commitChar(0, text.toString());
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

    // --- B. TalkBack (Hover) Triggers ---

    private boolean handleHover(MotionEvent event) {
        if (!accessibilityManager.isEnabled()) return false;

        int action = event.getAction();
        float touchX = event.getX();
        float touchY = event.getY();

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
                int enterIndex = getNearestKeyIndex((int)touchX, (int)touchY);
                if (enterIndex != -1) {
                    lastHoverKeyIndex = enterIndex;
                    playHaptic();
                    announceKeyText(currentKeyboard.getKeys().get(enterIndex));
                }
                break;

            case MotionEvent.ACTION_HOVER_MOVE:
                // OPTIMIZATION: Don't re-calculate if finger moved very little (Reduce Lag)
                if (Math.abs(touchX - lastTouchX) < 5 && Math.abs(touchY - lastTouchY) < 5) {
                    return true;
                }
                lastTouchX = touchX;
                lastTouchY = touchY;

                int moveIndex = getNearestKeyIndex((int)touchX, (int)touchY);
                if (moveIndex != -1 && moveIndex != lastHoverKeyIndex) {
                    lastHoverKeyIndex = moveIndex;
                    playHaptic();
                    announceKeyText(currentKeyboard.getKeys().get(moveIndex));
                }
                break;

            case MotionEvent.ACTION_HOVER_EXIT:
                if (lastHoverKeyIndex != -1) {
                    List<Keyboard.Key> keys = currentKeyboard.getKeys();
                    if (lastHoverKeyIndex < keys.size()) {
                        Keyboard.Key key = keys.get(lastHoverKeyIndex);
                        // TalkBack Commit: Pass both Code and Label (for compound chars)
                        String label = (key.text != null) ? key.text.toString() : null;
                        commitChar(key.codes[0], label);
                    }
                    lastHoverKeyIndex = -1;
                }
                break;
        }
        return true;
    }

    // ====================================================================================
    // SECTION 3: HELPER FUNCTIONS
    // ====================================================================================

    private int getNearestKeyIndex(int x, int y) {
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        
        // 1. Direct Hit (Fastest)
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).isInside(x, y)) return i;
        }
        
        // 2. Distance Search (Fallback)
        int closestIndex = -1;
        double minDistance = Double.MAX_VALUE;
        int threshold = 100; // Reduced threshold for better performance
        
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            int cx = key.x + key.width / 2;
            int cy = key.y + key.height / 2;
            double dist = Math.hypot(x - cx, y - cy); // Faster than sqrt logic
            
            if (dist < minDistance && dist < threshold) {
                minDistance = dist;
                closestIndex = i;
            }
        }
        return closestIndex;
    }

    private boolean handleSmartReordering(InputConnection ic, int primaryCode) {
        if (isConsonant(primaryCode)) { 
             CharSequence before = ic.getTextBeforeCursor(1, 0);
             if (before != null && before.length() > 0) {
                 char preChar = before.charAt(0);
                 // ေ (4145), ႄ (4228)
                 if (preChar == 4145 || preChar == 4228) { 
                     ic.deleteSurroundingText(1, 0);
                     ic.commitText(String.valueOf((char)primaryCode), 1);
                     ic.commitText(String.valueOf(preChar), 1);
                     return true;
                 }
             }
        }
        if (primaryCode == 4141) { // ိ
             CharSequence before = ic.getTextBeforeCursor(1, 0);
             if (before != null && before.length() > 0) {
                 char prev = before.charAt(0);
                 // ု (4143), ူ (4144)
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

    private boolean isConsonant(int code) {
        return (code >= 4096 && code <= 4138) || (code >= 4213 && code <= 4225) || code == 4100 || code == 4101;
    }

    private boolean isShanOrMyanmar() {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard;
    }

    private void sendEnter(InputConnection ic) {
        int options = getCurrentInputEditorInfo().imeOptions;
        int action = options & EditorInfo.IME_MASK_ACTION;
        if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_GO) {
            sendDefaultEditorAction(true);
        } else {
            ic.commitText("\n", 1);
        }
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
    
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}

