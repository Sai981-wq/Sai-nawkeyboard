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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SaiNawKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    private KeyboardView keyboardView;
    private LinearLayout candidateContainer;
    private List<TextView> candidateViews = new ArrayList<>();
    
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
    private ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    
    // *** Delay Update Runnable ***
    private Runnable pendingCandidateUpdate = new Runnable() {
        @Override
        public void run() {
            performCandidateSearch();
        }
    };

    private boolean isSpaceLongPressed = false;
    private Runnable spaceLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            isSpaceLongPressed = true;
            InputMethodManager imeManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imeManager != null) {
                imeManager.showInputMethodPicker();
                playHaptic(-99);
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
        
        initCandidateViews(isDarkTheme);

        accessibilityManager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        suggestionDB = new SuggestionDB(this);

        initKeyboards();

        currentKeyboard = qwertyKeyboard;
        keyboardView.setKeyboard(currentKeyboard);
        keyboardView.setOnKeyboardActionListener(this);

        // Hover Logic
        keyboardView.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, MotionEvent event) {
                return handleHover(event);
            }
        });

        keyboardView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return false; 
            }
        });

        return layout;
    }

    private void initCandidateViews(boolean isDarkTheme) {
        candidateContainer.removeAllViews();
        candidateViews.clear();
        int textColor = isDarkTheme ? Color.WHITE : Color.BLACK;

        for (int i = 0; i < 3; i++) {
            TextView tv = new TextView(this);
            tv.setTextSize(18);
            tv.setTextColor(textColor);
            tv.setPadding(30, 10, 30, 10);
            tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(android.R.drawable.btn_default);
            tv.setFocusable(true);
            tv.setVisibility(View.GONE); 
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            params.setMargins(5, 0, 5, 0);
            
            candidateContainer.addView(tv, params);
            candidateViews.add(tv);
        }
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
        triggerCandidateUpdate(0); // Start fresh
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
        }
    }

    private void loadSettings() {
        isVibrateOn = prefs.getBoolean("vibrate_on", true);
        isSoundOn = prefs.getBoolean("sound_on", true);
    }

    // *** HOVER LOGIC (Fixed for Fast Typing) ***
    private boolean handleHover(MotionEvent event) {
        int action = event.getAction();
        int touchX = (int) event.getX();
        int touchY = (int) event.getY();
        
        // Math.sqrt မပါတဲ့ ပိုမြန်တဲ့ Logic ကို သုံးပါမယ်
        int keyIndex = getNearestKeyIndexFast(touchX, touchY);

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                if (keyIndex != -1 && keyIndex != lastHoverKeyIndex) {
                    lastHoverKeyIndex = keyIndex;
                    Keyboard.Key key = currentKeyboard.getKeys().get(keyIndex);
                    
                    // Explore လုပ်နေချိန် (လက်မကြွသေး)
                    playHaptic(0);
                    announceKeyText(key, true); 
                }
                break;

            case MotionEvent.ACTION_HOVER_EXIT:
                // လက်ကြွလိုက်ချိန် (Lift)
                if (lastHoverKeyIndex != -1) {
                    List<Keyboard.Key> keys = currentKeyboard.getKeys();
                    if (lastHoverKeyIndex < keys.size()) {
                        Keyboard.Key key = keys.get(lastHoverKeyIndex);
                        handleInput(key.codes[0], key);
                    }
                    // *** အရေးကြီးဆုံးအချက် ***
                    // ချက်ချင်း Reset ချရမယ်၊ ဒါမှ နောက်တစ်ခါ ချက်ချင်းထိရင် အသစ်လို့မြင်မှာ
                    lastHoverKeyIndex = -1;
                }
                break;
        }
        return true;
    }
    
    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        handleInput(primaryCode, null);
    }

    @Override
    public void onText(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(text, 1);
        playSound(0);
        if (isCaps) {
            isCaps = false;
            updateKeyboardLayout();
        }
    }

    @Override
    public void onPress(int primaryCode) {
        if (primaryCode == 32) {
            isSpaceLongPressed = false;
            handler.postDelayed(spaceLongPressRunnable, 600);
        }
        playHaptic(primaryCode); 
    }

    @Override
    public void onRelease(int primaryCode) {
        if (primaryCode == 32) {
            handler.removeCallbacks(spaceLongPressRunnable);
        }
    }

    private void handleInput(int primaryCode, Keyboard.Key key) {
        playSound(primaryCode); 

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        String textToSpeak = null; 

        if (key != null && key.text != null) {
            ic.commitText(key.text, 1);
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
                saveWordAndReset(); 
                textToSpeak = "Enter";
                break;
            case -5: 
                ic.deleteSurroundingText(1, 0);
                if (currentWord.length() > 0) {
                    currentWord.deleteCharAt(currentWord.length() - 1);
                    // Delete ဆိုရင်တော့ မြန်မြန် Update လုပ်ပေးမှ အဆင်ပြေမယ်
                    triggerCandidateUpdate(50); 
                }
                textToSpeak = "Delete";
                break;
            case 32: 
                if (!isSpaceLongPressed) {
                    ic.commitText(" ", 1);
                    saveWordAndReset();
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
                    String charStr;
                    if (key != null && key.label != null && key.label.length() > 1) {
                        charStr = key.label.toString();
                    } else {
                        charStr = String.valueOf((char) primaryCode);
                    }
                    ic.commitText(charStr, 1);
                    currentWord.append(charStr);
                }
                
                if (isCaps) {
                    isCaps = false;
                    updateKeyboardLayout();
                }
                
                // *** အဓိက ပြင်ဆင်ချက် ***
                // စာလုံးရိုက်ရင် 200ms နေမှ Suggestion ရှာမယ်
                // ဒီကြားထဲမှာ လက်ပြန်ထိရင် Touch က အရင်ဝင်သွားလိမ့်မယ် (Layout မပြောင်းသေးလို့)
                triggerCandidateUpdate(200); 
        }

        if (textToSpeak != null) speakSystem(textToSpeak, false);
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

    private boolean isConsonant(int code) {
        return (code >= 4096 && code <= 4138) || (code >= 4213 && code <= 4225) || code == 4100 || code == 4101;
    }

    private boolean isShanOrMyanmar() {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard;
    }

    private void saveWordAndReset() {
        if (currentWord.length() > 0) {
            final String wordToSave = currentWord.toString();
            dbExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    suggestionDB.saveWord(wordToSave);
                }
            });
            currentWord.setLength(0);
            triggerCandidateUpdate(0);
        }
    }

    // Delay ချိန်ကို Parameter အနေနဲ့ လက်ခံမယ်
    private void triggerCandidateUpdate(long delayMillis) {
        handler.removeCallbacks(pendingCandidateUpdate);
        if (delayMillis > 0) {
            handler.postDelayed(pendingCandidateUpdate, delayMillis);
        } else {
            handler.post(pendingCandidateUpdate);
        }
    }

    private void performCandidateSearch() {
        final String searchWord = currentWord.toString();
        
        if (searchWord.isEmpty()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    for (TextView tv : candidateViews) {
                        tv.setVisibility(View.GONE);
                    }
                }
            });
            return;
        }

        dbExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final List<String> suggestions = suggestionDB.getSuggestions(searchWord);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < candidateViews.size(); i++) {
                            TextView tv = candidateViews.get(i);
                            if (i < suggestions.size()) {
                                final String suggestion = suggestions.get(i);
                                tv.setText(suggestion);
                                tv.setVisibility(View.VISIBLE);
                                tv.setOnClickListener(v -> pickSuggestion(suggestion));
                            } else {
                                tv.setVisibility(View.GONE);
                            }
                        }
                    }
                });
            }
        });
    }

    private void pickSuggestion(String suggestion) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.deleteSurroundingText(currentWord.length(), 0);
        ic.commitText(suggestion + " ", 1);
        
        final String savedWord = suggestion;
        dbExecutor.execute(new Runnable() {
            @Override
            public void run() {
                suggestionDB.saveWord(savedWord);
            }
        });
        
        currentWord.setLength(0);
        triggerCandidateUpdate(0);
        speakSystem("Selected " + suggestion, true);
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
            speakSystem("Myanmar", true);
        } else if (currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard) {
            currentKeyboard = shanKeyboard;
            speakSystem("Shan", true);
        } else {
            currentKeyboard = qwertyKeyboard;
            speakSystem("English", true);
        }
        isCaps = false;
        keyboardView.setKeyboard(currentKeyboard);
    }

    // *** Improved Fast Logic for finding key ***
    private int getNearestKeyIndexFast(int x, int y) {
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        
        // 1. Check direct hit first (Fastest)
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).isInside(x, y)) return i;
        }

        // 2. Check distance without Sqrt (Faster than previous)
        int closestIndex = -1;
        int minDistSq = Integer.MAX_VALUE;
        int threshold = 150 * 150; 
        
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            int kx = key.x + key.width / 2;
            int ky = key.y + key.height / 2;
            
            int dx = x - kx;
            int dy = y - ky;
            int distSq = dx*dx + dy*dy;

            if (distSq < minDistSq && distSq < threshold) {
                minDistSq = distSq;
                closestIndex = i;
            }
        }
        return closestIndex;
    }

    private void announceKeyText(Keyboard.Key key, boolean shouldInterrupt) {
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
        
        if (text != null && isShanOrMyanmar() && isCaps) {
             text = "Sub " + text;
        }
        
        if (text != null) speakSystem(text, shouldInterrupt);
    }

    private void startVoiceInput() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            speakSystem("Voice typing not supported", false);
        }
    }

    private void speakSystem(String text, boolean shouldInterrupt) {
        if (accessibilityManager.isEnabled()) {
            if (shouldInterrupt) {
                accessibilityManager.interrupt();
            }
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            event.setContentDescription(text);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }

    private void playHaptic(int primaryCode) {
        if (!isVibrateOn) return;
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                int duration = 40;
                int amplitude = VibrationEffect.DEFAULT_AMPLITUDE;
                
                if (primaryCode == -5 || primaryCode == -4 || primaryCode == 32) {
                    duration = 70;
                    amplitude = 255;
                } else if (primaryCode == -1 || primaryCode == -2) {
                    duration = 50;
                } else if (primaryCode == 0) {
                    duration = 30; 
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(duration, amplitude));
                } else {
                    v.vibrate(duration);
                }
            }
        } catch (Exception e) {}
    }
    
    private void playSound(int primaryCode) {
        if (!isSoundOn) return;
        try {
            int soundEffect = AudioManager.FX_KEYPRESS_STANDARD;
            if (primaryCode == -5) {
                soundEffect = AudioManager.FX_KEYPRESS_DELETE;
            } else if (primaryCode == 32) {
                soundEffect = AudioManager.FX_KEYPRESS_SPACEBAR;
            } else if (primaryCode == -4) {
                soundEffect = AudioManager.FX_KEYPRESS_RETURN;
            }
            audioManager.playSoundEffect(soundEffect, 1.0f);
        } catch (Exception e) {}
    }
    
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}

