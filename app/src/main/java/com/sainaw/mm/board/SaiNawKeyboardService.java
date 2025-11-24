package com.sainaw.mm.board;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SaiNawKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    private KeyboardView keyboardView;
    private LinearLayout candidateContainer;
    private List<TextView> candidateViews = new ArrayList<>();

    private SaiNawAccessibilityHelper accessibilityHelper;
    private SuggestionDB suggestionDB;
    private StringBuilder currentWord = new StringBuilder();

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isListening = false;

    private Keyboard qwertyKeyboard, qwertyShiftKeyboard;
    private Keyboard myanmarKeyboard, myanmarShiftKeyboard;
    private Keyboard shanKeyboard, shanShiftKeyboard;
    // Symbols ခွဲထုတ်ခြင်း (Language Aware)
    private Keyboard symbolsEnKeyboard, symbolsMmKeyboard; 

    private Keyboard currentKeyboard;
    private boolean isCaps = false;
    private AudioManager audioManager;
    private SharedPreferences prefs;

    private int lastHoverKeyIndex = -1;
    private boolean isVibrateOn = true;
    private boolean isSoundOn = true;
    // လက်ရှိ ဘာသာစကား (0=Eng, 1=MM, 2=Shan)
    private int currentLanguageId = 0; 

    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

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
        suggestionDB = SuggestionDB.getInstance(this); // Singleton

        initKeyboards();

        currentKeyboard = qwertyKeyboard;
        currentLanguageId = 0; // Default English
        keyboardView.setKeyboard(currentKeyboard);
        keyboardView.setOnKeyboardActionListener(this);

        setupSpeechRecognizer();

        accessibilityHelper = new SaiNawAccessibilityHelper(keyboardView, new SaiNawAccessibilityHelper.OnAccessibilityKeyListener() {
            @Override
            public void onAccessibilityKeyClick(int primaryCode, Keyboard.Key key) {
                handleInput(primaryCode, key);
            }
        });
        ViewCompat.setAccessibilityDelegate(keyboardView, accessibilityHelper);
        updateHelperState();

        keyboardView.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, MotionEvent event) {
                boolean handled = accessibilityHelper.dispatchHoverEvent(event);
                handleLiftToType(event);
                return handled;
            }
        });

        keyboardView.setOnTouchListener((v, event) -> false);
        return layout;
    }

    private void setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "my-MM");
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "my-MM");
            speechIntent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "my-MM");
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { playHaptic(-10); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { isListening = false; }
                @Override public void onError(int error) { 
                    isListening = false; 
                    playHaptic(-5);
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0);
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) ic.commitText(text + " ", 1);
                    }
                    isListening = false;
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
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
        // *** (၆) Number Row Logic ***
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
        
        // *** (၃) Symbols Logic (မြန်မာဂဏန်း vs English ဂဏန်း) ***
        // symbols_mm.xml မှာ မြန်မာဂဏန်း ၁၂၃ ထည့်ပါ
        // symbols.xml (default) မှာ English 123 ထည့်ပါ
        symbolsEnKeyboard = new Keyboard(this, R.xml.symbols); // English 123
        try {
            symbolsMmKeyboard = new Keyboard(this, R.xml.symbols_mm); // Myanmar ၁၂၃
        } catch (Exception e) {
            symbolsMmKeyboard = symbolsEnKeyboard; // ဖိုင်မရှိရင် English ပဲသုံးမယ်
        }
        symbolsKeyboard = symbolsEnKeyboard; // Default
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        loadSettings();
        // *** (၆) Re-init keyboards if number row setting changed ***
        initKeyboards(); 
        
        // Reset state
        currentWord.setLength(0);
        isCaps = false;
        
        // Restore last keyboard based on ID
        if (currentLanguageId == 1) currentKeyboard = myanmarKeyboard;
        else if (currentLanguageId == 2) currentKeyboard = shanKeyboard;
        else currentKeyboard = qwertyKeyboard;
        
        keyboardView.setKeyboard(currentKeyboard);
        updateHelperState();
        triggerCandidateUpdate(0);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (dbExecutor != null && !dbExecutor.isShutdown()) dbExecutor.shutdown();
    }

    private void loadSettings() {
        isVibrateOn = prefs.getBoolean("vibrate_on", true);
        isSoundOn = prefs.getBoolean("sound_on", true);
    }

    private void handleLiftToType(MotionEvent event) {
        int action = event.getAction();
        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
            int keyIndex = getNearestKeyIndexFast((int)event.getX(), (int)event.getY());
            if (keyIndex != -1 && keyIndex != lastHoverKeyIndex) {
                lastHoverKeyIndex = keyIndex;
                playHaptic(0);
            }
        } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
            if (lastHoverKeyIndex != -1) {
                List<Keyboard.Key> keys = currentKeyboard.getKeys();
                if (lastHoverKeyIndex < keys.size()) {
                    Keyboard.Key key = keys.get(lastHoverKeyIndex);
                    if (key.codes[0] != -100) handleInput(key.codes[0], key);
                }
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
                startVoiceInput();
                break;
            case -1:
                isCaps = !isCaps;
                updateKeyboardLayout();
                break;
            case -2:
                // *** (၃) Symbols Logic ***
                if (currentLanguageId == 1) { // Myanmar
                    currentKeyboard = symbolsMmKeyboard; // Myanmar Numerals
                } else {
                    currentKeyboard = symbolsEnKeyboard; // English Numerals (Shan also uses Eng)
                }
                updateKeyboardLayout();
                break;
            case -6:
                // Back to ABC
                if (currentLanguageId == 1) currentKeyboard = myanmarKeyboard;
                else if (currentLanguageId == 2) currentKeyboard = shanKeyboard;
                else currentKeyboard = qwertyKeyboard;
                updateKeyboardLayout();
                break;
            case -101:
                changeLanguage();
                break;
            case -4:
                sendDefaultEditorAction(true);
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
                triggerCandidateUpdate(200);
        }
    }

    private void startVoiceInput() {
        if (speechRecognizer == null) return;
        if (isListening) {
            speechRecognizer.stopListening();
            isListening = false;
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            }
            try {
                speechRecognizer.startListening(speechIntent);
                isListening = true;
            } catch (Exception e) {}
        }
    }

    private boolean handleSmartReordering(InputConnection ic, int primaryCode) {
        // (Existing Logic)
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
            dbExecutor.execute(() -> suggestionDB.saveWord(wordToSave));
            currentWord.setLength(0);
            triggerCandidateUpdate(0);
        }
    }

    private void triggerCandidateUpdate(long delayMillis) {
        handler.removeCallbacks(pendingCandidateUpdate);
        if (delayMillis > 0) handler.postDelayed(pendingCandidateUpdate, delayMillis);
        else handler.post(pendingCandidateUpdate);
    }

    private void performCandidateSearch() {
        final String searchWord = currentWord.toString();
        // *** (၇) Suggestion Bar Logic Fix ***
        if (searchWord.isEmpty()) {
            handler.post(() -> {
                for (TextView tv : candidateViews) tv.setVisibility(View.GONE);
            });
            return;
        }

        dbExecutor.execute(() -> {
            final List<String> suggestions = suggestionDB.getSuggestions(searchWord);
            handler.post(() -> {
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
            });
        });
    }

    private void pickSuggestion(String suggestion) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.deleteSurroundingText(currentWord.length(), 0);
        ic.commitText(suggestion + " ", 1);
        final String savedWord = suggestion;
        dbExecutor.execute(() -> suggestionDB.saveWord(savedWord));
        currentWord.setLength(0);
        triggerCandidateUpdate(0);
    }

    private void updateKeyboardLayout() {
        lastHoverKeyIndex = -1;
        if (currentKeyboard == symbolsEnKeyboard || currentKeyboard == symbolsMmKeyboard) { 
            // Do nothing if in symbols
        } else if (isCaps) {
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
        updateHelperState();
    }

    private void changeLanguage() {
        lastHoverKeyIndex = -1;
        if (currentLanguageId == 0) { // Eng -> MM
            currentLanguageId = 1;
            currentKeyboard = myanmarKeyboard;
        } else if (currentLanguageId == 1) { // MM -> Shan
            currentLanguageId = 2;
            currentKeyboard = shanKeyboard;
        } else { // Shan -> Eng
            currentLanguageId = 0;
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(40);
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

