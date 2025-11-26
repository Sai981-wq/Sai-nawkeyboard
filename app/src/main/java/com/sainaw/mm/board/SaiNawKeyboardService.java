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
    private static final int MM_THWAY_HTOE = 0x1031; // 'ေ'
    private static final int SHAN_E = 0x1084;        // 'ႄ'
    private static final int MM_I = 0x102D;          // 'ိ'
    private static final int MM_U = 0x102F;          // 'ု'
    private static final int MM_UU = 0x1030;         // 'ူ'

    // Components
    private KeyboardView keyboardView;
    private LinearLayout candidateContainer;
    private List<TextView> candidateViews = new ArrayList<>();
    private SaiNawAccessibilityHelper accessibilityHelper;
    private SuggestionDB suggestionDB;
    
    // --- NEW: Buffer for handling Reordering ---
    private StringBuilder mComposing = new StringBuilder(); 
    // Internal tracker for suggestions (keeps history of committed text + composing)
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

    // --- MAIN INPUT HANDLING (REWRITTEN) ---
    @Override public void onKey(int primaryCode, int[] keyCodes) { handleInput(primaryCode, null); }

    private void handleInput(int primaryCode, Keyboard.Key key) {
        playSound(primaryCode);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        // Special Strings (e.g. .com)
        if (key != null && key.text != null) {
            commitTyped(ic); // Commit buffer first
            ic.commitText(key.text, 1);
            if (isCaps) { isCaps = false; updateKeyboardLayout(); }
            return;
        }

        switch (primaryCode) {
            case CODE_DELETE:
                handleBackspace(ic);
                break;

            case CODE_SPACE:
                if (!isSpaceLongPressed) {
                    commitTyped(ic); // Commit buffer
                    ic.commitText(" ", 1);
                    saveWordAndReset(); // Save word for suggestion
                }
                isSpaceLongPressed = false;
                break;

            case CODE_ENTER:
                commitTyped(ic);
                ic.commitText("\n", 1);
                saveWordAndReset();
                break;
                
            case CODE_SHIFT: 
            case CODE_SYMBOL_ON:
            case CODE_SYMBOL_OFF:
            case CODE_LANG_CHANGE:
            case CODE_VOICE:
                commitTyped(ic); // Switch mode should finish current composition
                handleFunctionalKeys(primaryCode);
                break;

            default:
                if (isShanOrMyanmar()) {
                    handleMyanmarInput(ic, primaryCode);
                } else {
                    commitTyped(ic); // English doesn't need composing reordering
                    ic.commitText(String.valueOf((char) primaryCode), 1);
                    currentWord.append((char) primaryCode);
                }
                
                if (isCaps) { isCaps = false; updateKeyboardLayout(); }
                triggerCandidateUpdate(200);
        }
    }

    // --- CORE LOGIC: COMPOSING & REORDERING ---
    
    // 1. Adds char to buffer, reorders internally, then updates screen with setComposingText
    private void handleMyanmarInput(InputConnection ic, int primaryCode) {
        char c = (char) primaryCode;
        mComposing.append(c);
        
        int len = mComposing.length();
        
        // RULE 1: Swap 'ေ' (Thway Htoe) + Consonant
        // Buffer: "ေ" + "မ" -> Change to "မ" + "ေ"
        if (len >= 2) {
            char last = mComposing.charAt(len - 1);
            char prev = mComposing.charAt(len - 2);
            
            if ((prev == MM_THWAY_HTOE || prev == SHAN_E) && isConsonant(last)) {
                mComposing.setCharAt(len - 2, last);
                mComposing.setCharAt(len - 1, prev);
            }
        }

        // RULE 2: Handle 'ေ' + Consonant + Medial (e.g. ေ + ပ + ြ)
        // Previous step made it "ပေ" (Consonant + ေ). Now "ြ" arrives.
        // Buffer: "ပေ" + "ြ" -> We need "ပြေ" (Consonant + Medial + ေ)
        if (len >= 3) {
            char last = mComposing.charAt(len - 1); // Medial (e.g. ြ)
            char middle = mComposing.charAt(len - 2); // ေ
            // char first = mComposing.charAt(len - 3); // Consonant
            
            if (isMedial(last) && (middle == MM_THWAY_HTOE || middle == SHAN_E)) {
                // Swap 'ေ' and Medial
                mComposing.setCharAt(len - 2, last);
                mComposing.setCharAt(len - 1, middle);
            }
        }
        
        // RULE 3: Shan Vowel Normalization
        if (len >= 2) {
             char last = mComposing.charAt(len - 1);
             char prev = mComposing.charAt(len - 2);
             if (last == MM_I && (prev == MM_U || prev == MM_UU)) {
                 mComposing.setCharAt(len - 2, last);
                 mComposing.setCharAt(len - 1, prev);
             }
        }

        // Update the screen with the reordered buffer
        ic.setComposingText(mComposing, 1);
    }

    // 2. Commits the Composing text to the app permanently
    private void commitTyped(InputConnection ic) {
        if (mComposing.length() > 0) {
            ic.commitText(mComposing, 1);
            currentWord.append(mComposing); // Add to suggestion tracker
            mComposing.setLength(0); // Clear buffer
        }
    }

    // 3. Handles Backspace with Composing buffer
    private void handleBackspace(InputConnection ic) {
        if (mComposing.length() > 0) {
            mComposing.deleteCharAt(mComposing.length() - 1);
            if (mComposing.length() == 0) {
                ic.commitText("", 0); // Clear composing span
            } else {
                ic.setComposingText(mComposing, 1); // Update span
            }
        } else {
            ic.deleteSurroundingText(1, 0);
            if (currentWord.length() > 0) {
                currentWord.deleteCharAt(currentWord.length() - 1);
                triggerCandidateUpdate(50);
            }
        }
    }

    private void handleFunctionalKeys(int code) {
        switch (code) {
            case CODE_SHIFT: isCaps = !isCaps; updateKeyboardLayout(); announceText(isCaps ? "Shift On" : "Shift Off"); break;
            case CODE_SYMBOL_ON: isSymbols = true; updateKeyboardLayout(); announceText("Symbols"); break;
            case CODE_SYMBOL_OFF: isSymbols = false; updateKeyboardLayout(); announceText("Alphabet"); break;
            case CODE_LANG_CHANGE: changeLanguage(); break;
            case CODE_VOICE: startVoiceInput(); break;
        }
    }

    // --- LOGIC HELPERS ---
    private boolean isConsonant(int code) {
        return (code >= 0x1000 && code <= 0x1021) || // Ka to A
               (code == 0x1025) || (code == 0x1027) || // U, E
               (code == 0x103F) || // Great Sa
               (code >= 0x1075 && code <= 0x1081) || // Shan Consonants
               (code >= 0x108E && code <= 0x109D);   
    }

    private boolean isMedial(int code) {
        return (code >= 0x103B && code <= 0x103E) || // Yapin..Hahtoe
               (code == 0x105E) || (code == 0x105F) || // Mon
               (code == 0x1082); // Shan Wa
    }
    
    private boolean isShanOrMyanmar() {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard;
    }

    // --- UI/UX & OTHERS ---
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
            if (accessibilityHelper != null) accessibilityHelper.setKeyboard(currentKeyboard, isShanOrMyanmar(), isCaps);
        }
    }

    private void changeLanguage() {
        commitTyped(getCurrentInputConnection());
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
    
    private void triggerCandidateUpdate(long delay) {
        handler.removeCallbacks(pendingCandidateUpdate);
        handler.postDelayed(pendingCandidateUpdate, delay);
    }

    private Runnable pendingCandidateUpdate = this::performCandidateSearch;

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
            // Clear current composing text or committed text
            if (mComposing.length() > 0) ic.commitText("", 1);
            else ic.deleteSurroundingText(currentWord.length(), 0);
            
            ic.commitText(suggestion + " ", 1);
            mComposing.setLength(0); // Reset buffer
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
                        commitTyped(getCurrentInputConnection());
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

