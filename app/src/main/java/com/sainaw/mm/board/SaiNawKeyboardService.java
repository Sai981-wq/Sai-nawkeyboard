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
    
    // Haptic Constants
    private static final int HAPTIC_FOCUS = 0; 
    private static final int HAPTIC_TYPE = 1;  
    private static final int HAPTIC_LONG_PRESS = 2;

    // Myanmar/Shan Unicode Constants
    private static final int MM_THWAY_HTOE = 4145; 
    private static final int SHAN_E = 4228;        
    private static final char ZWSP = '\u200B';     
    
    // Vowel Constants
    private static final int MM_I = 4141;          
    private static final int MM_U = 4143;          
    private static final int MM_UU = 4144;         
    private static final int MM_ANUSVARA = 4150;   

    // Components
    private KeyboardView keyboardView;
    private LinearLayout candidateContainer;
    private List<TextView> candidateViews = new ArrayList<>();
    private SaiNawAccessibilityHelper accessibilityHelper;
    private SuggestionDB suggestionDB;
    private StringBuilder currentWord = new StringBuilder();
    private SaiNawTextProcessor textProcessor;

    // Voice
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isListening = false;

    // Keyboards
    private Keyboard qwertyKeyboard, qwertyShiftKeyboard;
    private Keyboard myanmarKeyboard, myanmarShiftKeyboard;
    private Keyboard shanKeyboard, shanShiftKeyboard;
    private Keyboard symbolsEnKeyboard, symbolsMmKeyboard;
    private Keyboard numberKeyboard; 
    private Keyboard currentKeyboard;
    private List<Keyboard.Key> currentKeys; 

    // State
    private boolean isCaps = false;
    private boolean isSymbols = false; 
    private int currentLanguageId = 0; 
    private boolean isReceiverRegistered = false;
    
    // *** NEW: Store EditorInfo to update Enter key anytime ***
    private EditorInfo currentEditorInfo; 

    // System Services
    private AudioManager audioManager;
    private AccessibilityManager accessibilityManager;
    private SharedPreferences prefs;

    // Settings & State
    private int lastHoverKeyIndex = -1;
    private boolean isVibrateOn = true;
    private boolean isSoundOn = true;
    private boolean isLiftToType = true; 

    // Long Press Variables
    private boolean isLongPressHandled = false; 
    private boolean isDeleteActive = false;     

    // Threading
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    // --- RUNNABLES ---
    private final Runnable spaceLongPressTask = new Runnable() {
        @Override
        public void run() {
            isLongPressHandled = true; 
            playHaptic(HAPTIC_LONG_PRESS);
            InputMethodManager imeManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imeManager != null) {
                imeManager.showInputMethodPicker();
            }
        }
    };

    private final Runnable deleteStartTask = new Runnable() {
        @Override
        public void run() {
            isLongPressHandled = true;
            isDeleteActive = true;
            handler.post(deleteLoopTask); 
        }
    };

    private final Runnable deleteLoopTask = new Runnable() {
        @Override
        public void run() {
            if (isDeleteActive) {
                playHaptic(HAPTIC_TYPE); 
                handleInput(CODE_DELETE, null);
                handler.postDelayed(this, 80); 
            }
        }
    };

    private final View.OnClickListener candidateListener = v -> {
        String suggestion = (String) v.getTag();
        if (suggestion != null) pickSuggestion(suggestion);
    };

    private Runnable pendingCandidateUpdate = this::performCandidateSearch;
    
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

        textProcessor = new SaiNawTextProcessor();

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
                isReceiverRegistered = true;
            }
        }

        initKeyboards(); 

        currentLanguageId = 0;
        isCaps = false;
        
        keyboardView.setOnKeyboardActionListener(this);

        setupSpeechRecognizer();

        accessibilityHelper = new SaiNawAccessibilityHelper(keyboardView, (primaryCode, key) -> handleInput(primaryCode, key));
        ViewCompat.setAccessibilityDelegate(keyboardView, accessibilityHelper);
        updateHelperState();

        keyboardView.setOnHoverListener((v, event) -> {
            handleLiftToType(event); 
            return accessibilityHelper.dispatchHoverEvent(event);
        });

        keyboardView.setOnTouchListener((v, event) -> false);
        return layout;
    }
    
    private void loadSettings() {
        isVibrateOn = prefs.getBoolean("vibrate_on", true);
        isSoundOn = prefs.getBoolean("sound_on", true);
        isLiftToType = prefs.getBoolean("lift_to_type", true);
    }

    public int getResId(String name) {
        return getResources().getIdentifier(name, "xml", getPackageName());
    }

    private void initKeyboards() {
        try {
            boolean showNumRow = prefs.getBoolean("number_row", false);
            String engSuffix = showNumRow ? "_num" : "";
            qwertyKeyboard = new Keyboard(this, getResId("qwerty" + engSuffix));
            qwertyShiftKeyboard = new Keyboard(this, getResId("qwerty_shift"));
            myanmarKeyboard = new Keyboard(this, getResId("myanmar"));
            myanmarShiftKeyboard = new Keyboard(this, getResId("myanmar_shift"));
            shanKeyboard = new Keyboard(this, getResId("shan"));
            shanShiftKeyboard = new Keyboard(this, getResId("shan_shift"));
            int symEnId = getResId("symbols");
            int symMmId = getResId("symbols_mm");
            symbolsEnKeyboard = (symEnId != 0) ? new Keyboard(this, symEnId) : qwertyKeyboard; 
            symbolsMmKeyboard = (symMmId != 0) ? new Keyboard(this, symMmId) : symbolsEnKeyboard;
            
            // Dedicated Number Pad
            int numPadId = getResId("number_pad");
            numberKeyboard = (numPadId != 0) ? new Keyboard(this, numPadId) : symbolsEnKeyboard;

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
                @Override public void onReadyForSpeech(Bundle params) { playHaptic(HAPTIC_TYPE); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { isListening = false; }
                @Override public void onError(int error) { isListening = false; playHaptic(HAPTIC_TYPE); }
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
        
        // *** Store EditorInfo for dynamic Enter key ***
        currentEditorInfo = info; 
        
        // --- KEYBOARD SELECTION LOGIC ---
        int inputType = info.inputType & EditorInfo.TYPE_MASK_CLASS;
        
        if (inputType == EditorInfo.TYPE_CLASS_PHONE) {
            currentKeyboard = numberKeyboard;
            isSymbols = true; 
        } 
        else if (inputType == EditorInfo.TYPE_CLASS_NUMBER || 
                 inputType == EditorInfo.TYPE_CLASS_DATETIME) {
            currentKeyboard = numberKeyboard;
            isSymbols = true;
        } 
        else {
            isSymbols = false;
            updateKeyboardLayout();
            // Don't return, need to apply Enter key below
        }

        if (keyboardView != null) {
            // Apply keyboard if we manually set it (for Number Pad)
            if (isSymbols && (currentKeyboard == numberKeyboard)) {
                keyboardView.setKeyboard(currentKeyboard);
                currentKeys = currentKeyboard.getKeys();
            }
            
            // *** Apply Dynamic Enter Label ***
            updateEnterKeyLabel(); 

            keyboardView.invalidateAllKeys();
            updateHelperState();
        }
        
        triggerCandidateUpdate(0);
    }
    
    // *** Updated: Helper to Change Enter Key Label ***
    private void updateEnterKeyLabel() {
        if (currentKeyboard == null || keyboardView == null || currentEditorInfo == null) return;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        for (Keyboard.Key key : keys) {
            if (key.codes[0] == CODE_ENTER) {
                int action = currentEditorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;

                switch (action) {
                    case EditorInfo.IME_ACTION_GO:
                        key.label = "Go";
                        break;
                    case EditorInfo.IME_ACTION_NEXT:
                        key.label = "Next";
                        break;
                    case EditorInfo.IME_ACTION_SEARCH:
                        key.label = "Search";
                        break;
                    case EditorInfo.IME_ACTION_SEND:
                        key.label = "Send";
                        break;
                    case EditorInfo.IME_ACTION_DONE:
                        key.label = "Done";
                        break;
                    default:
                        key.label = "Enter"; 
                        break;
                }
                
                // Icon ကို ဖြုတ်လိုက်မှ စာသားပေါ်မှာပါ
                key.icon = null; 
                break; 
            }
        }
    }

    private void handleLiftToType(MotionEvent event) {
        if (!isLiftToType || currentKeys == null) {
            lastHoverKeyIndex = -1;
            return;
        }

        int action = event.getAction();
        float x = event.getX();
        float y = event.getY();

        if (y < 0) {
            cancelAllLongPress(); 
            lastHoverKeyIndex = -1; 
            return; 
        }

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                int newKeyIndex = getNearestKeyIndexFast((int) x, (int) y);
                
                if (newKeyIndex != -1 && newKeyIndex != lastHoverKeyIndex) {
                    cancelAllLongPress();
                    lastHoverKeyIndex = newKeyIndex;
                    playHaptic(HAPTIC_FOCUS); 
                    
                    Keyboard.Key key = currentKeys.get(newKeyIndex);
                    int code = key.codes[0];
                    
                    if (code == CODE_SPACE) {
                        // Space 3000ms
                        handler.postDelayed(spaceLongPressTask, 3000);
                    } else if (code == CODE_DELETE) {
                        // Delete 2000ms
                        handler.postDelayed(deleteStartTask, 2000);
                    }
                }
                break;

            case MotionEvent.ACTION_HOVER_EXIT:
                if (y < 0) {
                    cancelAllLongPress();
                    lastHoverKeyIndex = -1;
                    return;
                }

                if (!isLongPressHandled) {
                    if (lastHoverKeyIndex != -1 && lastHoverKeyIndex < currentKeys.size()) {
                        Keyboard.Key key = currentKeys.get(lastHoverKeyIndex);
                        if (key.codes[0] != -100) {
                            playHaptic(HAPTIC_TYPE);
                            handleInput(key.codes[0], key);
                        }
                    }
                }
                
                cancelAllLongPress(); 
                lastHoverKeyIndex = -1;
                break;
        }
    }
    
    private void cancelAllLongPress() {
        isLongPressHandled = false;
        isDeleteActive = false;
        handler.removeCallbacks(spaceLongPressTask);
        handler.removeCallbacks(deleteStartTask);
        handler.removeCallbacks(deleteLoopTask);
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
        playHaptic(HAPTIC_FOCUS);
    }

    @Override public void onRelease(int primaryCode) {
        cancelAllLongPress();
    }

    // --- MAIN INPUT HANDLING ---
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
                
                case CODE_SHIFT: 
                    isCaps = !isCaps; 
                    playHaptic(HAPTIC_TYPE); 
                    updateKeyboardLayout(); 
                    announceText(isCaps ? "Shift On" : "Shift Off"); 
                    break;
                    
                case CODE_SYMBOL_ON: 
                    isSymbols = true; 
                    playHaptic(HAPTIC_TYPE); 
                    updateKeyboardLayout(); 
                    announceText("Symbols Keyboard"); 
                    break;
                    
                case CODE_SYMBOL_OFF: 
                    isSymbols = false; 
                    playHaptic(HAPTIC_TYPE);
                    updateKeyboardLayout(); 
                    announceText(currentLanguageId == 1 ? "Myanmar" : (currentLanguageId == 2 ? "Shan" : "English")); 
                    break;
                    
                case CODE_LANG_CHANGE: changeLanguage(); break;
                
                case CODE_ENTER: 
                    playHaptic(HAPTIC_TYPE);
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
                    String wordToEcho = getWordFromProcessor();
                    ic.commitText(" ", 1);
                    saveWordAndReset();
                    
                    if (wordToEcho != null && !wordToEcho.isEmpty()) {
                        handler.postDelayed(() -> announceText(wordToEcho), 70);
                    }
                    break;
                    
                default:
                    String charStr = (key != null && key.label != null && key.label.length() > 1) 
                            ? key.label.toString() 
                            : String.valueOf((char) primaryCode);

                    if (primaryCode >= 48 && primaryCode <= 57) {
                        ic.commitText(charStr, 1);
                        return;
                    }

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
                            
                            if (!handled && textProcessor.isMedial(primaryCode)) {
                                 CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
                                 if (lastOne != null && lastOne.length() > 0) {
                                     char prevChar = lastOne.charAt(0);
                                     if (prevChar == MM_THWAY_HTOE || prevChar == SHAN_E) {
                                         ic.beginBatchEdit();
                                         ic.deleteSurroundingText(1, 0); 
                                         ic.commitText(charStr, 1);      
                                         ic.commitText(String.valueOf(prevChar), 1); 
                                         ic.endBatchEdit();
                                         handled = true;
                                     }
                                 }
                            }
                            
                            if (!handled && primaryCode == MM_I) {
                                CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
                                if (lastOne != null && lastOne.length() > 0) {
                                    char prevChar = lastOne.charAt(0);
                                    if (prevChar == (char)MM_U || prevChar == (char)MM_UU) {
                                        ic.beginBatchEdit();
                                        ic.deleteSurroundingText(1, 0);
                                        ic.commitText(charStr, 1); 
                                        ic.commitText(String.valueOf(prevChar), 1); 
                                        ic.endBatchEdit();
                                        handled = true;
                                    }
                                }
                            }
                            else if (!handled && primaryCode == MM_U) {
                                CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
                                if (lastOne != null && lastOne.length() > 0) {
                                    char prevChar = lastOne.charAt(0);
                                    if (prevChar == (char)MM_ANUSVARA) {
                                        ic.beginBatchEdit();
                                        ic.deleteSurroundingText(1, 0);
                                        ic.commitText(charStr, 1); 
                                        ic.commitText(String.valueOf(prevChar), 1); 
                                        ic.endBatchEdit();
                                        handled = true;
                                    }
                                }
                            }
                            
                            if (!handled) {
                                ic.commitText(charStr, 1);
                            }
                            currentWord.append(charStr);
                            
                            announceSyllableFromProcessor();
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

    private void announceSyllableFromProcessor() {
        if (accessibilityManager == null || !accessibilityManager.isEnabled()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        
        handler.postDelayed(() -> {
            CharSequence textBefore = ic.getTextBeforeCursor(15, 0);
            String syllable = textProcessor.getSyllableToSpeak(textBefore);
            if (syllable != null && !syllable.isEmpty()) {
                announceText(syllable);
            }
        }, 50);
    }

    private String getWordFromProcessor() {
        if (accessibilityManager == null || !accessibilityManager.isEnabled()) return null;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return null;
        CharSequence textBefore = ic.getTextBeforeCursor(50, 0);
        return textProcessor.getWordForEcho(textBefore);
    }

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
    
    private boolean isShanOrMyanmar() {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard;
    }

    private void saveWordAndReset() {
        if (suggestionDB != null && currentWord.length() > 0) {
            final String rawWord = currentWord.toString();
            final String normalizedWord = textProcessor.normalizeText(rawWord);
            dbExecutor.execute(() -> suggestionDB.saveWord(normalizedWord));
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
            if (keyboardView != null) {
                keyboardView.setKeyboard(currentKeyboard);
                currentKeys = currentKeyboard.getKeys();
                
                // *** Dynamic Enter Label Update ***
                updateEnterKeyLabel();
                
                keyboardView.invalidateAllKeys();
                updateHelperState();
            }
        } catch (Exception e) {
            currentKeyboard = qwertyKeyboard;
            if(keyboardView != null) {
                keyboardView.setKeyboard(currentKeyboard);
                currentKeys = currentKeyboard.getKeys();
            }
        }
    }

    private void changeLanguage() {
        lastHoverKeyIndex = -1;
        playHaptic(HAPTIC_TYPE); 
        String langName;
        if (currentLanguageId == 0) { currentLanguageId = 1; langName = "Myanmar"; } 
        else if (currentLanguageId == 1) { currentLanguageId = 2; langName = "Shan"; } 
        else { currentLanguageId = 0; langName = "English"; }
        
        announceText(langName);
        isCaps = false; isSymbols = false; 
        updateKeyboardLayout();
    }

    private int getNearestKeyIndexFast(int x, int y) {
        if (currentKeys == null || currentKeys.isEmpty()) return -1;
        if (lastHoverKeyIndex >= 0 && lastHoverKeyIndex < currentKeys.size()) {
            Keyboard.Key lastKey = currentKeys.get(lastHoverKeyIndex);
            if (lastKey.isInside(x, y)) {
                if (lastKey.codes[0] == -100) return -1;
                return lastHoverKeyIndex;
            }
        }
        for (int i = 0; i < currentKeys.size(); i++) {
            Keyboard.Key k = currentKeys.get(i);
            if (k.isInside(x, y)) {
                if (k.codes[0] == -100) return -1;
                return i;
            }
        }
        return -1;
    }

    private void playHaptic(int type) {
        if (!isVibrateOn) return;
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (type == HAPTIC_LONG_PRESS) {
                         v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
                    } else if (type == HAPTIC_TYPE) {
                         v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
                    } else {
                        v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
                    }
                } else {
                    int duration = (type == HAPTIC_LONG_PRESS) ? 50 : ((type == HAPTIC_TYPE) ? 30 : 10);
                    v.vibrate(duration);
                }
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
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) { speechRecognizer.destroy(); }
        if (dbExecutor != null && !dbExecutor.isShutdown()) { dbExecutor.shutdown(); }
        if (suggestionDB != null) { suggestionDB.close(); }
        
        if (isReceiverRegistered) {
            unregisterReceiver(userUnlockReceiver);
            isReceiverRegistered = false;
        }
        
        cancelAllLongPress();
        handler.removeCallbacks(pendingCandidateUpdate);
    }

    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}

