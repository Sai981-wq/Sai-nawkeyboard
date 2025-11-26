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
    private static final int MM_I = 4141;          // 'ိ'
    private static final int MM_U = 4144;          // 'ု'
    private static final int MM_UU = 4143;         // 'ူ'

    // Components
    private KeyboardView keyboardView;
    private LinearLayout candidateContainer;
    private List<TextView> candidateViews = new ArrayList<>();
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
    private int currentLanguageId = 0; 
    private int lastHoverKeyIndex = -1;
    
    // Services
    private AudioManager audioManager;
    private AccessibilityManager accessibilityManager;
    private SharedPreferences prefs;
    private boolean isVibrateOn = true;
    private boolean isSoundOn = true;

    // Threading & Voice
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isListening = false;

    private final View.OnClickListener candidateListener = v -> {
        String suggestion = (String) v.getTag();
        if (suggestion != null) pickSuggestion(suggestion);
    };

    private Runnable pendingCandidateUpdate = this::performCandidateSearch;
    private boolean isSpaceLongPressed = false;
    private Runnable spaceLongPressRunnable = () -> {
        isSpaceLongPressed = true;
        InputMethodManager imeManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imeManager != null) {
            imeManager.showInputMethodPicker();
            playHaptic(-99);
        }
    };

    // --- BOOT & INIT ---
    private boolean isUserUnlocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
            return um != null && um.isUserUnlocked();
        }
        return true;
    }

    private Context getSafeContext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return isUserUnlocked() ? this : createDeviceProtectedStorageContext();
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                registerReceiver(userUnlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            }
        }

        initKeyboards(); 
        currentLanguageId = 0;
        updateKeyboardLayout(); 
        
        keyboardView.setOnKeyboardActionListener(this);
        setupSpeechRecognizer();

        // Screen Reader Helper
        accessibilityHelper = new SaiNawAccessibilityHelper(keyboardView, (primaryCode, key) -> handleInput(primaryCode, key));
        ViewCompat.setAccessibilityDelegate(keyboardView, accessibilityHelper);

        keyboardView.setOnHoverListener((v, event) -> {
            boolean handled = accessibilityHelper.dispatchHoverEvent(event);
            handleLiftToType(event);
            return handled;
        });
        
        return layout;
    }
    
    private void loadSettings() {
        isVibrateOn = prefs.getBoolean("vibrate_on", true);
        isSoundOn = prefs.getBoolean("sound_on", true);
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
            symbolsEnKeyboard = new Keyboard(this, getResId("symbols"));
            symbolsMmKeyboard = new Keyboard(this, getResId("symbols_mm"));
        } catch (Exception e) {
            qwertyKeyboard = new Keyboard(this, getResId("qwerty"));
        }
    }
    
    public int getResId(String name) {
        return getResources().getIdentifier(name, "xml", getPackageName());
    }

    // --- INPUT HANDLING ---
    @Override public void onKey(int primaryCode, int[] keyCodes) { handleInput(primaryCode, null); }

    private void handleInput(int primaryCode, Keyboard.Key key) {
        playSound(primaryCode);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        if (key != null && key.text != null) {
            ic.commitText(key.text, 1);
            if (isCaps) { isCaps = false; updateKeyboardLayout(); announceText("Shift Off"); }
            return;
        }

        switch (primaryCode) {
            case CODE_DELETE:
                CharSequence textBefore = ic.getTextBeforeCursor(1, 0);
                ic.deleteSurroundingText(1, 0);
                if (textBefore != null && textBefore.length() > 0) announceText("Deleted " + textBefore);
                else announceText("Delete");
                if (currentWord.length() > 0) {
                    currentWord.deleteCharAt(currentWord.length() - 1);
                    triggerCandidateUpdate(50);
                }
                break;

            case CODE_SPACE:
                if (!isSpaceLongPressed) {
                    ic.commitText(" ", 1);
                    saveWordAndReset();
                }
                isSpaceLongPressed = false;
                break;

            case CODE_ENTER:
                ic.commitText("\n", 1);
                saveWordAndReset();
                break;
                
            case CODE_SHIFT: isCaps = !isCaps; updateKeyboardLayout(); announceText(isCaps ? "Shift On" : "Shift Off"); break;
            case CODE_SYMBOL_ON: isSymbols = true; updateKeyboardLayout(); announceText("Symbols"); break;
            case CODE_SYMBOL_OFF: isSymbols = false; updateKeyboardLayout(); announceText("Alphabet"); break;
            case CODE_LANG_CHANGE: changeLanguage(); break;
            case CODE_VOICE: startVoiceInput(); break;

            default:
                if (isShanOrMyanmar()) {
                    // --- THE FIX IS HERE ---
                    boolean swapped = handleSmartReordering(ic, primaryCode);
                    
                    if (swapped) {
                         // Correct the internal word buffer after a swap
                         if (currentWord.length() > 0) {
                            char last = currentWord.charAt(currentWord.length() - 1);
                            currentWord.deleteCharAt(currentWord.length() - 1); // remove 'ေ'
                            currentWord.append(String.valueOf((char) primaryCode)); // add Consonant
                            currentWord.append(last); // add 'ေ' back
                         }
                    } 
                    else if (handleShanVowelNormalization(ic, primaryCode)) {
                         // Normalization logic
                         if (currentWord.length() > 0) {
                            char last = currentWord.charAt(currentWord.length() - 1);
                            currentWord.deleteCharAt(currentWord.length() - 1);
                            currentWord.append(String.valueOf((char) primaryCode));
                            currentWord.append(last);
                         }
                    }
                    else {
                        String charStr = String.valueOf((char) primaryCode);
                        ic.commitText(charStr, 1);
                        currentWord.append(charStr);
                    }
                } else {
                    String charStr = String.valueOf((char) primaryCode);
                    ic.commitText(charStr, 1);
                    currentWord.append(charStr);
                }
                
                if (isCaps) { isCaps = false; updateKeyboardLayout(); }
                triggerCandidateUpdate(200);
        }
    }

    // --- REORDERING LOGIC (UPDATED FOR "Ma Pyay" BUG) ---
    
    private boolean handleSmartReordering(InputConnection ic, int primaryCode) {
        // Need to check up to 2 characters back to understand context
        CharSequence before = ic.getTextBeforeCursor(2, 0);
        if (before == null || before.length() == 0) return false;

        char prevChar;
        char prevPrevChar = 0; // Null char
        
        if (before.length() == 1) {
            prevChar = before.charAt(0);
        } else {
            // getTextBeforeCursor returns "AB" where B is closest to cursor (index 1)
            prevChar = before.charAt(1);
            prevPrevChar = before.charAt(0);
        }

        // --- RULE 1: Medial Swapping ---
        // Medials ALWAYS swap if they follow 'ေ'. 
        // e.g. "ေ" + "ြ" -> "ြ" + "ေ"
        if ((prevChar == MM_THWAY_HTOE || prevChar == SHAN_E) && isMedial(primaryCode)) {
            performSwap(ic, primaryCode, prevChar);
            return true;
        }

        // --- RULE 2: Consonant Swapping (With Context Check) ---
        // e.g. "ေ" + "ပ" -> "ပ" + "ေ"
        // BUT: If buffer is "ပြေ" (prev=ေ, prevPrev=ြ), and input is "မ", 
        // "ြ" is a Medial, so "ေ" belongs to "ပြ". "မ" is a new syllable. DO NOT SWAP.
        
        if ((prevChar == MM_THWAY_HTOE || prevChar == SHAN_E) && isConsonant(primaryCode)) {
            
            boolean isPrevThwayHtoeAttached = isConsonant(prevPrevChar) || isMedial(prevPrevChar);
            
            // Swap ONLY if the 'ေ' is NOT attached to a previous Consonant/Medial
            if (!isPrevThwayHtoeAttached) {
                performSwap(ic, primaryCode, prevChar);
                return true;
            }
        }
        
        // --- RULE 3: Medial Sorting (Standard) ---
        // e.g. 'ှ' before 'ြ'
        if (getMedialWeight(primaryCode) > 0 && getMedialWeight((int)prevChar) > 0) {
            if (getMedialWeight((int)prevChar) > getMedialWeight(primaryCode)) {
                performSwap(ic, primaryCode, prevChar);
                return true;
            }
        }

        return false;
    }

    private void performSwap(InputConnection ic, int firstCode, int secondCode) {
        ic.beginBatchEdit();
        ic.deleteSurroundingText(1, 0); 
        ic.commitText(String.valueOf((char) firstCode), 1); 
        ic.commitText(String.valueOf((char) secondCode), 1);
        ic.endBatchEdit();
    }
    
    // Updated Helper Ranges
    private boolean isConsonant(int code) {
        return (code >= 0x1000 && code <= 0x1021) || // Ka to A
               (code == 0x1025) || (code == 0x1027) || // U, E
               (code == 0x103F) || // Great Sa
               (code >= 0x1075 && code <= 0x1081) || // Shan Consonants
               (code >= 0x108E && code <= 0x109D);   // Other extensions
               // Removed digits to prevent random swaps
    }

    private boolean isMedial(int code) {
        return (code >= 0x103B && code <= 0x103E) || // Yapin..Hahtoe
               (code == 0x105E) || (code == 0x105F) || // Mon
               (code == 0x1082); // Shan Wa
    }
    
    private int getMedialWeight(int code) {
        switch (code) {
            case 4155: return 1; // Yapin
            case 4156: return 2; // Yayit
            case 4157: return 3; // Wasway
            case 4226: return 3; // Shan Wa
            case 4158: return 4; // Hahtoe
            default: return 0;
        }
    }

    private boolean handleShanVowelNormalization(InputConnection ic, int primaryCode) {
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        if (before == null || before.length() == 0) return false;
        char prevChar = before.charAt(0);
        if ((primaryCode == MM_I && (prevChar == MM_U || prevChar == MM_UU))) {
            performSwap(ic, MM_I, prevChar);
            return true;
        }
        return false;
    }

    private void handleLiftToType(MotionEvent event) {
        try {
            int action = event.getAction();
            float x = event.getX();
            float y = event.getY();
            
            if (y < 20 || y > keyboardView.getHeight() - 20 || x < 20 || x > keyboardView.getWidth() - 20) {
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
                    if (lastHoverKeyIndex < keys.size()) {
                        Keyboard.Key key = keys.get(lastHoverKeyIndex);
                        if (key.codes[0] != -100) handleInput(key.codes[0], key);
                    }
                }
                lastHoverKeyIndex = -1;
            }
        } catch (Exception e) { lastHoverKeyIndex = -1; }
    }
    
    private int getNearestKeyIndexFast(int x, int y) {
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).isInside(x, y)) return i;
        }
        return -1;
    }

    private void announceText(String text) {
        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }
    
    private void initCandidateViews(boolean isDarkTheme) {
        candidateContainer.removeAllViews();
        candidateViews.clear();
        int textColor = isDarkTheme ? Color.WHITE : Color.BLACK;
        for (int i = 0; i < 3; i++) {
            TextView tv = new TextView(this);
            tv.setTextColor(textColor);
            tv.setTextSize(18);
            tv.setPadding(30, 10, 30, 10);
            tv.setGravity(Gravity.CENTER);
            tv.setVisibility(View.GONE); 
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            candidateContainer.addView(tv, params);
            candidateViews.add(tv);
        }
    }
    
    // --- UTILS ---
    private boolean isShanOrMyanmar() {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard;
    }

    private void updateKeyboardLayout() {
        Keyboard next;
        if (isSymbols) next = (currentLanguageId == 1) ? symbolsMmKeyboard : symbolsEnKeyboard;
        else if (currentLanguageId == 1) next = isCaps ? myanmarShiftKeyboard : myanmarKeyboard;
        else if (currentLanguageId == 2) next = isCaps ? shanShiftKeyboard : shanKeyboard;
        else next = isCaps ? qwertyShiftKeyboard : qwertyKeyboard;
        currentKeyboard = next;
        if (keyboardView != null) {
            keyboardView.setKeyboard(next);
            keyboardView.invalidateAllKeys();
            updateHelperState();
        }
    }
    
    private void updateHelperState() {
        if (accessibilityHelper != null) accessibilityHelper.setKeyboard(currentKeyboard, isShanOrMyanmar(), isCaps);
    }

    private void changeLanguage() {
        if (currentLanguageId == 0) { currentLanguageId = 1; announceText("Myanmar"); }
        else if (currentLanguageId == 1) { currentLanguageId = 2; announceText("Shan"); }
        else { currentLanguageId = 0; announceText("English"); }
        isCaps = false; isSymbols = false;
        updateKeyboardLayout();
    }
    
    private void saveWordAndReset() {
        if (suggestionDB != null && currentWord.length() > 0) {
            String w = currentWord.toString();
            dbExecutor.execute(() -> suggestionDB.saveWord(w));
        }
        currentWord.setLength(0);
        triggerCandidateUpdate(0);
    }
    
    private void performCandidateSearch() {
        if (suggestionDB == null || currentWord.length() == 0) {
            handler.post(() -> { for (TextView tv : candidateViews) tv.setVisibility(View.GONE); });
            return;
        }
        dbExecutor.execute(() -> {
            final List<String> suggestions = suggestionDB.getSuggestions(currentWord.toString());
            handler.post(() -> {
                for (int i = 0; i < candidateViews.size(); i++) {
                    TextView tv = candidateViews.get(i);
                    if (i < suggestions.size()) {
                        tv.setText(suggestions.get(i));
                        tv.setTag(suggestions.get(i));
                        tv.setVisibility(View.VISIBLE);
                        tv.setOnClickListener(candidateListener);
                    } else tv.setVisibility(View.GONE);
                }
            });
        });
    }
    
    private void pickSuggestion(String suggestion) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.deleteSurroundingText(currentWord.length(), 0);
            ic.commitText(suggestion + " ", 1);
        }
        saveWordAndReset();
    }

    private void setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "my-MM");
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onResults(Bundle results) {
                    ArrayList<String> m = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (m != null && !m.isEmpty() && getCurrentInputConnection() != null) {
                        getCurrentInputConnection().commitText(m.get(0) + " ", 1);
                    }
                    isListening = false;
                }
                @Override public void onReadyForSpeech(Bundle p) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float r) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() { isListening = false; }
                @Override public void onError(int e) { isListening = false; }
                @Override public void onPartialResults(Bundle p) {}
                @Override public void onEvent(int e, Bundle p) {}
            });
        }
    }

    private void startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        }
        if (speechRecognizer != null) { 
            handler.post(() -> speechRecognizer.startListening(speechIntent)); 
            isListening = true; 
        }
    }

    private void playSound(int code) {
        if (!isSoundOn) return;
        int s = AudioManager.FX_KEYPRESS_STANDARD;
        if (code == CODE_DELETE) s = AudioManager.FX_KEYPRESS_DELETE;
        else if (code == CODE_ENTER) s = AudioManager.FX_KEYPRESS_RETURN;
        else if (code == CODE_SPACE) s = AudioManager.FX_KEYPRESS_SPACEBAR;
        audioManager.playSoundEffect(s, 1.0f);
    }

    private void playHaptic(int code) {
        if (!isVibrateOn) return;
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(40);
        }
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
    @Override public void onText(CharSequence text) {}
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}
