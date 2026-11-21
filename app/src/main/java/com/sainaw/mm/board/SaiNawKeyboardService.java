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
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
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
    private SaiNawPhoneticManager phoneticManager;
    private SaiNawEmojiManager emojiManager;
    private SuggestionDB suggestionDB;
    private AccessibilityManager accessibilityManager;

    private KeyboardView keyboardView;
    private LinearLayout candidateContainer;
    private List<TextView> candidateViews = new ArrayList<>();

    private StringBuilder currentWord = new StringBuilder();
    private boolean isReceiverRegistered = false;
    private boolean useSmartEcho = false;
    private boolean autoCapsEnabled = true;
    private boolean manualShiftOverride = false;
    private boolean isCurrentDarkTheme = false;

    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isListening = false;
    private boolean isSpeechReady = false;
    private int speechSessionToken = 0;
    private Runnable speechWatchdog;
    private Runnable pendingAnnouncementTask;
    private String lastPartialTranscript = "";
    private TextToSpeech shanTts;
    private ShanVoiceInput shanVoiceInput;
    private int voiceLanguageId = -1;

    private String lastShanText = "";
    private long lastShanTime = 0;

    private static final int KEYCODE_EMOJI = -7;
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
        handler.removeCallbacks(pendingCandidateUpdate);
        if (pendingAnnouncementTask != null) {
            handler.removeCallbacks(pendingAnnouncementTask);
            pendingAnnouncementTask = null;
        }
        cancelSpeechWatchdog();
        if (shanVoiceInput != null) shanVoiceInput.destroy();
        if (shanTts != null) {
            try { shanTts.stop(); shanTts.shutdown(); } catch (Exception ignored) { }
        }

        feedbackManager = new SaiNawFeedbackManager(this);
        layoutManager = new SaiNawLayoutManager(this);
        emojiManager = new SaiNawEmojiManager(this);
        touchHandler = new SaiNawTouchHandler(this, layoutManager, feedbackManager, emojiManager);

        textProcessor = new SaiNawTextProcessor();
        inputLogic = new SaiNawInputLogic(textProcessor, layoutManager);
        phoneticManager = new SaiNawPhoneticManager(this);
        accessibilityManager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);

        shanTts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                shanTts.setAudioAttributes(attrs);
            }
        }, "com.shan.tts");

        Context safeContext = getSafeContext();
        SharedPreferences prefs = safeContext.getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);

        feedbackManager.loadSettings(prefs);
        touchHandler.loadSettings(prefs);
        layoutManager.initKeyboards(prefs);
        useSmartEcho = prefs.getBoolean("smart_echo", false);
        autoCapsEnabled = prefs.getBoolean("auto_caps", true);

        isCurrentDarkTheme = prefs.getBoolean("dark_theme", false);
        View layout = getLayoutInflater().inflate(isCurrentDarkTheme ? R.layout.input_view_dark : R.layout.input_view, null);
        keyboardView = layout.findViewById(R.id.keyboard_view);
        candidateContainer = layout.findViewById(R.id.candidates_container);
        initCandidateViews(isCurrentDarkTheme);
        shanVoiceInput = new ShanVoiceInput(this, (LinearLayout) layout, isCurrentDarkTheme);

        if (isUserUnlocked()) {
            dbExecutor.execute(() -> suggestionDB = SuggestionDB.getInstance(SaiNawKeyboardService.this));
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isReceiverRegistered) {
            registerReceiver(userUnlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            isReceiverRegistered = true;
        }

        keyboardView.setOnKeyboardActionListener(this);
        accessibilityHelper = new SaiNawAccessibilityHelper(keyboardView, new SaiNawAccessibilityHelper.OnAccessibilityKeyListener() {
            @Override
            public void onAccessibilityKeyClick(int primaryCode, Keyboard.Key key) {
                handleInput(primaryCode, key);
            }
            @Override
            public String onCursorMoveAndGetText(boolean isForward, int granularity) {
                return moveCursorNativelyAndGetText(isForward, granularity);
            }
            @Override
            public void onCustomAnnounce(String text) {
                announceText(text);
            }
        }, phoneticManager, emojiManager);

        boolean useMmPhonetic = prefs.getBoolean("use_phonetic_sounds", true);
        boolean useShanPhonetic = prefs.getBoolean("use_shan_phonetic_sounds", true);
        accessibilityHelper.setPhoneticEnabled(useMmPhonetic, useShanPhonetic);
        ViewCompat.setAccessibilityDelegate(keyboardView, accessibilityHelper);

        keyboardView.setOnHoverListener((v, event) -> handleKeyboardHover(event));

        return layout;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        if (!restarting) {
            if (shanVoiceInput != null) shanVoiceInput.cancel();
            destroyGoogleSpeechRecognizer(false);
        }
        super.onStartInput(attribute, restarting);
        if (layoutManager != null) {
            layoutManager.updateEditorInfo(attribute);
            if (!restarting) {
                layoutManager.determineKeyboardForInputType();
            }
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        SharedPreferences prefs = getSafeContext().getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        boolean newDarkTheme = prefs.getBoolean("dark_theme", false);
        if (newDarkTheme != isCurrentDarkTheme) {
            setInputView(onCreateInputView());
        }

        super.onStartInputView(info, restarting);

        if (layoutManager == null || feedbackManager == null || touchHandler == null) {
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE | InputConnection.CURSOR_UPDATE_MONITOR);
        }

        feedbackManager.loadSettings(prefs);
        touchHandler.loadSettings(prefs);
        layoutManager.initKeyboards(prefs);
        useSmartEcho = prefs.getBoolean("smart_echo", false);
        autoCapsEnabled = prefs.getBoolean("auto_caps", true);

        boolean useMmPhonetic = prefs.getBoolean("use_phonetic_sounds", true);
        boolean useShanPhonetic = prefs.getBoolean("use_shan_phonetic_sounds", true);

        if (accessibilityHelper != null) {
            accessibilityHelper.setPhoneticEnabled(useMmPhonetic, useShanPhonetic);
        }
        if (phoneticManager != null) {
            phoneticManager.setLanguageId(layoutManager.currentLanguageId);
        }

        if (!restarting) {
            layoutManager.isCaps = false;
            layoutManager.isCapsLocked = false;
            layoutManager.isSymbols = false;
            layoutManager.isEmoji = false;
            manualShiftOverride = false;
            currentWord.setLength(0);
            hideCandidatesImmediately();
        }

        layoutManager.updateEditorInfo(info);
        layoutManager.determineKeyboardForInputType();
        updateShiftState();
    }

    private void hideCandidatesImmediately() {
        handler.removeCallbacks(pendingCandidateUpdate);
        for (TextView tv : candidateViews) {
            if (tv.getVisibility() != View.INVISIBLE) {
                tv.setVisibility(View.INVISIBLE);
            }
            if (tv.getTag() != null) {
                tv.setText("");
                tv.setTag(null);
            }
            tv.setClickable(false);
            tv.setFocusable(false);
            tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    private boolean isNativeTalkBackText(String text) {
        if (text == null || text.trim().isEmpty()) return true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u1000' && c <= '\u109F') ||
                (c >= '\uAA60' && c <= '\uAA7F') ||
                (c >= '\uA9E0' && c <= '\uA9FF')) {
                return false;
            }
        }
        return true;
    }

    private void silenceTalkBack() {
        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            accessibilityManager.interrupt();
        }
    }

    int prepareShanVoiceCapture() {
        stopSpeech();
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager != null && manager.isEnabled()) {
            manager.interrupt();
            return 180;
        }
        return 0;
    }

    boolean handleKeyboardHover(android.view.MotionEvent event) {
        if (accessibilityHelper != null) accessibilityHelper.handleHoverEvent(event);
        if (touchHandler != null) touchHandler.handleHover(event);
        return true;
    }

    void onShanVoiceStateChanged() {
    }

    public void stopSpeech() {
        if (shanTts != null) {
            try { shanTts.stop(); } catch (Exception ignored) { }
        }
    }

    public void handleInput(int primaryCode, Keyboard.Key key) {
        if (shanTts != null) {
            try { shanTts.stop(); } catch (Exception ignored) { }
        }

        if (layoutManager == null || feedbackManager == null) return;

        if (primaryCode != -10) {
            feedbackManager.playSound(primaryCode);
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        if (key != null && key.text != null) {
            ic.commitText(key.text, 1);
            syncAndGetCurrentWord();
            triggerCandidateUpdate(200);
            if (!layoutManager.isSymbols && !layoutManager.isEmoji && layoutManager.isCaps && !layoutManager.isCapsLocked) {
                layoutManager.isCaps = false;
                layoutManager.updateKeyboardLayout();
            }
            manualShiftOverride = false;
            updateShiftState();
            return;
        }

        boolean useShanPhonetic = getSafeContext().getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE)
                .getBoolean("use_shan_phonetic_sounds", true);
        boolean isShanUI = (layoutManager.currentLanguageId == 2);

        boolean effectiveSmartEcho = useSmartEcho && !(isShanUI && useShanPhonetic);

        try {
            switch (primaryCode) {
                case -5:
                    manualShiftOverride = false;
                    CharSequence selectedText = ic.getSelectedText(0);
                    if (selectedText != null && selectedText.length() > 0) {
                        ic.commitText("", 1);
                        if (effectiveSmartEcho) announceText("Deleted selected text");
                    } else if (!textProcessor.handleCustomBackspace(ic)) {
                        CharSequence beforeDel = ic.getTextBeforeCursor(1, 0);
                        if (beforeDel != null && beforeDel.length() == 1 && beforeDel.charAt(0) == ZWSP) {
                            ic.deleteSurroundingText(1, 0);
                        }
                        CharSequence textBefore = ic.getTextBeforeCursor(1, 0);
                        ic.deleteSurroundingText(1, 0);
                        if (effectiveSmartEcho) {
                            if (textBefore != null && textBefore.length() > 0) announceText("Deleted " + textBefore);
                            else announceText("Delete");
                        }
                    } else {
                        if (effectiveSmartEcho) {
                            announceText("Deleted");
                        }
                    }

                    syncAndGetCurrentWord();
                    triggerCandidateUpdate(200);
                    break;

                case -10:
                    manualShiftOverride = false;
                    startVoiceInput();
                    break;

                case -1:
                    if (layoutManager.isSymbols) {
                        layoutManager.isCaps = !layoutManager.isCaps;
                        if (effectiveSmartEcho) announceText(layoutManager.isCaps ? "More Symbols" : "Symbols");
                    } else {
                        manualShiftOverride = true;
                        if (layoutManager.isCapsLocked) {
                            layoutManager.isCapsLocked = false;
                            layoutManager.isCaps = false;
                            if (effectiveSmartEcho) announceText("Shift Off");
                        } else {
                            layoutManager.isCaps = !layoutManager.isCaps;
                            if (effectiveSmartEcho) announceText(layoutManager.isCaps ? "Shift On" : "Shift Off");
                        }
                    }
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    break;

                case -2:
                    layoutManager.isSymbols = true;
                    layoutManager.isEmoji = false;
                    layoutManager.isCaps = false;
                    layoutManager.isCapsLocked = false;
                    manualShiftOverride = false;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    if (effectiveSmartEcho) announceText("Symbols");
                    break;

                case -6:
                    layoutManager.isSymbols = false;
                    layoutManager.isEmoji = false;
                    layoutManager.isCaps = false;
                    layoutManager.isCapsLocked = false;
                    manualShiftOverride = false;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    if (effectiveSmartEcho) announceText(layoutManager.currentLanguageId == 1 ? "Myanmar" : (layoutManager.currentLanguageId == 2 ? "Shan" : "English"));
                    break;

                case KEYCODE_EMOJI:
                    layoutManager.isEmoji = true;
                    layoutManager.isSymbols = false;
                    layoutManager.isCaps = false;
                    layoutManager.isCapsLocked = false;
                    layoutManager.currentEmojiPage = 1;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    break;

                case -102:
                    layoutManager.currentEmojiPage = 2;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    break;

                case -103:
                    layoutManager.currentEmojiPage = 1;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    break;

                case -104:
                    layoutManager.currentEmojiPage = 3;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    break;

                case -105:
                    layoutManager.currentEmojiPage = 4;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.updateKeyboardLayout();
                    break;

                case -101:
                    manualShiftOverride = false;
                    destroyGoogleSpeechRecognizer(false);
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.changeLanguage();
                    if (touchHandler != null) touchHandler.reset();
                    if (phoneticManager != null) phoneticManager.setLanguageId(layoutManager.currentLanguageId);
                    if (effectiveSmartEcho) announceText(layoutManager.currentLanguageId == 1 ? "Myanmar" : (layoutManager.currentLanguageId == 2 ? "Shan" : "English"));
                    break;

                case -4:
                    manualShiftOverride = false;
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    EditorInfo editorInfo = getCurrentInputEditorInfo();
                    boolean isMultiLine = editorInfo != null && (editorInfo.inputType & EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
                    if (editorInfo != null && !isMultiLine && (editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_NONE) {
                        sendDefaultEditorAction(true);
                    } else {
                        ic.commitText("\n", 1);
                    }
                    if (effectiveSmartEcho) announceText("Enter");
                    saveWordAndReset();

                    if (layoutManager.isSymbols || layoutManager.isEmoji) {
                        layoutManager.isSymbols = false;
                        layoutManager.isEmoji = false;
                        layoutManager.isCaps = false;
                        layoutManager.updateKeyboardLayout();
                    }
                    break;

                case 32:
                    manualShiftOverride = false;
                    ic.commitText(" ", 1);
                    if (effectiveSmartEcho) {
                        String lastWord = getLastWordForEcho();
                        if (lastWord != null && !lastWord.isEmpty()) announceText(lastWord);
                        else announceText("Space");
                    }
                    saveWordAndReset();

                    if (layoutManager.isSymbols || layoutManager.isEmoji) {
                        layoutManager.isSymbols = false;
                        layoutManager.isEmoji = false;
                        layoutManager.isCaps = false;
                        layoutManager.updateKeyboardLayout();
                    }
                    break;

                default:
                    manualShiftOverride = false;
                    if (!textProcessor.handleCustomInsert(ic, primaryCode)) {
                        inputLogic.processInput(ic, primaryCode, key);
                    }
                    syncAndGetCurrentWord();

                    if (effectiveSmartEcho) {
                        String accumulatingWord = getCurrentWordForEcho();
                        if (accumulatingWord != null && !accumulatingWord.isEmpty()) announceText(accumulatingWord);
                    }

                    if (!layoutManager.isSymbols && !layoutManager.isEmoji && layoutManager.isCaps && !layoutManager.isCapsLocked) {
                        layoutManager.isCaps = false;
                        layoutManager.updateKeyboardLayout();
                    }
                    triggerCandidateUpdate(200);
                    break;
            }
        } catch (Exception e) { e.printStackTrace(); }

        updateShiftState();
    }

    private String syncAndGetCurrentWord() {
        InputConnection ic = getCurrentInputConnection();
        currentWord.setLength(0);
        if (ic == null) return "";

        CharSequence before = ic.getTextBeforeCursor(32, 0);
        if (before == null || before.length() == 0) return "";

        String s = before.toString();
        int start = s.length();
        while (start > 0) {
            char c = s.charAt(start - 1);
            if (Character.isWhitespace(c) || c == '\u104A' || c == '\u104B') {
                break;
            }
            start--;
        }
        String word = s.substring(start);
        currentWord.append(word);
        return word;
    }

    private void updateShiftState() {
        InputConnection ic = getCurrentInputConnection();
        if (layoutManager == null || ic == null) return;
        if (layoutManager.currentLanguageId != 0 || layoutManager.isSymbols || layoutManager.isEmoji) return;
        if (layoutManager.isCapsLocked || manualShiftOverride) return;

        if (autoCapsEnabled) {
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null && ei.inputType != EditorInfo.TYPE_NULL) {
                int caps = ic.getCursorCapsMode(ei.inputType);
                boolean shouldBeCaps = (caps != 0);
                if (layoutManager.isCaps != shouldBeCaps) {
                    layoutManager.isCaps = shouldBeCaps;
                    layoutManager.updateKeyboardLayout();
                }
            }
        }
    }

    private String getCurrentWordForEcho() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return null;
        CharSequence text = ic.getTextBeforeCursor(100, 0);
        if (text == null || text.length() == 0) return null;
        String s = text.toString();
        int lastNewLine = s.lastIndexOf('\n');
        String currentLine = (lastNewLine != -1) ? s.substring(lastNewLine + 1) : s;
        if (currentLine.isEmpty()) return null;
        int lastSpaceIndex = currentLine.lastIndexOf(' ');
        return (lastSpaceIndex != -1) ? currentLine.substring(lastSpaceIndex + 1) : currentLine;
    }

    private String getLastWordForEcho() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return null;
        CharSequence text = ic.getTextBeforeCursor(100, 0);
        if (text == null || text.length() == 0) return null;
        String s = text.toString();
        int lastNewLine = s.lastIndexOf('\n');
        String currentLine = (lastNewLine != -1) ? s.substring(lastNewLine + 1) : s;
        String trimmed = currentLine.trim();
        if (trimmed.isEmpty()) return null;
        int lastSpaceIndex = trimmed.lastIndexOf(' ');
        return (lastSpaceIndex != -1) ? trimmed.substring(lastSpaceIndex + 1) : trimmed;
    }

    public KeyboardView getKeyboardView() { return keyboardView; }

    boolean commitShanTranscript(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (!canUseShanVoice() || ic == null || text == null || text.trim().isEmpty()) return false;
        ic.finishComposingText();
        boolean inserted = ic.commitText(text, 1);
        if (inserted) {
            currentWord.setLength(0);
            hideCandidatesImmediately();
            updateShiftState();
        }
        return inserted;
    }

    public void updateHelperState() {
        if (keyboardView instanceof SaiNawKeyboardView && layoutManager != null) {
            Keyboard current = layoutManager.getCurrentKeyboard();
            ((SaiNawKeyboardView) keyboardView).setPlainShanVoice(
                    current == layoutManager.shanKeyboard || current == layoutManager.shanShiftKeyboard);
        }
        if (accessibilityHelper != null && layoutManager != null) {
            accessibilityHelper.setKeyboard(layoutManager.getCurrentKeyboard(), layoutManager.currentLanguageId, layoutManager.isCaps);
        }
        if (shanVoiceInput != null && layoutManager != null) {
            if (voiceLanguageId != layoutManager.currentLanguageId) {
                shanVoiceInput.cancel();
            }
            voiceLanguageId = layoutManager.currentLanguageId;
            shanVoiceInput.setLanguageCode("shn");
            shanVoiceInput.setAvailable(canUseShanVoice());
        }
    }

    private boolean canUseShanVoice() {
        if (!isUserUnlocked() || layoutManager == null || layoutManager.currentLanguageId != 2
                || layoutManager.isSymbols || layoutManager.isEmoji) return false;
        EditorInfo info = getCurrentInputEditorInfo();
        if (info == null) return false;
        int kind = info.inputType & android.text.InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & android.text.InputType.TYPE_MASK_VARIATION;
        if (kind != android.text.InputType.TYPE_CLASS_TEXT) return false;
        return variation != android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            && variation != android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            && variation != android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    }

    public int getResId(String name) { return getResources().getIdentifier(name, "xml", getPackageName()); }

    public void announceText(String text) {
        if ((shanVoiceInput != null && shanVoiceInput.isCapturePending()) || isListening) return;
        boolean useShanPhonetic = getSafeContext().getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE)
                .getBoolean("use_shan_phonetic_sounds", true);

        if (layoutManager != null && layoutManager.currentLanguageId == 2 && useShanPhonetic && !isNativeTalkBackText(text)) {
            silenceTalkBack();
            handler.postDelayed(this::silenceTalkBack, 15);
            handler.postDelayed(this::silenceTalkBack, 40);

            long now = System.currentTimeMillis();
            if (!(text.equals(lastShanText) && (now - lastShanTime < 80))) {
                if (shanTts != null) {
                    Bundle params = new Bundle();
                    params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ACCESSIBILITY);
                    shanTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "shan_tts_utterance");
                }
                lastShanText = text;
                lastShanTime = now;
            }
            return;
        }

        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            if (pendingAnnouncementTask != null) {
                handler.removeCallbacks(pendingAnnouncementTask);
            }
            pendingAnnouncementTask = () -> {
                if ((shanVoiceInput != null && shanVoiceInput.isCapturePending()) || isListening) return;
                AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
                event.getText().add(text);
                accessibilityManager.sendAccessibilityEvent(event);
            };
            handler.postDelayed(pendingAnnouncementTask, 50);
        }
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
        if (currentWord.length() == 0) {
            hideCandidatesImmediately();
            return;
        }
        handler.postDelayed(pendingCandidateUpdate, delayMillis);
    }

    private void saveWordAndReset() {
        if (currentWord.length() > 0 && isUserUnlocked()) {
            final String raw = currentWord.toString();
            dbExecutor.execute(() -> {
                SuggestionDB db = suggestionDB;
                if (db == null) {
                    db = SuggestionDB.getInstance(SaiNawKeyboardService.this);
                    suggestionDB = db;
                }
                db.saveWord(textProcessor.normalizeText(raw));
            });
        }
        currentWord.setLength(0);
        hideCandidatesImmediately();
    }

    private void performCandidateSearch() {
        final String queryWord = currentWord.toString();
        if (queryWord.isEmpty() || !isUserUnlocked()) {
            hideCandidatesImmediately();
            return;
        }
        dbExecutor.execute(() -> {
            SuggestionDB db = suggestionDB;
            if (db == null) {
                db = SuggestionDB.getInstance(SaiNawKeyboardService.this);
                suggestionDB = db;
            }
            final List<String> suggestions = db.getSuggestions(queryWord);
            handler.post(() -> {
                if (!currentWord.toString().equals(queryWord)) return;
                for (int i = 0; i < candidateViews.size(); i++) {
                    TextView tv = candidateViews.get(i);
                    if (i < suggestions.size()) {
                        String word = suggestions.get(i);
                        if (!word.equals(tv.getTag())) {
                            tv.setText(word);
                            tv.setTag(word);
                        }
                        if (tv.getVisibility() != View.VISIBLE) {
                            tv.setVisibility(View.VISIBLE);
                        }
                        tv.setClickable(true);
                        tv.setFocusable(true);
                        tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    } else {
                        if (tv.getVisibility() != View.INVISIBLE) {
                            tv.setVisibility(View.INVISIBLE);
                        }
                        if (tv.getTag() != null) {
                            tv.setText("");
                            tv.setTag(null);
                        }
                        tv.setClickable(false);
                        tv.setFocusable(false);
                        tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    }
                }
            });
        });
    }

    private void pickSuggestion(String suggestion) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            String actualCurrentWord = syncAndGetCurrentWord();
            if (!actualCurrentWord.isEmpty()) {
                ic.deleteSurroundingText(actualCurrentWord.length(), 0);
            }
            ic.commitText(suggestion + " ", 1);
        }
        if (isUserUnlocked()) {
            dbExecutor.execute(() -> {
                SuggestionDB db = suggestionDB;
                if (db == null) {
                    db = SuggestionDB.getInstance(SaiNawKeyboardService.this);
                    suggestionDB = db;
                }
                db.saveWord(suggestion);
            });
        }
        currentWord.setLength(0);
        hideCandidatesImmediately();
    }

    private void cancelSpeechWatchdog() {
        if (speechWatchdog != null) {
            handler.removeCallbacks(speechWatchdog);
            speechWatchdog = null;
        }
    }

    private void safeDestroyRecognizer(SpeechRecognizer recognizer) {
        if (recognizer == null) return;
        try { recognizer.cancel(); } catch (Exception ignored) { }
        try { recognizer.destroy(); } catch (Exception ignored) { }
    }

    private void destroyGoogleSpeechRecognizer(boolean asyncCleanup) {
        speechSessionToken++;
        cancelSpeechWatchdog();
        isListening = false;
        isSpeechReady = false;
        lastPartialTranscript = "";

        final SpeechRecognizer old = speechRecognizer;
        speechRecognizer = null;
        if (old != null) {
            if (asyncCleanup) {
                handler.postDelayed(() -> safeDestroyRecognizer(old), 120);
            } else {
                safeDestroyRecognizer(old);
            }
        }
    }

    private void stopGoogleSpeechRecognizer() {
        cancelSpeechWatchdog();
        if (speechRecognizer != null && isListening && isSpeechReady) {
            try {
                speechRecognizer.stopListening();
            } catch (Exception ignored) {
                destroyGoogleSpeechRecognizer(true);
            }
        } else {
            destroyGoogleSpeechRecognizer(true);
        }
    }

    private Intent createSpeechIntentForCurrentLanguage() {
        boolean isMyanmar = (layoutManager != null && layoutManager.currentLanguageId == 1);
        String primaryLang = isMyanmar ? "my-MM" : "en-US";
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, primaryLang);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primaryLang);
        intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 750L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);
            intent.putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false);
        }
        return intent;
    }

    private String formatFinalTranscript(String rawText) {
        if (rawText == null) return "";
        String cleaned = rawText.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) return "";
        boolean isEnglish = (layoutManager != null && layoutManager.currentLanguageId == 0);
        if (isEnglish) {
            InputConnection ic = getCurrentInputConnection();
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ic != null && ei != null && ei.inputType != EditorInfo.TYPE_NULL) {
                int caps = ic.getCursorCapsMode(ei.inputType);
                if (caps != 0 && cleaned.length() > 0) {
                    cleaned = Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
                }
            }
        }
        return cleaned;
    }

    private String selectBestHypothesis(ArrayList<String> matches, float[] confidences) {
        if (matches == null || matches.isEmpty()) return "";
        int bestIndex = 0;
        if (confidences != null && confidences.length == matches.size()) {
            float bestScore = -1f;
            for (int i = 0; i < matches.size(); i++) {
                String candidate = matches.get(i);
                if (candidate != null && !candidate.trim().isEmpty() && confidences[i] > bestScore) {
                    bestScore = confidences[i];
                    bestIndex = i;
                }
            }
        }
        return matches.get(bestIndex);
    }

    private void commitRecognizedSpeech(String rawTranscript) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String formatted = formatFinalTranscript(rawTranscript);
        if (formatted.isEmpty()) {
            ic.finishComposingText();
            return;
        }
        String prefixSpace = "";
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        if (before != null && before.length() == 1) {
            char prev = before.charAt(0);
            boolean isEnglish = (layoutManager != null && layoutManager.currentLanguageId == 0);
            if (isEnglish && !Character.isWhitespace(prev) && prev != '(' && prev != '[' && prev != '"' && prev != '\'') {
                prefixSpace = " ";
            }
        }
        ic.commitText(prefixSpace + formatted + " ", 1);
        currentWord.setLength(0);
        hideCandidatesImmediately();
        updateShiftState();
    }

    private SpeechRecognizer createFreshSpeechRecognizer(final int sessionToken) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return null;
        SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle p) {
                if (sessionToken != speechSessionToken) return;
                cancelSpeechWatchdog();
                isSpeechReady = true;
                lastPartialTranscript = "";
                if (feedbackManager != null) feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
            }
            @Override
            public void onBeginningOfSpeech() { }
            @Override
            public void onRmsChanged(float r) { }
            @Override
            public void onBufferReceived(byte[] b) { }
            @Override
            public void onEndOfSpeech() {
                if (sessionToken != speechSessionToken) return;
                isListening = false;
            }
            @Override
            public void onError(int error) {
                if (sessionToken != speechSessionToken) return;
                boolean wasListening = isListening;
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    if (!lastPartialTranscript.isEmpty() && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)) {
                        commitRecognizedSpeech(lastPartialTranscript);
                    } else {
                        ic.finishComposingText();
                    }
                }
                if (wasListening && feedbackManager != null) {
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                }
                destroyGoogleSpeechRecognizer(true);
            }
            @Override
            public void onResults(Bundle r) {
                if (sessionToken != speechSessionToken) return;
                ArrayList<String> matches = r != null ? r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                float[] confidences = r != null ? r.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES) : null;
                String chosen = selectBestHypothesis(matches, confidences);
                if (chosen.trim().isEmpty() && !lastPartialTranscript.isEmpty()) {
                    chosen = lastPartialTranscript;
                }
                commitRecognizedSpeech(chosen);
                destroyGoogleSpeechRecognizer(true);
            }
            @Override
            public void onPartialResults(Bundle p) {
                if (sessionToken != speechSessionToken) return;
                InputConnection ic = getCurrentInputConnection();
                if (ic != null && isListening && p != null) {
                    ArrayList<String> partial = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (partial != null && !partial.isEmpty()) {
                        String liveText = partial.get(0).trim();
                        if (!liveText.isEmpty()) {
                            lastPartialTranscript = liveText;
                            ic.setComposingText(formatFinalTranscript(liveText), 1);
                        }
                    }
                }
            }
            @Override
            public void onEvent(int t, Bundle p) { }
        });
        return recognizer;
    }

    private void startVoiceInput() {
        if (layoutManager != null && layoutManager.currentLanguageId == 2) {
            if (canUseShanVoice() && shanVoiceInput != null) shanVoiceInput.toggle();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(this, ShanVoiceConsentActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        }
        if (isListening) {
            stopGoogleSpeechRecognizer();
            return;
        }

        destroyGoogleSpeechRecognizer(false);
        final int currentToken = ++speechSessionToken;
        int settleMillis = Math.max(150, prepareShanVoiceCapture());

        handler.postDelayed(() -> {
            if (currentToken != speechSessionToken) return;
            speechRecognizer = createFreshSpeechRecognizer(currentToken);
            if (speechRecognizer == null) return;
            speechIntent = createSpeechIntentForCurrentLanguage();
            try {
                lastPartialTranscript = "";
                isSpeechReady = false;
                isListening = true;
                speechRecognizer.startListening(speechIntent);

                speechWatchdog = () -> {
                    if (currentToken == speechSessionToken && isListening && !isSpeechReady) {
                        destroyGoogleSpeechRecognizer(true);
                    }
                };
                handler.postDelayed(speechWatchdog, 4500);
            } catch (Exception e) {
                destroyGoogleSpeechRecognizer(true);
            }
        }, settleMillis);
    }

    private void initCandidateViews(boolean isDarkTheme) {
        candidateContainer.removeAllViews();
        candidateViews.clear();
        candidateContainer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int textColor = isDarkTheme ? Color.WHITE : Color.BLACK;
        for (int i = 0; i < 3; i++) {
            TextView tv = new TextView(this);
            tv.setTextColor(textColor);
            tv.setTextSize(18);
            tv.setPadding(30, 10, 30, 10);
            tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(android.R.drawable.btn_default);
            tv.setVisibility(View.INVISIBLE);
            tv.setClickable(false);
            tv.setFocusable(false);
            tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tv.setOnClickListener(candidateListener);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            p.setMargins(5, 0, 5, 0);
            candidateContainer.addView(tv, p);
            candidateViews.add(tv);
        }
    }

    @Override public void onKey(int p, int[] k) {
        if (touchHandler != null && touchHandler.isLongPressHandled()) {
            return;
        }
        handleInput(p, null);
    }

    @Override public void onText(CharSequence t) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || t == null) return;
        ic.commitText(t, 1);
        syncAndGetCurrentWord();
        triggerCandidateUpdate(200);
        if (feedbackManager != null) feedbackManager.playSound(0);
        if (layoutManager != null && !layoutManager.isSymbols && !layoutManager.isEmoji && layoutManager.isCaps && !layoutManager.isCapsLocked) {
            layoutManager.isCaps = false;
            layoutManager.updateKeyboardLayout();
        }
        manualShiftOverride = false;
        updateShiftState();
    }

    @Override public void onPress(int p) {
        if (feedbackManager != null) feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_FOCUS);
        if (touchHandler != null) {
            touchHandler.handleStandardPress(p);
        }
    }

    @Override public void onRelease(int p) {
        if (touchHandler != null) {
            touchHandler.cancelAllLongPress();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isInputViewShown()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                String text = moveCursorNativelyAndGetText(false, AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER);
                if (text != null) announceText(text);
                else announceText("Start");
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                String text = moveCursorNativelyAndGetText(true, AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER);
                if (text != null) announceText(text);
                else announceText("End");
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isInputViewShown() && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void swipeLeft() {
        String text = moveCursorNativelyAndGetText(false, AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER);
        if (text != null) announceText(text);
        else announceText("Start");
    }

    @Override
    public void swipeRight() {
        String text = moveCursorNativelyAndGetText(true, AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER);
        if (text != null) announceText(text);
        else announceText("End");
    }

    public String moveCursorNativelyAndGetText(boolean isForward, int granularity) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return null;

        ExtractedTextRequest request = new ExtractedTextRequest();
        ExtractedText extractedText = ic.getExtractedText(request, 0);

        if (extractedText == null || extractedText.text == null) return null;

        int currentPos = extractedText.selectionStart;
        String text = extractedText.text.toString();
        int newPos = currentPos;
        String traversedText = null;

        if (granularity == AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD) {
            if (isForward) {
                newPos = text.indexOf(' ', currentPos);
                if (newPos == -1) newPos = text.length();
                else newPos++;
                if (newPos > currentPos) traversedText = text.substring(currentPos, newPos);
            } else {
                if (currentPos > 0) {
                    newPos = text.lastIndexOf(' ', currentPos - 2);
                    if (newPos == -1) newPos = 0;
                    else newPos++;
                    if (currentPos > newPos) traversedText = text.substring(newPos, currentPos);
                }
            }
        } else {
            if (isForward) {
                if (currentPos < text.length()) {
                    newPos = currentPos + 1;
                    traversedText = text.substring(currentPos, currentPos + 1);
                }
            } else {
                if (currentPos > 0) {
                    newPos = currentPos - 1;
                    traversedText = text.substring(currentPos - 1, currentPos);
                }
            }
        }

        if (newPos != currentPos) {
            ic.setSelection(newPos, newPos);
            return traversedText;
        }
        return null;
    }

    @Override public void swipeDown() {}
    @Override public void swipeUp() {}

    @Override public void onWindowHidden() {
        if (shanVoiceInput != null) shanVoiceInput.cancel();
        destroyGoogleSpeechRecognizer(false);
        super.onWindowHidden();
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        if (shanVoiceInput != null) shanVoiceInput.cancel();
        destroyGoogleSpeechRecognizer(false);
        super.onFinishInputView(finishingInput);
    }

    @Override public void onFinishInput() {
        if (shanVoiceInput != null) shanVoiceInput.cancel();
        destroyGoogleSpeechRecognizer(false);
        super.onFinishInput();
    }

    @Override public void onDestroy() {
        if (shanVoiceInput != null) shanVoiceInput.destroy();
        destroyGoogleSpeechRecognizer(false);
        super.onDestroy();
        if (shanTts != null) {
            try { shanTts.stop(); shanTts.shutdown(); } catch (Exception ignored) { }
        }
        if (dbExecutor != null && !dbExecutor.isShutdown()) dbExecutor.shutdown();
        if (isReceiverRegistered) {
            try { unregisterReceiver(userUnlockReceiver); } catch (Exception ignored) { }
            isReceiverRegistered = false;
        }
        if (touchHandler != null) {
            touchHandler.cancelAllLongPress();
        }
        handler.removeCallbacks(pendingCandidateUpdate);
        if (pendingAnnouncementTask != null) {
            handler.removeCallbacks(pendingAnnouncementTask);
            pendingAnnouncementTask = null;
        }
        cancelSpeechWatchdog();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
    }

    @Override
    public void onUpdateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo);
    }
}
