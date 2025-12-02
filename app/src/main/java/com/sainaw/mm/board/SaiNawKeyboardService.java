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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SaiNawKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    // --- Logic & Managers ---
    private SaiNawFeedbackManager feedbackManager;
    private SaiNawLayoutManager layoutManager;
    private SaiNawTouchHandler touchHandler;
    private SaiNawAccessibilityHelper accessibilityHelper;
    private SaiNawTextProcessor textProcessor;
    private SaiNawInputLogic inputLogic;
    private SaiNawSmartEcho smartEcho;
    private SaiNawPhoneticManager phoneticManager;
    private SuggestionDB suggestionDB;

    // --- System & UI ---
    private AccessibilityManager accessibilityManager;
    private KeyboardView keyboardView;
    private LinearLayout candidateContainer;
    private List<TextView> candidateViews = new ArrayList<>();
    
    // --- State ---
    private StringBuilder currentWord = new StringBuilder();
    private boolean isReceiverRegistered = false;
    private boolean useSmartEcho = false; 
    
    // --- Threading ---
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isListening = false;

    // --- Constants ---
    private static final char ZWSP = '\u200B';

    // --- Listeners ---
    private final Runnable pendingCandidateUpdate = this::performCandidateSearch;
    private final View.OnClickListener candidateListener = v -> {
        String suggestion = (String) v.getTag();
        if (suggestion != null) pickSuggestion(suggestion);
    };

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
        // 1. Initialize Logic Managers
        feedbackManager = new SaiNawFeedbackManager(this);
        layoutManager = new SaiNawLayoutManager(this);
        textProcessor = new SaiNawTextProcessor();
        inputLogic = new SaiNawInputLogic(textProcessor, layoutManager);
        smartEcho = new SaiNawSmartEcho(this);
        phoneticManager = new SaiNawPhoneticManager(this);
        touchHandler = new SaiNawTouchHandler(this, layoutManager, feedbackManager);
        
        accessibilityManager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);

        // 2. Load Settings
        Context safeContext = getSafeContext();
        SharedPreferences prefs = safeContext.getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        
        feedbackManager.loadSettings(prefs);
        touchHandler.loadSettings(prefs);
        layoutManager.initKeyboards(prefs);
        
        useSmartEcho = prefs.getBoolean("smart_echo", false); 

        // 3. UI Setup
        boolean isDarkTheme = prefs.getBoolean("dark_theme", false);
        View layout = getLayoutInflater().inflate(isDarkTheme ? R.layout.input_view_dark : R.layout.input_view, null);
        keyboardView = layout.findViewById(R.id.keyboard_view);
        candidateContainer = layout.findViewById(R.id.candidates_container);
        initCandidateViews(isDarkTheme);

        // 4. Database
        if (isUserUnlocked()) {
            suggestionDB = SuggestionDB.getInstance(this);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(userUnlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            isReceiverRegistered = true;
        }

        // 5. Listeners & Accessibility
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setOnTouchListener((v, event) -> false);
        
        // Pass phoneticManager to AccessibilityHelper
        accessibilityHelper = new SaiNawAccessibilityHelper(keyboardView, this::handleInput, phoneticManager);
        
        // Apply Phonetic Switch Setting
        boolean usePhonetic = prefs.getBoolean("use_phonetic_sounds", true);
        accessibilityHelper.setPhoneticEnabled(usePhonetic);
        
        ViewCompat.setAccessibilityDelegate(keyboardView, accessibilityHelper);
        
        keyboardView.setOnHoverListener((v, event) -> {
            touchHandler.handleHover(event);
            return accessibilityHelper.dispatchHoverEvent(event);
        });

        setupSpeechRecognizer();
        return layout;
    }

    // Dynamic Enter Label Hook
    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        if (layoutManager != null) {
            layoutManager.updateEditorInfo(attribute);
            layoutManager.determineKeyboardForInputType();
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        
        SharedPreferences prefs = getSafeContext().getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        feedbackManager.loadSettings(prefs);
        touchHandler.loadSettings(prefs);
        layoutManager.initKeyboards(prefs); 
        
        // Update Smart Echo setting
        useSmartEcho = prefs.getBoolean("smart_echo", false); 
        
        // Update Phonetic setting
        boolean usePhonetic = prefs.getBoolean("use_phonetic_sounds", true);
        if (accessibilityHelper != null) {
            accessibilityHelper.setPhoneticEnabled(usePhonetic);
        }

        // *** IMPORTANT: Sync Language ID to Phonetic Manager ***
        if (phoneticManager != null) {
            phoneticManager.setLanguageId(layoutManager.currentLanguageId);
        }
        
        currentWord.setLength(0);
        layoutManager.updateEditorInfo(info);
        layoutManager.determineKeyboardForInputType();
        triggerCandidateUpdate(0);
    }

    // --- MAIN INPUT HANDLER ---
    public void handleInput(int primaryCode, Keyboard.Key key) {
        feedbackManager.playSound(primaryCode);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        if (key != null && key.text != null) {
            ic.commitText(key.text, 1);
            if (layoutManager.isCaps && !layoutManager.isCapsLocked) {
                layoutManager.isCaps = false;
                layoutManager.updateKeyboardLayout();
            }
            return;
        }

        try {
            switch (primaryCode) {
                case -5: // DELETE
                    CharSequence beforeDel = ic.getTextBeforeCursor(1, 0);
                    if (beforeDel != null && beforeDel.length() == 1 && beforeDel.charAt(0) == ZWSP) {
                        ic.deleteSurroundingText(1, 0);
                    }
                    ic.deleteSurroundingText(1, 0);
                    
                    // Smart Echo for Delete (Optional, as TalkBack handles this usually)
                    // If you want explicit feedback:
                    // if (useSmartEcho) smartEcho.announceText("Delete");
                    
                    if (currentWord.length() > 0) {
                        currentWord.deleteCharAt(currentWord.length() - 1);
                        triggerCandidateUpdate(50);
                    }
                    break;

                case -10: startVoiceInput(); break; 

                case -1: // SHIFT
                    if (layoutManager.isCapsLocked) {
                        layoutManager.isCapsLocked = false;
                        layoutManager.isCaps = false;
                        smartEcho.announceText("Shift Off");
                    } else {
                        layoutManager.isCaps = !layoutManager.isCaps;
                        smartEcho.announceText(layoutManager.isCaps ? "Shift On" : "Shift Off");
                    }
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    break;

                case -2: // SYMBOL ON
                    layoutManager.isSymbols = true;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    smartEcho.announceText("Symbols");
                    break;

                case -6: // SYMBOL OFF
                    layoutManager.isSymbols = false;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    smartEcho.announceText(layoutManager.currentLanguageId == 1 ? "Myanmar" : (layoutManager.currentLanguageId == 2 ? "Shan" : "English"));
                    break;

                case -101: // LANG CHANGE
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.changeLanguage();
                    touchHandler.reset(); 
                    
                    // *** Sync Language Change to Phonetic Manager ***
                    if (phoneticManager != null) {
                        phoneticManager.setLanguageId(layoutManager.currentLanguageId);
                    }

                    smartEcho.announceText(layoutManager.currentLanguageId == 1 ? "Myanmar" : (layoutManager.currentLanguageId == 2 ? "Shan" : "English"));
                    
                    // Refresh Accessibility Nodes to update descriptions
                    if (accessibilityHelper != null) {
                        accessibilityHelper.setKeyboard(layoutManager.getCurrentKeyboard(), layoutManager.isShanOrMyanmar(), layoutManager.isCaps);
                    }
                    break;

                case -4: // ENTER
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    EditorInfo editorInfo = getCurrentInputEditorInfo();
                    boolean isMultiLine = (editorInfo.inputType & EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
                    
                    if (!isMultiLine && (editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_NONE) {
                        sendDefaultEditorAction(true);
                    } else {
                        ic.commitText("\n", 1);
                    }
                    if (useSmartEcho) smartEcho.announceText("Enter");
                    saveWordAndReset();
                    break;

                case 32: // SPACE
                    ic.commitText(" ", 1);
                    
                    // Smart Echo: Read whole word after space (up to 2000 chars)
                    if (useSmartEcho) {
                        smartEcho.onSpaceTyped(ic);
                    }
                    saveWordAndReset();
                    break;

                default: // CHARACTER INPUT
                    // 1. Process Input via Logic Class
                    inputLogic.processInput(ic, primaryCode, key);
                    
                    // 2. Track current word for suggestions
                    String charStr = (key != null && key.label != null && key.label.length() > 1) 
                            ? key.label.toString() : String.valueOf((char) primaryCode);
                    currentWord.append(charStr);

                    // 3. Accumulative Smart Echo: Read accumulated text
                    if (useSmartEcho) {
                        smartEcho.onCharTyped(ic);
                    }

                    if (layoutManager.isCaps && !layoutManager.isCapsLocked) {
                        layoutManager.isCaps = false;
                        layoutManager.updateKeyboardLayout();
                    }
                    triggerCandidateUpdate(200);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // --- Helpers ---
    public KeyboardView getKeyboardView() { return keyboardView; }
    public void updateHelperState() { 
        if (accessibilityHelper != null) {
            accessibilityHelper.setKeyboard(layoutManager.getCurrentKeyboard(), layoutManager.isShanOrMyanmar(), layoutManager.isCaps); 
        }
    }
    public int getResId(String name) { return getResources().getIdentifier(name, "xml", getPackageName()); }

    // Expose for internal use
    public void announceText(String text) {
        smartEcho.announceText(text);
    }

    private Context getSafeContext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isUserUnlocked()) return createDeviceProtectedStorageContext();
        return this;
    }

    private boolean isUserUnlocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
            return um != null && um.isUserUnlocked();
        }
        return true;
    }

    private void triggerCandidateUpdate(long delayMillis) {
        handler.removeCallbacks(pendingCandidateUpdate);
        handler.postDelayed(pendingCandidateUpdate, delayMillis);
    }

    private void saveWordAndReset() {
        if (suggestionDB != null && currentWord.length() > 0) {
            final String raw = currentWord.toString();
            dbExecutor.execute(() -> suggestionDB.saveWord(textProcessor.normalizeText(raw)));
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
        if (suggestionDB != null) dbExecutor.execute(() -> suggestionDB.saveWord(suggestion));
        currentWord.setLength(0);
        triggerCandidateUpdate(0);
    }
    
    private void setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "my-MM");
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle p) { feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE); } 
                public void onBeginningOfSpeech() {} 
                public void onRmsChanged(float r) {} 
                public void onBufferReceived(byte[] b) {} 
                public void onEndOfSpeech() { isListening = false; }
                public void onError(int e) { isListening = false; feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE); } 
                public void onResults(Bundle r) { 
                    ArrayList<String> m = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (m != null && !m.isEmpty()) { getCurrentInputConnection().commitText(m.get(0) + " ", 1); }
                    isListening = false; 
                }
                public void onPartialResults(Bundle p) {} 
                public void onEvent(int t, Bundle p) {}
            });
        }
    }

    private void startVoiceInput() {
        if (speechRecognizer == null) return;
        if (isListening) {
            speechRecognizer.stopListening();
            isListening = false;
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent); 
            return;
        }
        handler.post(() -> {
            try { speechRecognizer.startListening(speechIntent); isListening = true; } catch (Exception e) {}
        });
    }

    private void initCandidateViews(boolean isDarkTheme) {
        candidateContainer.removeAllViews(); candidateViews.clear();
        int textColor = isDarkTheme ? Color.WHITE : Color.BLACK;
        for (int i=0; i<3; i++) {
            TextView tv = new TextView(this);
            tv.setTextColor(textColor);
            tv.setTextSize(18); tv.setPadding(30,10,30,10); tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(android.R.drawable.btn_default); tv.setVisibility(View.GONE);
            tv.setFocusable(true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            p.setMargins(5,0,5,0); candidateContainer.addView(tv, p); candidateViews.add(tv);
        }
    }
    
    @Override public void onKey(int p, int[] k) { handleInput(p, null); }
    @Override public void onText(CharSequence t) { 
        getCurrentInputConnection().commitText(t, 1); 
        feedbackManager.playSound(0);
        if (layoutManager.isCaps && !layoutManager.isCapsLocked) { 
            layoutManager.isCaps = false; layoutManager.updateKeyboardLayout(); 
        }
    }
    @Override public void onPress(int p) { feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_FOCUS); } 
    @Override public void onRelease(int p) { touchHandler.cancelAllLongPress(); }
    @Override public void swipeLeft() {} @Override public void swipeRight() {} @Override public void swipeDown() {} @Override public void swipeUp() {}
    
    @Override public void onDestroy() { 
        super.onDestroy(); 
        if(speechRecognizer!=null) speechRecognizer.destroy(); 
        if(dbExecutor != null && !dbExecutor.isShutdown()) dbExecutor.shutdown();
        if(suggestionDB!=null) suggestionDB.close();
        if(isReceiverRegistered) unregisterReceiver(userUnlockReceiver);
        touchHandler.cancelAllLongPress();
        handler.removeCallbacks(pendingCandidateUpdate);
    }
}

