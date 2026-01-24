package com.sainaw.mm.board;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SaiNawKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    private SaiNawFeedbackManager feedbackManager;
    private SaiNawLayoutManager layoutManager;
    private SaiNawTouchHandler touchHandler;
    private SaiNawAccessibilityHelper accessibilityHelper;
    private SaiNawTextProcessor textProcessor;
    private SaiNawInputLogic inputLogic;
    private SaiNawSmartEcho smartEcho;
    private SaiNawPhoneticManager phoneticManager;
    private SaiNawEmojiManager emojiManager;
    private SuggestionDB suggestionDB;

    private KeyboardView keyboardView;
    private LinearLayout candidateContainer;
    private List<TextView> candidateViews = new ArrayList<>();
    private StringBuilder currentWord = new StringBuilder();
    private boolean isReceiverRegistered = false;
    private boolean useSmartEcho = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isListening = false;

    private static final int KEYCODE_EMOJI = -7;
    private static final int KEYCODE_ABC = -6;
    private static final char ZWSP = '\u200B';
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
        try {
            Context safeContext = getSafeContext();
            SharedPreferences prefs = safeContext.getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);

            feedbackManager = new SaiNawFeedbackManager(this);
            feedbackManager.loadSettings(prefs);

            layoutManager = new SaiNawLayoutManager(this);
            layoutManager.initKeyboards(prefs);

            textProcessor = new SaiNawTextProcessor();
            inputLogic = new SaiNawInputLogic(textProcessor, layoutManager);
            smartEcho = new SaiNawSmartEcho(this);
            
            phoneticManager = new SaiNawPhoneticManager(this);
            emojiManager = new SaiNawEmojiManager(this);
            
            touchHandler = new SaiNawTouchHandler(this, layoutManager, feedbackManager, emojiManager);
            touchHandler.loadSettings(prefs);

            boolean isDarkTheme = prefs.getBoolean("dark_theme", false);
            View layout = getLayoutInflater().inflate(isDarkTheme ? R.layout.input_view_dark : R.layout.input_view, null);
            
            keyboardView = layout.findViewById(R.id.keyboard_view);
            candidateContainer = layout.findViewById(R.id.candidates_container);
            if (candidateContainer != null) initCandidateViews(isDarkTheme);

            if (isUserUnlocked()) {
                suggestionDB = SuggestionDB.getInstance(this);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                registerReceiver(userUnlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
                isReceiverRegistered = true;
            }

            if (keyboardView != null) {
                keyboardView.setOnKeyboardActionListener(this);
                accessibilityHelper = new SaiNawAccessibilityHelper(keyboardView, this::handleInput, phoneticManager);
                ViewCompat.setAccessibilityDelegate(keyboardView, accessibilityHelper);
                
                keyboardView.setOnHoverListener((v, event) -> {
                    if (touchHandler != null) touchHandler.handleHover(event);
                    return (accessibilityHelper != null) ? accessibilityHelper.dispatchHoverEvent(event) : false;
                });
            }

            setupSpeechRecognizer();
            return layout;
        } catch (Exception e) { return null; }
    }

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
        
        if (layoutManager != null) {
            layoutManager.initKeyboards(prefs);
            layoutManager.updateEditorInfo(info);
            layoutManager.determineKeyboardForInputType();
        }

        useSmartEcho = prefs.getBoolean("smart_echo", false);
        boolean usePhonetic = prefs.getBoolean("use_phonetic_sounds", true);
        
        if (accessibilityHelper != null) {
            accessibilityHelper.setPhoneticEnabled(usePhonetic);
        }
        
        if (phoneticManager != null && layoutManager != null) {
            phoneticManager.setLanguageId(layoutManager.currentLanguageId);
        }

        if (touchHandler != null) touchHandler.loadSettings(prefs);
        if (feedbackManager != null) feedbackManager.loadSettings(prefs);
        
        currentWord.setLength(0);
        triggerCandidateUpdate(0);
    }

    public void handleInput(int primaryCode, Keyboard.Key key) {
        if (feedbackManager != null) {
            feedbackManager.playSound(primaryCode);
            feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        try {
            switch (primaryCode) {
                case -5: handleDelete(ic); return;
                case -1: handleShift(); return;
                case -101: handleLanguageSwitch(); return;
                case -4: handleEnter(ic); return;
                case 32: handleSpace(ic); return;
                case -10: startVoiceInput(); return;
                case KEYCODE_ABC:
                case KEYCODE_EMOJI:
                case -2: handleKeyboardModeChange(primaryCode); return;
            }

            if (primaryCode > 0) {
                String charStr = (key != null && key.label != null) ? key.label.toString() : String.valueOf((char) primaryCode);
                
                if (layoutManager.isShanOrMyanmar() && !layoutManager.isEmoji) {
                    inputLogic.processInput(ic, primaryCode, key);
                } else {
                    ic.commitText(charStr, 1);
                }

                if (useSmartEcho && smartEcho != null) {
                    smartEcho.onCharTyped(ic, charStr);
                }

                if (layoutManager.isCaps && !layoutManager.isCapsLocked) {
                    layoutManager.isCaps = false;
                    layoutManager.updateKeyboardLayout();
                    updateHelperState();
                }
                
                if (layoutManager.isShanOrMyanmar()) {
                    currentWord.append(charStr);
                    triggerCandidateUpdate(200);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void handleDelete(InputConnection ic) {
        CharSequence beforeDel = ic.getTextBeforeCursor(1, 0);
        if (beforeDel != null && beforeDel.length() == 1 && beforeDel.charAt(0) == ZWSP) ic.deleteSurroundingText(1, 0);
        ic.deleteSurroundingText(1, 0);
        if (useSmartEcho && smartEcho != null) smartEcho.announceText("Delete");
        if (currentWord.length() > 0) {
            currentWord.deleteCharAt(currentWord.length() - 1);
            triggerCandidateUpdate(50);
        }
    }

    private void handleShift() {
        layoutManager.isCaps = !layoutManager.isCaps;
        layoutManager.updateKeyboardLayout();
        if (smartEcho != null) smartEcho.announceText(layoutManager.isCaps ? "Shift On" : "Shift Off");
        updateHelperState();
    }

    private void handleLanguageSwitch() {
        layoutManager.changeLanguage();
        if (phoneticManager != null) {
            phoneticManager.setLanguageId(layoutManager.currentLanguageId);
        }
        if (smartEcho != null) smartEcho.announceText(layoutManager.currentLanguageId == 1 ? "Myanmar" : "Shan");
        updateHelperState();
    }

    private void handleEnter(InputConnection ic) {
        ic.commitText("\n", 1);
        if (useSmartEcho && smartEcho != null) smartEcho.onWordFinished(ic);
        saveWordAndReset();
    }

    private void handleSpace(InputConnection ic) {
        ic.commitText(" ", 1);
        if (useSmartEcho && smartEcho != null) smartEcho.onWordFinished(ic); 
        saveWordAndReset();
    }

    private void handleKeyboardModeChange(int code) {
        if (code == KEYCODE_EMOJI) { layoutManager.isEmoji = true; layoutManager.isSymbols = false; }
        else if (code == -2) { layoutManager.isSymbols = true; layoutManager.isEmoji = false; }
        else { layoutManager.isSymbols = false; layoutManager.isEmoji = false; }
        layoutManager.updateKeyboardLayout();
        updateHelperState();
    }

    public void showInputMethodPicker() {
        InputMethodManager imeManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imeManager != null) imeManager.showInputMethodPicker();
    }

    public KeyboardView getKeyboardView() { return keyboardView; }
    public int getResId(String name) { return getResources().getIdentifier(name, "xml", getPackageName()); }
    public void announceText(String text) { if (smartEcho != null) smartEcho.announceText(text); }

    private void saveWordAndReset() {
        if (suggestionDB != null && currentWord.length() > 0) {
            final String normalized = textProcessor.normalizeText(currentWord.toString());
            dbExecutor.execute(() -> suggestionDB.saveWord(normalized));
        }
        currentWord.setLength(0);
        triggerCandidateUpdate(0);
    }

    private void performCandidateSearch() {
        if (suggestionDB == null || currentWord.length() == 0) {
            handler.post(() -> { for (TextView tv : candidateViews) tv.setVisibility(View.GONE); });
            return;
        }
        final String searchKey = textProcessor.normalizeText(currentWord.toString());
        dbExecutor.execute(() -> {
            final List<String> suggestions = suggestionDB.getSuggestions(searchKey);
            handler.post(() -> {
                for (int i = 0; i < candidateViews.size(); i++) {
                    TextView tv = candidateViews.get(i);
                    if (i < suggestions.size()) {
                        tv.setText(suggestions.get(i)); tv.setTag(suggestions.get(i)); tv.setVisibility(View.VISIBLE);
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
        currentWord.setLength(0);
        triggerCandidateUpdate(0);
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

    public void updateHelperState() { 
        if (accessibilityHelper != null) accessibilityHelper.setKeyboard(layoutManager.getCurrentKeyboard(), layoutManager.isShanOrMyanmar(), layoutManager.isCaps); 
    }

    private void setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "my-MM");
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle p) {}
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float r) {}
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() { isListening = false; }
                public void onError(int e) { isListening = false; }
                public void onResults(Bundle r) {
                    ArrayList<String> m = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (m != null && !m.isEmpty()) getCurrentInputConnection().commitText(m.get(0) + " ", 1);
                    isListening = false;
                }
                public void onPartialResults(Bundle p) {}
                public void onEvent(int t, Bundle p) {}
            });
        }
    }

    private void startVoiceInput() {
        if (speechRecognizer == null || isListening) return;
        try { speechRecognizer.startListening(speechIntent); isListening = true; } catch (Exception e) {}
    }

    private void initCandidateViews(boolean isDarkTheme) {
        candidateContainer.removeAllViews(); candidateViews.clear();
        int textColor = isDarkTheme ? Color.WHITE : Color.BLACK;
        for (int i=0; i<3; i++) {
            TextView tv = new TextView(this);
            tv.setTextColor(textColor); tv.setTextSize(18); tv.setPadding(30,10,30,10); tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(android.R.drawable.btn_default); tv.setVisibility(View.GONE);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            candidateContainer.addView(tv, p); candidateViews.add(tv);
        }
    }

    @Override public void onKey(int p, int[] k) { handleInput(p, null); }
    @Override public void onText(CharSequence t) { getCurrentInputConnection().commitText(t, 1); }
    @Override public void onPress(int p) {} @Override public void onRelease(int p) {}
    @Override public void swipeLeft() {} @Override public void swipeRight() {} @Override public void swipeDown() {} @Override public void swipeUp() {}
    @Override public void onDestroy() { 
        super.onDestroy(); 
        if(speechRecognizer!=null) speechRecognizer.destroy(); 
        if(dbExecutor != null) dbExecutor.shutdown();
        if(suggestionDB!=null) suggestionDB.close();
        if(isReceiverRegistered) unregisterReceiver(userUnlockReceiver);
    }
}

