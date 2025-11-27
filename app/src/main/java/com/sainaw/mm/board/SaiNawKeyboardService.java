package com.sainaw.mm.board;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.os.UserManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SaiNawKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    // --- Constants ---
    private static final int CODE_DELETE = -5;
    private static final int CODE_ENTER = -4;
    private static final int CODE_SPACE = 32;
    private static final int CODE_SHIFT = -1;
    private static final int CODE_SYMBOL_ON = -2;
    private static final int CODE_SYMBOL_OFF = -6;
    private static final int CODE_LANG_CHANGE = -101;
    private static final int CODE_VOICE = -10;

    // Myanmar/Shan Unicode Constants
    private static final int MM_THWAY_HTOE = 4145; // 'ေ' (U+1031)
    private static final int SHAN_E = 4228;        // 'ႄ' (U+1084)
    private static final char ZWSP = '\u200B';     // Placeholder Space

    // Components
    private KeyboardView keyboardView;
    private LinearLayout candidateContainer;
    private List<TextView> candidateViews = new ArrayList<>();
    private SaiNawAccessibilityHelper accessibilityHelper;
    private SuggestionDB suggestionDB;
    private StringBuilder currentWord = new StringBuilder();

    // Voice
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isListening = false;

    // Keyboards
    private Keyboard qwertyKeyboard, qwertyShiftKeyboard;
    private Keyboard myanmarKeyboard, myanmarShiftKeyboard;
    private Keyboard shanKeyboard, shanShiftKeyboard;
    private Keyboard symbolsEnKeyboard, symbolsMmKeyboard; 
    private Keyboard currentKeyboard;

    // State
    private boolean isCaps = false;
    private boolean isSymbols = false; 
    private int currentLanguageId = 0; 

    // System Services
    private AudioManager audioManager;
    private AccessibilityManager accessibilityManager;
    private SharedPreferences prefs;

    // Settings
    private int lastHoverKeyIndex = -1;
    private boolean isVibrateOn = true;
    private boolean isSoundOn = true;

    // Threading
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private final View.OnClickListener candidateListener = v -> {
        String suggestion = (String) v.getTag();
        if (suggestion != null) pickSuggestion(suggestion);
    };

    private Runnable pendingCandidateUpdate = this::performCandidateSearch;

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
    
    // --- DIRECT BOOT HELPERS ---
    private boolean isUserUnlocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
            return um != null && um.isUserUnlocked();
        }
        return true;
    }

    private Context getSafeContext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isUserUnlocked()) return this;
            else return createDeviceProtectedStorageContext();
        }
        return this;
    }

    private final BroadcastReceiver userUnlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                dbExecutor.execute(() -> {
                    if (suggestionDB == null) suggestionDB = SuggestionDB.getInstance(SaiNawKeyboardService.this);
                });
            }
        }
    };

    @Override
    public View onCreateInputView() {
        prefs = getSafeContext().getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        loadSettings();

        boolean isDarkTheme = prefs.getBoolean("dark_theme", false);
        int layoutRes = isDarkTheme ? R.layout.input_view_dark : R.layout.input_view;
        View layout = getLayoutInflater().inflate(layoutRes, null);

        keyboardView = layout.findViewById(R.id.keyboard_view);
        candidateContainer = layout.findViewById(R.id.candidates_container);

        initCandidateViews(isDarkTheme);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        accessibilityManager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        
        if (isUserUnlocked()) {
            suggestionDB = SuggestionDB.getInstance(this);
        } else {
            suggestionDB = null; 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                registerReceiver(userUnlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            }
        }

        initKeyboards(); 

        currentLanguageId = 0;
        isCaps = false;
        isSymbols = false;
        
        updateKeyboardLayout(); 
        keyboardView.setOnKeyboardActionListener(this);

        setupSpeechRecognizer();

        accessibilityHelper = new SaiNawAccessibilityHelper(keyboardView, (primaryCode, key) -> handleInput(primaryCode, key));
        ViewCompat.setAccessibilityDelegate(keyboardView, accessibilityHelper);
        updateHelperState();

        keyboardView.setOnHoverListener((v, event) -> {
            boolean handled = accessibilityHelper.dispatchHoverEvent(event);
            handleLiftToType(event);
            return handled;
        });

        keyboardView.setOnTouchListener((v, event) -> false);
        return layout;
    }
    
    private void loadSettings() {
        isVibrateOn = prefs.getBoolean("vibrate_on", true);
        isSoundOn = prefs.getBoolean("sound_on", true);
    }

    public int getResId(String name) {
        return getResources().getIdentifier(name, "xml", getPackageName());
    }

    private void initKeyboards() {
        try {
            boolean showNumRow = prefs.getBoolean("number_row", false);
            String suffix = showNumRow ? "_num" : "";
            
            qwertyKeyboard = new Keyboard(this, getResId("qwerty" + suffix));
            myanmarKeyboard = new Keyboard(this, getResId("myanmar" + suffix));
            shanKeyboard = new Keyboard(this, getResId("shan" + suffix));
            
            qwertyShiftKeyboard = new Keyboard(this, getResId("qwerty_shift"));
            myanmarShiftKeyboard = new Keyboard(this, getResId("myanmar_shift"));
            shanShiftKeyboard = new Keyboard(this, getResId("shan_shift"));
            
            int symEnId = getResId("symbols");
            int symMmId = getResId("symbols_mm");
            symbolsEnKeyboard = (symEnId != 0) ? new Keyboard(this, symEnId) : qwertyKeyboard; 
            symbolsMmKeyboard = (symMmId != 0) ? new Keyboard(this, symMmId) : symbolsEnKeyboard;
            
        } catch (Exception e) {
            e.printStackTrace();
            qwertyKeyboard = new Keyboard(this, getResId("qwerty"));
        }
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
                @Override public void onError(int error) { isListening = false; playHaptic(-5); }
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

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        prefs = getSafeContext().getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        loadSettings();
        try { initKeyboards(); } catch (Exception e) { e.printStackTrace(); }
        
        currentWord.setLength(0);
        isCaps = false;
        isSymbols = false;
        
        updateKeyboardLayout(); 
        triggerCandidateUpdate(0);
    }

    // --- TALKBACK SAFE: 20px MARGIN BOUNDS CHECK ---
    private void handleLiftToType(MotionEvent event) {
        try {
            int action = event.getAction();
            float x = event.getX();
            float y = event.getY();
            int width = keyboardView.getWidth();
            
            // Safety Margin: Cancel if < 20px from ANY edge
            if (y < 20 || y > keyboardView.getHeight() - 20 || x < 20 || x > width - 20) {
                lastHoverKeyIndex = -1;
                return;
            }

            if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
                int keyIndex = getNearestKeyIndexFast((int)x, (int)y);
                if (keyIndex != -1 && keyIndex != lastHoverKeyIndex) {
                    lastHoverKeyIndex = keyIndex;
                    playHaptic(0);
                }
            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                if (lastHoverKeyIndex != -1) {
                    List<Keyboard.Key> keys = currentKeyboard.getKeys();
                    if (keys != null && lastHoverKeyIndex < keys.size()) {
                        Keyboard.Key key = keys.get(lastHoverKeyIndex);
                        if (key.isInside((int)x, (int)y)) {
                             if (key.codes[0] != -100) {
                                handleInput(key.codes[0], key);
                            }
                        }
                    }
                }
                lastHoverKeyIndex = -1;
            }
        } catch (Exception e) { lastHoverKeyIndex = -1; }
    }

    private void updateHelperState() {
        if (accessibilityHelper != null) {
            accessibilityHelper.setKeyboard(currentKeyboard, isShanOrMyanmar(), isCaps);
        }
    }

    @Override public void onKey(int primaryCode, int[] keyCodes) { handleInput(primaryCode, null); }
    
    @Override public void onText(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(text, 1);
        playSound(0);
        if (isCaps) { isCaps = false; updateKeyboardLayout(); }
    }

    @Override public void onPress(int primaryCode) {
        if (primaryCode == CODE_SPACE) {
            isSpaceLongPressed = false;
            handler.postDelayed(spaceLongPressRunnable, 600);
        }
        playHaptic(primaryCode);
    }

    @Override public void onRelease(int primaryCode) {
        if (primaryCode == CODE_SPACE) handler.removeCallbacks(spaceLongPressRunnable);
    }

    // --- MAIN INPUT HANDLING (WITH ALL FIXES) ---
    private void handleInput(int primaryCode, Keyboard.Key key) {
        playSound(primaryCode);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        if (key != null && key.text != null) {
            ic.commitText(key.text, 1);
            if (isCaps) { isCaps = false; updateKeyboardLayout(); announceText("Shift Off"); }
            return;
        }

        try {
            switch (primaryCode) {
                case CODE_DELETE:
                    CharSequence textBeforeDelete = ic.getTextBeforeCursor(1, 0);
                    if (textBeforeDelete != null && textBeforeDelete.length() == 1 && textBeforeDelete.charAt(0) == ZWSP) {
                         ic.deleteSurroundingText(1, 0); 
                    }
                    
                    CharSequence textBefore = ic.getTextBeforeCursor(1, 0);
                    ic.deleteSurroundingText(1, 0); 
                    if (textBefore != null && textBefore.length() > 0) announceText("Deleted " + textBefore.toString());
                    else announceText("Delete");
                    if (currentWord.length() > 0) {
                        currentWord.deleteCharAt(currentWord.length() - 1);
                        triggerCandidateUpdate(50);
                    }
                    break;
                    
                case CODE_VOICE: startVoiceInput(); break;
                case CODE_SHIFT: isCaps = !isCaps; updateKeyboardLayout(); announceText(isCaps ? "Shift On" : "Shift Off"); break;
                case CODE_SYMBOL_ON: isSymbols = true; updateKeyboardLayout(); announceText("Symbols Keyboard"); break;
                case CODE_SYMBOL_OFF: 
                    isSymbols = false; updateKeyboardLayout(); 
                    announceText(currentLanguageId == 1 ? "Myanmar" : (currentLanguageId == 2 ? "Shan" : "English")); 
                    break;
                case CODE_LANG_CHANGE: changeLanguage(); break;
                case CODE_ENTER: 
                    EditorInfo editorInfo = getCurrentInputEditorInfo();
                    boolean isMultiLine = (editorInfo.inputType & EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
                    int action = editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
                    if (!isMultiLine && (action >= EditorInfo.IME_ACTION_GO && action <= EditorInfo.IME_ACTION_DONE)) {
                        sendDefaultEditorAction(true);
                    } else {
                        ic.commitText("\n", 1);
                    }
                    saveWordAndReset();
                    break;
                case CODE_SPACE:
                    if (!isSpaceLongPressed) {
                        ic.commitText(" ", 1);
                        saveWordAndReset();
                    }
                    isSpaceLongPressed = false;
                    break;
                    
                default:
                    String charStr = (key != null && key.label != null && key.label.length() > 1) 
                            ? key.label.toString() 
                            : String.valueOf((char) primaryCode);

                    if (isShanOrMyanmar()) {
                        if (primaryCode == MM_THWAY_HTOE || primaryCode == SHAN_E) {
                            ic.commitText(String.valueOf(ZWSP) + charStr, 1);
                            currentWord.append(charStr); 
                        }
                        else {
                            CharSequence lastTwo = ic.getTextBeforeCursor(2, 0);
                            boolean handled = false;

                            if (lastTwo != null && lastTwo.length() == 2) {
                                char charBefore = lastTwo.charAt(1);
                                char charTwoBefore = lastTwo.charAt(0);
                                if ((charBefore == MM_THWAY_HTOE || charBefore == SHAN_E) && charTwoBefore == ZWSP) {
                                    ic.beginBatchEdit();
                                    ic.deleteSurroundingText(2, 0); 
                                    ic.commitText(charStr, 1);      
                                    ic.commitText(String.valueOf(charBefore), 1); 
                                    ic.endBatchEdit();
                                    handled = true;
                                }
                            }
                            
                            if (!handled) {
                                ic.commitText(charStr, 1);
                            }
                            currentWord.append(charStr);
                        }
                    } 
                    else {
                        ic.commitText(charStr, 1);
                        currentWord.append(charStr);
                    }
                    
                    if (isCaps) { isCaps = false; updateKeyboardLayout(); }
                    triggerCandidateUpdate(200);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // --- MISSING METHODS ADDED HERE ---

    private void announceText(String text) {
        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            accessibilityManager.sendAccessibilityEvent(event);
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
            try { speechRecognizer.startListening(speechIntent); isListening = true; } catch (Exception e) {}
        }
    }
    
    // Helper to identify Medials (not used in simplified logic but good to keep if needed later)
    private boolean isMedial(int code) {
        return (code >= 4155 && code <= 4158) || (code == 4226);
    }

    private boolean isShanOrMyanmar() {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard;
    }

    private void saveWordAndReset() {
        if (suggestionDB != null && currentWord.length() > 0) {
            final String wordToSave = currentWord.toString();
            dbExecutor.execute(() -> suggestionDB.saveWord(wordToSave));
        }
        currentWord.setLength(0);
        triggerCandidateUpdate(0);
    }

    private void triggerCandidateUpdate(long delayMillis) {
        handler.removeCallbacks(pendingCandidateUpdate);
        if (delayMillis > 0) handler.postDelayed(pendingCandidateUpdate, delayMillis);
        else handler.post(pendingCandidateUpdate);
    }

    private void performCandidateSearch() {
        if (suggestionDB == null) {
            handler.post(() -> { for (TextView tv : candidateViews) tv.setVisibility(View.GONE); });
            return;
        }
        final String searchWord = currentWord.toString();
        if (searchWord.isEmpty()) {
            handler.post(() -> { for (TextView tv : candidateViews) tv.setVisibility(View.GONE); });
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
                        tv.setTag(suggestion);
                        tv.setContentDescription("Suggestion " + (i + 1) + ", " + suggestion);
                        tv.setOnClickListener(candidateListener);
                        tv.setVisibility(View.VISIBLE);
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
        
        if (suggestionDB != null) {
            final String savedWord = suggestion;
            dbExecutor.execute(() -> suggestionDB.saveWord(savedWord));
        }
        currentWord.setLength(0);
        triggerCandidateUpdate(0);
    }

    private void updateKeyboardLayout() {
        lastHoverKeyIndex = -1;
        try {
            Keyboard nextKeyboard;
            if (isSymbols) {
                nextKeyboard = (currentLanguageId == 1) ? symbolsMmKeyboard : symbolsEnKeyboard;
            } else {
                if (currentLanguageId == 1) nextKeyboard = isCaps ? myanmarShiftKeyboard : myanmarKeyboard;
                else if (currentLanguageId == 2) nextKeyboard = isCaps ? shanShiftKeyboard : shanKeyboard;
                else nextKeyboard = isCaps ? qwertyShiftKeyboard : qwertyKeyboard;
            }
            currentKeyboard = nextKeyboard;
            if (keyboardView != null) {
                keyboardView.setKeyboard(currentKeyboard);
                keyboardView.invalidateAllKeys();
                updateHelperState();
            }
        } catch (Exception e) {
            currentKeyboard = qwertyKeyboard;
            if(keyboardView != null) keyboardView.setKeyboard(currentKeyboard);
        }
    }

    private void changeLanguage() {
        lastHoverKeyIndex = -1;
        String langName;
        if (currentLanguageId == 0) { currentLanguageId = 1; langName = "Myanmar"; } 
        else if (currentLanguageId == 1) { currentLanguageId = 2; langName = "Shan"; } 
        else { currentLanguageId = 0; langName = "English"; }
        
        announceText(langName);
        isCaps = false; isSymbols = false; 
        updateKeyboardLayout();
    }

    private int getNearestKeyIndexFast(int x, int y) {
        if (currentKeyboard == null || currentKeyboard.getKeys() == null) return -1;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys.isEmpty()) return -1;
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(40);
            }
        } catch (Exception e) {}
    }
    
    private void playSound(int primaryCode) {
        if (!isSoundOn) return;
        try {
            int soundEffect = AudioManager.FX_KEYPRESS_STANDARD;
            if (primaryCode == CODE_DELETE) soundEffect = AudioManager.FX_KEYPRESS_DELETE;
            else if (primaryCode == CODE_SPACE) soundEffect = AudioManager.FX_KEYPRESS_SPACEBAR;
            else if (primaryCode == CODE_ENTER) soundEffect = AudioManager.FX_KEYPRESS_RETURN;
            audioManager.playSoundEffect(soundEffect, 1.0f);
        } catch (Exception e) {}
    }
    
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}

