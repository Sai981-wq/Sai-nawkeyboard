package com.sainaw.mm.board;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
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
    
    // Components
    private KeyboardView keyboardView;
    private LinearLayout candidateContainer; // Assuming you have candidate logic setup in XML
    private SaiNawAccessibilityHelper accessibilityHelper;
    private SuggestionDB suggestionDB;
    private StringBuilder currentWord = new StringBuilder();

    // Keyboards
    private Keyboard qwertyKeyboard, qwertyShiftKeyboard;
    private Keyboard myanmarKeyboard, myanmarShiftKeyboard;
    private Keyboard shanKeyboard, shanShiftKeyboard;
    private Keyboard symbolsEnKeyboard, symbolsMmKeyboard; 
    private Keyboard currentKeyboard;

    // State
    private boolean isCaps = false;
    private boolean isSymbols = false; 
    private int currentLanguageId = 0; // 0=Eng, 1=MM, 2=Shan

    // Settings
    private boolean isVibrateOn = true;
    private boolean isSoundOn = true;
    private SharedPreferences prefs;
    private AudioManager audioManager;

    // Voice
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;

    // Threading
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    // Direct Boot Helper
    private Context getSafeContext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return isUserUnlocked() ? this : createDeviceProtectedStorageContext();
        }
        return this;
    }
    
    private boolean isUserUnlocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.os.UserManager um = (android.os.UserManager) getSystemService(Context.USER_SERVICE);
            return um != null && um.isUserUnlocked();
        }
        return true;
    }

    @Override
    public View onCreateInputView() {
        prefs = getSafeContext().getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        loadSettings();

        boolean isDarkTheme = prefs.getBoolean("dark_theme", false);
        int layoutRes = isDarkTheme ? R.layout.input_view_dark : R.layout.input_view;
        View layout = getLayoutInflater().inflate(layoutRes, null);

        keyboardView = layout.findViewById(R.id.keyboard_view);
        candidateContainer = layout.findViewById(R.id.candidates_container);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        
        // Initialize DB if unlocked
        if (isUserUnlocked()) {
            dbExecutor.execute(() -> suggestionDB = SuggestionDB.getInstance(SaiNawKeyboardService.this));
        }

        initKeyboards(); 
        currentLanguageId = 0;
        updateKeyboardLayout(); 
        
        keyboardView.setOnKeyboardActionListener(this);
        setupSpeechRecognizer();

        // Accessibility Delegate (Optional but good)
        accessibilityHelper = new SaiNawAccessibilityHelper(keyboardView, (primaryCode, key) -> handleInput(primaryCode, key));
        ViewCompat.setAccessibilityDelegate(keyboardView, accessibilityHelper);

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
            qwertyKeyboard = new Keyboard(this, R.xml.qwerty); // Fallback
        }
    }

    // --- MAIN INPUT HANDLING LOGIC ---

    @Override 
    public void onKey(int primaryCode, int[] keyCodes) { 
        handleInput(primaryCode, null); 
    }

    private void handleInput(int primaryCode, Keyboard.Key key) {
        playSound(primaryCode);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        // Handle Text from Key Label (if any specific override)
        if (key != null && key.text != null) {
            ic.commitText(key.text, 1);
            if (isCaps) { isCaps = false; updateKeyboardLayout(); }
            return;
        }

        switch (primaryCode) {
            case CODE_DELETE:
                handleDelete(ic);
                break;
                
            case CODE_SPACE:
                ic.commitText(" ", 1);
                saveWordAndReset();
                break;
                
            case CODE_ENTER:
                handleEnter(ic);
                break;

            case CODE_SHIFT:
                isCaps = !isCaps;
                updateKeyboardLayout();
                break;

            case CODE_SYMBOL_ON:
                isSymbols = true;
                updateKeyboardLayout();
                break;

            case CODE_SYMBOL_OFF:
                isSymbols = false;
                updateKeyboardLayout();
                break;

            case CODE_LANG_CHANGE:
                changeLanguage();
                break;

            case CODE_VOICE:
                startVoiceInput();
                break;

            default:
                // --- SMART REORDERING LOGIC STARTS HERE ---
                if (isShanOrMyanmar()) {
                    // Check if we need to swap 'ေ' + Consonant
                    boolean swapped = handleSmartReordering(ic, primaryCode);
                    
                    if (swapped) {
                         // Swap successful: Update internal word buffer for suggestions
                         if (currentWord.length() > 0) {
                            char last = currentWord.charAt(currentWord.length() - 1);
                            currentWord.deleteCharAt(currentWord.length() - 1); // remove 'ေ'
                            currentWord.append(String.valueOf((char) primaryCode)); // add Consonant
                            currentWord.append(last); // add 'ေ' back
                         }
                    } else {
                        // Normal input
                        String charStr = String.valueOf((char) primaryCode);
                        ic.commitText(charStr, 1);
                        currentWord.append(charStr);
                    }
                } else {
                    // English / Other
                    String charStr = String.valueOf((char) primaryCode);
                    ic.commitText(charStr, 1);
                    currentWord.append(charStr);
                }

                // Reset shift after typing a char
                if (isCaps) { isCaps = false; updateKeyboardLayout(); }
                
                // Trigger suggestions (Implement your candidate update logic here)
                // triggerCandidateUpdate(); 
        }
    }

    // --- THE CORE FIX: SMART REORDERING ---
    
    private boolean handleSmartReordering(InputConnection ic, int primaryCode) {
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        if (before == null || before.length() == 0) return false;

        char prevChar = before.charAt(0);

        // RULE: If prev char is 'ေ' OR 'ႄ' AND current input is Consonant/Medial -> SWAP
        // This solves "ပြေပြေမပေ" because "မ" will swap with the second "ေ".
        if ((prevChar == MM_THWAY_HTOE || prevChar == SHAN_E) && 
            (isConsonant(primaryCode) || isMedial(primaryCode))) {
            
            ic.beginBatchEdit();
            ic.deleteSurroundingText(1, 0); // Delete 'ေ'
            ic.commitText(String.valueOf((char) primaryCode), 1); // Commit Consonant (e.g. မ)
            ic.commitText(String.valueOf(prevChar), 1); // Commit 'ေ' back
            ic.endBatchEdit();
            
            return true; // Indicate swap happened
        }
        
        // OPTIONAL: Standard Medial Sorting (e.g. 'ှ' before 'ြ')
        // if (getMedialWeight(primaryCode) < getMedialWeight(prevChar) ...) { ... }

        return false;
    }

    // --- Helper Methods for Unicode Ranges ---

    private boolean isConsonant(int code) {
        // Myanmar Consonants (Ka ... A) & Shan & Others
        return (code >= 0x1000 && code <= 0x1021) || // Ka to A
               (code == 0x1025) || (code == 0x1027) || // U, E (Independent Vowels acting as base)
               (code == 0x103F) || // Great Sa
               (code >= 0x1040 && code <= 0x1049) || // Digits (Sometimes typed after thway htoe in errors, safe to swap)
               (code >= 0x1075 && code <= 0x1081) || // Shan Consonants
               (code >= 0x108E && code <= 0x109D);   // Other extensions
    }

    private boolean isMedial(int code) {
        // Yapin, Yayit, Wasway, Hahtoe, Mon/Shan medials
        return (code >= 0x103B && code <= 0x103E) || // Standard Medials
               (code == 0x105E) || (code == 0x105F) || // Mon
               (code == 0x1082); // Shan Wa
    }

    private void handleDelete(InputConnection ic) {
        // Clean Delete: No ZWSP checks needed anymore
        ic.deleteSurroundingText(1, 0);
        if (currentWord.length() > 0) {
            currentWord.deleteCharAt(currentWord.length() - 1);
        }
    }

    private void handleEnter(InputConnection ic) {
        EditorInfo editorInfo = getCurrentInputEditorInfo();
        boolean isMultiLine = (editorInfo.inputType & EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        int action = editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        
        if (!isMultiLine && (action >= EditorInfo.IME_ACTION_GO && action <= EditorInfo.IME_ACTION_DONE)) {
            sendDefaultEditorAction(true);
        } else {
            ic.commitText("\n", 1);
        }
        saveWordAndReset();
    }

    // --- Standard Keyboard Util Methods ---

    private boolean isShanOrMyanmar() {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard;
    }

    private void updateKeyboardLayout() {
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
        }
    }

    private void changeLanguage() {
        if (currentLanguageId == 0) currentLanguageId = 1; // Eng -> MM
        else if (currentLanguageId == 1) currentLanguageId = 2; // MM -> Shan
        else currentLanguageId = 0; // Shan -> Eng
        
        isCaps = false; 
        isSymbols = false;
        updateKeyboardLayout();
    }
    
    private void saveWordAndReset() {
        if (suggestionDB != null && currentWord.length() > 0) {
            String word = currentWord.toString();
            dbExecutor.execute(() -> suggestionDB.saveWord(word));
        }
        currentWord.setLength(0);
    }

    private void setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "my-MM");
            
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onError(int error) {}
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) ic.commitText(matches.get(0) + " ", 1);
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
    }

    private void startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        }
        if (speechRecognizer != null) {
            handler.post(() -> speechRecognizer.startListening(speechIntent));
        }
    }

    private void playSound(int primaryCode) {
        if (!isSoundOn) return;
        int sound = AudioManager.FX_KEYPRESS_STANDARD;
        if (primaryCode == CODE_DELETE) sound = AudioManager.FX_KEYPRESS_DELETE;
        else if (primaryCode == CODE_ENTER) sound = AudioManager.FX_KEYPRESS_RETURN;
        else if (primaryCode == CODE_SPACE) sound = AudioManager.FX_KEYPRESS_SPACEBAR;
        audioManager.playSoundEffect(sound, 1.0f);
    }

    private void playHaptic() {
        if (!isVibrateOn) return;
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(40);
        }
    }

    // Standard Listeners
    @Override public void onPress(int primaryCode) { playHaptic(); }
    @Override public void onRelease(int primaryCode) {}
    @Override public void onText(CharSequence text) {}
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}
