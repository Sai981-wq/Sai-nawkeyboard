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
import android.widget.Toast;
import androidx.core.view.ViewCompat; 
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SaiNawKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    private KeyboardView keyboardView;
    private LinearLayout candidateContainer;
    private List<TextView> candidateViews = new ArrayList<>();
    
    // Accessibility Helper (TalkBack အတွက်)
    private SaiNawAccessibilityHelper accessibilityHelper;
    
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
    private AudioManager audioManager;
    private SharedPreferences prefs;
    
    private int lastHoverKeyIndex = -1;
    private boolean isVibrateOn = true;
    private boolean isSoundOn = true;

    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    
    // Performance: စာရိုက်နေတုန်း Suggestion ခဏစောင့်မည့် Runnable
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
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        
        // Database ကို Singleton ပြောင်းထားရင် .getInstance(this) သုံးပါ
        // မပြောင်းရသေးရင် new SuggestionDB(this) သုံးပါ
        suggestionDB = new SuggestionDB(this);

        initKeyboards();

        currentKeyboard = qwertyKeyboard;
        keyboardView.setKeyboard(currentKeyboard);
        keyboardView.setOnKeyboardActionListener(this);

        // *** GBOARD STYLE ACCESSIBILITY SETUP ***
        // Helper Class ကို အရင်ဆောက်ပြီးမှ ဒါကိုသုံးလို့ရမယ်
        accessibilityHelper = new SaiNawAccessibilityHelper(keyboardView);
        ViewCompat.setAccessibilityDelegate(keyboardView, accessibilityHelper);
        updateHelperState();

        // Hover Listener (TalkBack + Lift to Type)
        keyboardView.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, MotionEvent event) {
                // ၁။ TalkBack ကို အရင်အသိပေးမယ် (ExploreByTouch)
                boolean handledByAccessibility = accessibilityHelper.dispatchHoverEvent(event);
                
                // ၂။ Lift-to-type Logic (စာရိုက်ဖို့အတွက်)
                handleLiftToType(event);

                return handledByAccessibility; 
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
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
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
        triggerCandidateUpdate(0);
        updateHelperState();
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

    // *** LIFT TO TYPE LOGIC (Fast & No Dead Zone) ***
    private void handleLiftToType(MotionEvent event) {
        int action = event.getAction();
        int touchX = (int) event.getX();
        int touchY = (int) event.getY();

        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
            // လက်တင်ထားချိန် (Explore) - Haptic ပဲပေးမယ်
            int keyIndex = getNearestKeyIndexFast(touchX, touchY);
            if (keyIndex != -1 && keyIndex != lastHoverKeyIndex) {
                lastHoverKeyIndex = keyIndex;
                playHaptic(0); 
            }
        } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
            // လက်ကြွလိုက်ချိန် (Type)
            if (lastHoverKeyIndex != -1) {
                List<Keyboard.Key> keys = currentKeyboard.getKeys();
                if (lastHoverKeyIndex < keys.size()) {
                    Keyboard.Key key = keys.get(lastHoverKeyIndex);
                    if (key.codes[0] != -100) {
                        handleInput(key.codes[0], key);
                    }
                }
                // Dead Zone မဖြစ်အောင် ချက်ချင်း Reset ချ
                lastHoverKeyIndex = -1;
            }
        }
    }
    
    private void updateHelperState() {
        if (accessibilityHelper != null) {
            accessibilityHelper.setKeyboard(currentKeyboard, isShanOrMyanmar(), isCaps);
        }
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

        if (key != null && key.text != null) {
            ic.commitText(key.text, 1);
            return;
        }

        switch (primaryCode) {
            case -10: 
                startVoiceInput(); // Voice Typing Fix ပါတဲ့ Method ကို ခေါ်မယ်
                break;
            case -1: 
                isCaps = !isCaps;
                updateKeyboardLayout(); 
                break;
            case -2: 
                currentKeyboard = symbolsKeyboard;
                updateKeyboardLayout();
                break;
            case -6: 
                currentKeyboard = qwertyKeyboard;
                updateKeyboardLayout();
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
                break;
            case -5: 
                ic.deleteSurroundingText(1, 0);
                if (currentWord.length() > 0) {
                    currentWord.deleteCharAt(currentWord.length() - 1);
                    triggerCandidateUpdate(50); 
                }
                break;
            case 32: 
                if (!isSpaceLongPressed) {
                    ic.commitText(" ", 1);
                    saveWordAndReset();
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
                // စာရိုက်ရင် Delay ခံပြီးမှ Suggestion ရှာမယ် (မထစ်အောင်)
                triggerCandidateUpdate(200); 
        }
    }
    
    // *** VOICE TYPING FIX (CRASH မဖြစ်အောင် ပြင်ထားသည်) ***
    private void startVoiceInput() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "စကားပြောပါ...");
            
            // *** ဒီ LINE က အရေးအကြီးဆုံးပါ ***
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException a) {
            Toast.makeText(this, "Google Voice Typing မရှိပါ", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error starting voice input", Toast.LENGTH_SHORT).show();
        }
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
                    for (TextView tv : candidateViews) tv.setVisibility(View.GONE);
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
        
        // Helper ကို Layout ပြောင်းပြီလို့ လှမ်းပြောမယ်
        updateHelperState();
    }

    private void changeLanguage() {
        lastHoverKeyIndex = -1;
        if (currentKeyboard == qwertyKeyboard || currentKeyboard == qwertyShiftKeyboard || currentKeyboard == symbolsKeyboard) {
            currentKeyboard = myanmarKeyboard;
        } else if (currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard) {
            currentKeyboard = shanKeyboard;
        } else {
            currentKeyboard = qwertyKeyboard;
        }
        isCaps = false;
        keyboardView.setKeyboard(currentKeyboard);
        updateHelperState();
    }

    private int getNearestKeyIndexFast(int x, int y) {
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).isInside(x, y)) {
                if (keys.get(i).codes[0] == -100) return -1;
                return i;
            }
        }
        return -1;
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
            if (primaryCode == -5) soundEffect = AudioManager.FX_KEYPRESS_DELETE;
            else if (primaryCode == 32) soundEffect = AudioManager.FX_KEYPRESS_SPACEBAR;
            else if (primaryCode == -4) soundEffect = AudioManager.FX_KEYPRESS_RETURN;
            audioManager.playSoundEffect(soundEffect, 1.0f);
        } catch (Exception e) {}
    }
    
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}

