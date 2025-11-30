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

    // Helper Classes (Refactored Managers)
    private SaiNawFeedbackManager feedbackManager;
    private SaiNawLayoutManager layoutManager;
    private SaiNawTouchHandler touchHandler;
    
    // Logic Helpers
    private SaiNawAccessibilityHelper accessibilityHelper;
    private SaiNawTextProcessor textProcessor;
    private SuggestionDB suggestionDB;

    // UI Components
    private KeyboardView keyboardView;
    private LinearLayout candidateContainer;
    private List<TextView> candidateViews = new ArrayList<>();
    
    // State & Threading
    private StringBuilder currentWord = new StringBuilder();
    private boolean isReceiverRegistered = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    
    // Voice Input
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isListening = false;

    // Unicode Constants
    private static final int MM_THWAY_HTOE = 4145;
    private static final int SHAN_E = 4228;
    private static final char ZWSP = '\u200B';
    private static final int MM_I = 4141;
    private static final int MM_U = 4143;
    private static final int MM_UU = 4144;
    private static final int MM_ANUSVARA = 4150;

    // --- Listeners ---
    private final Runnable pendingCandidateUpdate = this::performCandidateSearch;
    
    private final View.OnClickListener candidateListener = v -> {
        String suggestion = (String) v.getTag();
        if (suggestion != null) pickSuggestion(suggestion);
    };

    // Direct Boot Receiver
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
        // 1. Initialize Managers
        feedbackManager = new SaiNawFeedbackManager(this);
        layoutManager = new SaiNawLayoutManager(this);
        touchHandler = new SaiNawTouchHandler(this, layoutManager, feedbackManager);
        textProcessor = new SaiNawTextProcessor();

        // 2. Load Settings safely
        Context safeContext = getSafeContext();
        SharedPreferences prefs = safeContext.getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        
        feedbackManager.loadSettings(prefs);
        touchHandler.loadSettings(prefs);
        layoutManager.initKeyboards(prefs); // Initial load

        // 3. Setup UI
        boolean isDarkTheme = prefs.getBoolean("dark_theme", false);
        View layout = getLayoutInflater().inflate(isDarkTheme ? R.layout.input_view_dark : R.layout.input_view, null);
        keyboardView = layout.findViewById(R.id.keyboard_view);
        candidateContainer = layout.findViewById(R.id.candidates_container);
        initCandidateViews(isDarkTheme);

        // 4. Setup Database (Direct Boot Aware)
        if (isUserUnlocked()) {
            suggestionDB = SuggestionDB.getInstance(this);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(userUnlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            isReceiverRegistered = true;
        }

        // 5. Setup Listeners & Accessibility
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setOnTouchListener((v, event) -> false);
        
        accessibilityHelper = new SaiNawAccessibilityHelper(keyboardView, this::handleInput);
        ViewCompat.setAccessibilityDelegate(keyboardView, accessibilityHelper);
        
        // Lift-to-Type Logic attached here
        keyboardView.setOnHoverListener((v, event) -> {
            touchHandler.handleHover(event);
            return accessibilityHelper.dispatchHoverEvent(event);
        });

        setupSpeechRecognizer();
        return layout;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        
        // Reload settings every time input starts (to catch changes made in Settings Activity)
        SharedPreferences prefs = getSafeContext().getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        feedbackManager.loadSettings(prefs);
        touchHandler.loadSettings(prefs);
        layoutManager.initKeyboards(prefs); // Reloads language enablement
        
        currentWord.setLength(0);
        layoutManager.updateEditorInfo(info);
        layoutManager.determineKeyboardForInputType();
        triggerCandidateUpdate(0);
    }

    // --- Main Input Handler ---
    public void handleInput(int primaryCode, Keyboard.Key key) {
        feedbackManager.playSound(primaryCode);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        // 1. Handle Key with Text Label (e.g., dedicated text keys)
        if (key != null && key.text != null) {
            ic.commitText(key.text, 1);
            // Reset sticky shift
            if (layoutManager.isCaps && !layoutManager.isCapsLocked) {
                layoutManager.isCaps = false;
                layoutManager.updateKeyboardLayout();
                announceText("Shift Off");
            }
            return;
        }

        try {
            switch (primaryCode) {
                case -5: // DELETE
                    CharSequence beforeDel = ic.getTextBeforeCursor(1, 0);
                    // Smart Delete for ZWSP
                    if (beforeDel != null && beforeDel.length() == 1 && beforeDel.charAt(0) == ZWSP) {
                        ic.deleteSurroundingText(1, 0);
                    }
                    CharSequence textBefore = ic.getTextBeforeCursor(1, 0);
                    ic.deleteSurroundingText(1, 0);
                    
                    if (textBefore != null && textBefore.length() > 0) announceText("Deleted " + textBefore);
                    else announceText("Delete");
                    
                    if (currentWord.length() > 0) {
                        currentWord.deleteCharAt(currentWord.length() - 1);
                        triggerCandidateUpdate(50);
                    }
                    break;

                case -10: // VOICE
                    startVoiceInput(); 
                    break; 

                case -1: // SHIFT
                    if (layoutManager.isCapsLocked) {
                        layoutManager.isCapsLocked = false;
                        layoutManager.isCaps = false;
                        announceText("Shift Off");
                    } else {
                        layoutManager.isCaps = !layoutManager.isCaps;
                        announceText(layoutManager.isCaps ? "Shift On" : "Shift Off");
                    }
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    break;

                case -2: // SYMBOL ON
                    layoutManager.isSymbols = true;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    announceText("Symbols Keyboard");
                    break;

                case -6: // SYMBOL OFF
                    layoutManager.isSymbols = false;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    announceText(layoutManager.currentLanguageId == 1 ? "Myanmar" : (layoutManager.currentLanguageId == 2 ? "Shan" : "English"));
                    break;

                case -101: // LANG CHANGE
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.changeLanguage();
                    touchHandler.reset(); // Reset hover state
                    announceText(layoutManager.currentLanguageId == 1 ? "Myanmar" : (layoutManager.currentLanguageId == 2 ? "Shan" : "English"));
                    break;

                case -4: // ENTER
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
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

                case 32: // SPACE
                    String wordToEcho = getWordFromProcessor();
                    ic.commitText(" ", 1);
                    saveWordAndReset();
                    if (wordToEcho != null && !wordToEcho.isEmpty()) {
                        handler.postDelayed(() -> announceText(wordToEcho), 70);
                    }
                    break;

                default: // CHARACTER INPUT
                    processCharInput(primaryCode, key, ic);
                    // Reset sticky shift
                    if (layoutManager.isCaps && !layoutManager.isCapsLocked) {
                        layoutManager.isCaps = false;
                        layoutManager.updateKeyboardLayout();
                    }
                    triggerCandidateUpdate(200);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void processCharInput(int primaryCode, Keyboard.Key key, InputConnection ic) {
        String charStr = (key != null && key.label != null && key.label.length() > 1) 
                ? key.label.toString() : String.valueOf((char) primaryCode);
        
        // Skip logic for numbers
        if (primaryCode >= 48 && primaryCode <= 57) {
            ic.commitText(charStr, 1);
            return;
        }

        if (layoutManager.isShanOrMyanmar()) {
            boolean handled = false;
            
            // 1. Thway Htoe / Shan E Reordering
            if (primaryCode == MM_THWAY_HTOE || primaryCode == SHAN_E) {
                ic.commitText(String.valueOf(ZWSP) + charStr, 1);
                currentWord.append(charStr);
                return;
            }
            
            // 2. Check existing Thway Htoe to reorder
            CharSequence lastTwo = ic.getTextBeforeCursor(2, 0);
            if (lastTwo != null && lastTwo.length() == 2) {
                if ((lastTwo.charAt(1) == MM_THWAY_HTOE || lastTwo.charAt(1) == SHAN_E) && lastTwo.charAt(0) == ZWSP) {
                    ic.beginBatchEdit();
                    ic.deleteSurroundingText(2, 0);
                    ic.commitText(charStr, 1);
                    ic.commitText(String.valueOf(lastTwo.charAt(1)), 1);
                    ic.endBatchEdit();
                    handled = true;
                }
            }
            
            // 3. Medial Logic
            if (!handled && textProcessor.isMedial(primaryCode)) {
                CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
                if (lastOne != null && lastOne.length() > 0 && (lastOne.charAt(0) == MM_THWAY_HTOE || lastOne.charAt(0) == SHAN_E)) {
                    ic.beginBatchEdit();
                    ic.deleteSurroundingText(1, 0);
                    ic.commitText(charStr, 1);
                    ic.commitText(String.valueOf(lastOne.charAt(0)), 1);
                    ic.endBatchEdit();
                    handled = true;
                }
            }
            
            // 4. Vowel Stacking Logic
            if (!handled) {
                CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
                if (lastOne != null && lastOne.length() > 0) {
                     char prev = lastOne.charAt(0);
                     if (primaryCode == MM_I && (prev == MM_U || prev == MM_UU)) handled = swapChars(ic, charStr, prev);
                     else if (primaryCode == MM_U && prev == MM_ANUSVARA) handled = swapChars(ic, charStr, prev);
                }
            }
            
            if (!handled) ic.commitText(charStr, 1);
            currentWord.append(charStr);
            announceSyllableFromProcessor();
        } else {
            // English/Other
            ic.commitText(charStr, 1);
            currentWord.append(charStr);
        }
    }

    private boolean swapChars(InputConnection ic, String current, char prev) {
        ic.beginBatchEdit();
        ic.deleteSurroundingText(1, 0);
        ic.commitText(current, 1);
        ic.commitText(String.valueOf(prev), 1);
        ic.endBatchEdit();
        return true;
    }

    // --- Accessors for Managers ---
    public KeyboardView getKeyboardView() { return keyboardView; }
    
    public void updateHelperState() { 
        if (accessibilityHelper != null) {
            accessibilityHelper.setKeyboard(layoutManager.getCurrentKeyboard(), layoutManager.isShanOrMyanmar(), layoutManager.isCaps); 
        }
    }
    
    public int getResId(String name) { return getResources().getIdentifier(name, "xml", getPackageName()); }

    public void announceText(String text) {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am != null && am.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            am.sendAccessibilityEvent(event);
        }
    }

    // --- Internal Helpers ---
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

    // --- Candidate & Database ---
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
    
    // --- Voice Input ---
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

    // --- Announcements ---
    private void announceSyllableFromProcessor() {
        if (accessibilityManager == null || !accessibilityManager.isEnabled()) return;
        handler.postDelayed(() -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                String s = textProcessor.getSyllableToSpeak(ic.getTextBeforeCursor(15, 0));
                if (s != null && !s.isEmpty()) announceText(s);
            }
        }, 50);
    }

    private String getWordFromProcessor() {
        if (accessibilityManager == null || !accessibilityManager.isEnabled()) return null;
        InputConnection ic = getCurrentInputConnection();
        return (ic != null) ? textProcessor.getWordForEcho(ic.getTextBeforeCursor(50, 0)) : null;
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
        // Accessibility manager init
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
    }
    
    // --- Abstract Overrides ---
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

