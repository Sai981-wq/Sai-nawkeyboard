package com.sainaw.mm.board;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SaiNawKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    private static final int ERROR_SERVER_DISCONNECTED_COMPAT = 11;
    private static final long VOICE_READY_TIMEOUT_MS = 6_500L;
    private static final long VOICE_RESULT_TIMEOUT_MS = 8_000L;
    private static final long VOICE_RETRY_DELAY_MS = 450L;
    private static final long VOICE_COMPLETE_SILENCE_MS = 1_800L;
    private static final long VOICE_POSSIBLY_COMPLETE_SILENCE_MS = 1_500L;

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

    private static final int SHADOW_MAX_LEN = 128;
    private final StringBuilder shadowLine = new StringBuilder(SHADOW_MAX_LEN);
    private final StringBuilder currentWord = new StringBuilder(64);
    private boolean shadowNeedsResync = true;
    private boolean hasTextSelection = false;

    private boolean isReceiverRegistered = false;
    private boolean useSmartEcho = false;
    private boolean autoCapsEnabled = true;
    private boolean useMmPhonetic = true;
    private boolean useShanPhonetic = true;
    private boolean manualShiftOverride = false;
    private boolean isCurrentDarkTheme = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private boolean isSpeechReady = false;
    private boolean hasRetriedWakeUp = false;
    private int activeRecognizerId = 0;
    private int voiceSessionToken = 0;
    private String activeVoiceLanguageTag = "my-MM";
    private Runnable pendingVoiceStartTask;
    private Runnable voiceReadyWatchdog;
    private Runnable voiceResultWatchdog;
    private Runnable pendingAnnouncementTask;

    private TextToSpeech shanTts;
    private volatile boolean isShanTtsSpeaking = false;
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
        destroySpeechRecognizer(false);
        if (accessibilityHelper != null) accessibilityHelper.shutdown();
        if (feedbackManager != null) feedbackManager.release();
        if (shanVoiceInput != null) shanVoiceInput.destroy();
        if (shanTts != null) {
            isShanTtsSpeaking = false;
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
            if (status == TextToSpeech.SUCCESS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    shanTts.setAudioAttributes(attrs);
                }
                shanTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { isShanTtsSpeaking = true; }
                    @Override public void onDone(String utteranceId) { isShanTtsSpeaking = false; }
                    @Override public void onError(String utteranceId) { isShanTtsSpeaking = false; }
                    @Override public void onError(String utteranceId, int errorCode) { isShanTtsSpeaking = false; }
                });
            }
        }, "com.shan.tts");

        Context safeContext = getSafeContext();
        SharedPreferences prefs = safeContext.getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);

        feedbackManager.loadSettings(prefs);
        touchHandler.loadSettings(prefs);
        layoutManager.initKeyboards(prefs);
        useSmartEcho = prefs.getBoolean("smart_echo", false);
        autoCapsEnabled = prefs.getBoolean("auto_caps", true);
        useMmPhonetic = prefs.getBoolean("use_phonetic_sounds", true);
        useShanPhonetic = prefs.getBoolean("use_shan_phonetic_sounds", true);

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
            public boolean onAccessibilityKeyLongClick(int primaryCode, Keyboard.Key key) {
                return touchHandler != null && touchHandler.performLongPressAction(primaryCode);
            }
            @Override
            public boolean shouldConsumeAccessibilityClick(int virtualViewId) {
                return touchHandler != null && touchHandler.consumeHoverOwnedClick(virtualViewId);
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

        accessibilityHelper.setLayoutManager(layoutManager);
        accessibilityHelper.loadHapticSettings(prefs);
        accessibilityHelper.setPhoneticEnabled(useMmPhonetic, useShanPhonetic);
        ViewCompat.setAccessibilityDelegate(keyboardView, accessibilityHelper);

        keyboardView.setOnHoverListener((v, event) -> handleKeyboardHover(event));

        return layout;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        if (!restarting) {
            if (shanVoiceInput != null) shanVoiceInput.cancel();
            stopSpeechListeningIfActive();
            shadowLine.setLength(0);
            currentWord.setLength(0);
            shadowNeedsResync = true;
            hasTextSelection = false;
            if (inputLogic != null) inputLogic.resetPreVowelState();
            if (touchHandler != null) touchHandler.reset();
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

        feedbackManager.loadSettings(prefs);
        touchHandler.loadSettings(prefs);
        layoutManager.initKeyboards(prefs);
        useSmartEcho = prefs.getBoolean("smart_echo", false);
        autoCapsEnabled = prefs.getBoolean("auto_caps", true);
        useMmPhonetic = prefs.getBoolean("use_phonetic_sounds", true);
        useShanPhonetic = prefs.getBoolean("use_shan_phonetic_sounds", true);

        if (accessibilityHelper != null) {
            accessibilityHelper.loadHapticSettings(prefs);
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
            if (inputLogic != null) inputLogic.resetPreVowelState();
            syncShadowFromIc(ic);
            hideCandidatesImmediately();
        }

        layoutManager.updateEditorInfo(info);
        layoutManager.determineKeyboardForInputType();
        updateShiftState(true);
    }

    private void syncShadowFromIc(InputConnection ic) {
        shadowLine.setLength(0);
        currentWord.setLength(0);
        if (ic == null) {
            shadowNeedsResync = false;
            return;
        }
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        if (before != null && before.length() > 0) {
            String s = before.toString();
            int lastNewLine = s.lastIndexOf('\n');
            String line = (lastNewLine != -1) ? s.substring(lastNewLine + 1) : s;
            shadowLine.append(line);
            extractCurrentWordFromShadow();
        }
        shadowNeedsResync = false;
    }

    private void appendToShadow(CharSequence text) {
        if (text == null || text.length() == 0) return;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                shadowLine.setLength(0);
                currentWord.setLength(0);
            } else {
                shadowLine.append(c);
                if (isWordBoundary(c)) {
                    currentWord.setLength(0);
                } else {
                    currentWord.append(c);
                }
            }
        }
        trimShadowIfNeeded();
    }

    private void trimShadowIfNeeded() {
        if (shadowLine.length() > SHADOW_MAX_LEN) {
            shadowLine.delete(0, shadowLine.length() - SHADOW_MAX_LEN);
        }
    }

    private void extractCurrentWordFromShadow() {
        currentWord.setLength(0);
        int len = shadowLine.length();
        int start = len;
        while (start > 0 && !isWordBoundary(shadowLine.charAt(start - 1))) {
            start--;
        }
        if (start < len) {
            currentWord.append(shadowLine, start, len);
        }
    }

    private static boolean isWordBoundary(char c) {
        return Character.isWhitespace(c) || c == '\u104A' || c == '\u104B';
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
        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            accessibilityManager.interrupt();
            return 100;
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
        if (isShanTtsSpeaking && shanTts != null) {
            isShanTtsSpeaking = false;
            try { shanTts.stop(); } catch (Exception ignored) { }
        }
    }

    public void handleInput(int primaryCode, Keyboard.Key key) {
        stopSpeech();

        if (layoutManager == null || feedbackManager == null) return;

        if (primaryCode != -10) {
            feedbackManager.playSound(primaryCode);
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        if (key != null && key.text != null) {
            ic.commitText(key.text, 1);
            if (inputLogic != null) inputLogic.resetPreVowelState();
            if (!shadowNeedsResync) {
                appendToShadow(key.text);
            }
            triggerCandidateUpdate(160);
            if (!layoutManager.isSymbols && !layoutManager.isEmoji && layoutManager.isCaps && !layoutManager.isCapsLocked) {
                layoutManager.isCaps = false;
                layoutManager.updateKeyboardLayout();
            }
            manualShiftOverride = false;
            updateShiftState(false);
            return;
        }

        boolean isShanUI = (layoutManager.currentLanguageId == 2);
        boolean effectiveSmartEcho = useSmartEcho && !(isShanUI && useShanPhonetic);

        try {
            switch (primaryCode) {
                case -5:
                    manualShiftOverride = false;
                    if (inputLogic != null) inputLogic.onCursorOrContextChanged();
                    boolean deletedSelection = false;
                    if (hasTextSelection) {
                        CharSequence selectedText = ic.getSelectedText(0);
                        if (selectedText != null && selectedText.length() > 0) {
                            ic.commitText("", 1);
                            hasTextSelection = false;
                            shadowNeedsResync = true;
                            deletedSelection = true;
                            if (effectiveSmartEcho) announceText("Deleted selected text");
                        }
                    }

                    if (!deletedSelection) {
                        if (!textProcessor.handleCustomBackspace(ic)) {
                            CharSequence textBefore = null;
                            if (effectiveSmartEcho || layoutManager.currentLanguageId != 0) {
                                CharSequence beforeDel = ic.getTextBeforeCursor(1, 0);
                                if (beforeDel != null && beforeDel.length() == 1 && beforeDel.charAt(0) == ZWSP) {
                                    ic.deleteSurroundingText(1, 0);
                                }
                                if (effectiveSmartEcho) {
                                    textBefore = ic.getTextBeforeCursor(1, 0);
                                }
                            }
                            ic.deleteSurroundingText(1, 0);

                            if (!shadowNeedsResync && shadowLine.length() > 0) {
                                shadowLine.deleteCharAt(shadowLine.length() - 1);
                                extractCurrentWordFromShadow();
                            } else {
                                shadowNeedsResync = true;
                            }

                            if (effectiveSmartEcho) {
                                if (textBefore != null && textBefore.length() > 0) announceText("Deleted " + textBefore);
                                else announceText("Delete");
                            }
                        } else {
                            shadowNeedsResync = true;
                            if (effectiveSmartEcho) announceText("Deleted");
                        }
                    }

                    triggerCandidateUpdate(160);
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
                    stopSpeechListeningIfActive();
                    if (inputLogic != null) inputLogic.resetPreVowelState();
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    layoutManager.changeLanguage();
                    if (touchHandler != null) touchHandler.reset();
                    if (phoneticManager != null) phoneticManager.setLanguageId(layoutManager.currentLanguageId);
                    if (effectiveSmartEcho) announceText(layoutManager.currentLanguageId == 1 ? "Myanmar" : (layoutManager.currentLanguageId == 2 ? "Shan" : "English"));
                    break;

                case -4:
                    manualShiftOverride = false;
                    if (inputLogic != null) inputLogic.resetPreVowelState();
                    feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    if (shadowNeedsResync) {
                        syncShadowFromIc(ic);
                    }
                    EditorInfo editorInfo = getCurrentInputEditorInfo();
                    boolean isMultiLine = editorInfo != null && (editorInfo.inputType & EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
                    if (editorInfo != null && !isMultiLine && (editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_NONE) {
                        sendDefaultEditorAction(true);
                    } else {
                        ic.commitText("\n", 1);
                    }
                    if (effectiveSmartEcho) announceText("Enter");
                    saveWordAndReset();
                    shadowLine.setLength(0);
                    shadowNeedsResync = false;

                    if (layoutManager.isSymbols || layoutManager.isEmoji) {
                        layoutManager.isSymbols = false;
                        layoutManager.isEmoji = false;
                        layoutManager.isCaps = false;
                        layoutManager.updateKeyboardLayout();
                    }
                    break;

                case 32:
                    manualShiftOverride = false;
                    if (inputLogic != null) inputLogic.resetPreVowelState();
                    if (shadowNeedsResync) {
                        syncShadowFromIc(ic);
                    }
                    String wordBeforeSpace = currentWord.length() > 0 ? currentWord.toString() : null;
                    ic.commitText(" ", 1);
                    appendToShadow(" ");
                    if (effectiveSmartEcho) {
                        if (wordBeforeSpace != null && !wordBeforeSpace.isEmpty()) announceText(wordBeforeSpace);
                        else announceText("Space");
                    }
                    saveWordAndReset(wordBeforeSpace);

                    if (layoutManager.isSymbols || layoutManager.isEmoji) {
                        layoutManager.isSymbols = false;
                        layoutManager.isEmoji = false;
                        layoutManager.isCaps = false;
                        layoutManager.updateKeyboardLayout();
                    }
                    break;

                default:
                    manualShiftOverride = false;
                    boolean wasCaps = layoutManager.isCaps;
                    boolean customInserted = textProcessor.handleCustomInsert(ic, primaryCode);
                    boolean reordered = false;
                    if (!customInserted) {
                        reordered = inputLogic.processInput(ic, primaryCode, key);
                    }

                    if (!customInserted && !reordered && !shadowNeedsResync) {
                        String committedStr;
                        if (key != null && key.label != null && key.label.length() > 1) {
                            committedStr = key.label.toString();
                        } else if (primaryCode >= 32) {
                            int cp = primaryCode;
                            if (layoutManager.currentLanguageId == 0 && !layoutManager.isSymbols && !layoutManager.isEmoji
                                    && wasCaps && Character.isLetter(cp)) {
                                cp = Character.toUpperCase(cp);
                            }
                            committedStr = new String(Character.toChars(cp));
                        } else {
                            committedStr = null;
                        }
                        if (committedStr != null) {
                            appendToShadow(committedStr);
                        } else {
                            shadowNeedsResync = true;
                        }
                    } else {
                        shadowNeedsResync = true;
                        if (effectiveSmartEcho) {
                            syncShadowFromIc(ic);
                        }
                    }

                    if (effectiveSmartEcho && currentWord.length() > 0) {
                        announceText(currentWord.toString());
                    }

                    if (!layoutManager.isSymbols && !layoutManager.isEmoji && layoutManager.isCaps && !layoutManager.isCapsLocked) {
                        layoutManager.isCaps = false;
                        layoutManager.updateKeyboardLayout();
                    }
                    triggerCandidateUpdate(160);
                    break;
            }
        } catch (Exception e) { e.printStackTrace(); }

        updateShiftState(false);
    }

    private void updateShiftState(boolean allowIpc) {
        if (layoutManager == null) return;
        if (layoutManager.currentLanguageId != 0 || layoutManager.isSymbols || layoutManager.isEmoji) return;
        if (layoutManager.isCapsLocked || manualShiftOverride || !autoCapsEnabled) return;

        boolean shouldBeCaps;
        if (!allowIpc && !shadowNeedsResync) {
            shouldBeCaps = computeCapsFromShadow();
        } else if (allowIpc) {
            InputConnection ic = getCurrentInputConnection();
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ic == null || ei == null || ei.inputType == EditorInfo.TYPE_NULL) return;
            shouldBeCaps = (ic.getCursorCapsMode(ei.inputType) != 0);
        } else {
            return;
        }

        if (layoutManager.isCaps != shouldBeCaps) {
            layoutManager.isCaps = shouldBeCaps;
            layoutManager.updateKeyboardLayout();
        }
    }

    private boolean computeCapsFromShadow() {
        int len = shadowLine.length();
        if (len == 0) return true;
        int i = len - 1;
        boolean sawSpace = false;
        while (i >= 0 && shadowLine.charAt(i) == ' ') {
            sawSpace = true;
            i--;
        }
        if (i < 0) return true;
        char lastNonSpace = shadowLine.charAt(i);
        return sawSpace && (lastNonSpace == '.' || lastNonSpace == '!' || lastNonSpace == '?');
    }

    public KeyboardView getKeyboardView() { return keyboardView; }

    boolean commitShanTranscript(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (!canUseShanVoice() || ic == null || text == null || text.trim().isEmpty()) return false;
        ic.finishComposingText();
        boolean inserted = ic.commitText(text, 1);
        if (inserted) {
            if (inputLogic != null) inputLogic.resetPreVowelState();
            appendToShadow(text);
            currentWord.setLength(0);
            hideCandidatesImmediately();
            updateShiftState(false);
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

        if (layoutManager != null && layoutManager.currentLanguageId == 2 && useShanPhonetic && !isNativeTalkBackText(text)) {
            silenceTalkBack();
            handler.postDelayed(this::silenceTalkBack, 15);
            handler.postDelayed(this::silenceTalkBack, 40);

            long now = System.currentTimeMillis();
            if (!(text.equals(lastShanText) && (now - lastShanTime < 80))) {
                if (shanTts != null) {
                    Bundle params = new Bundle();
                    params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ACCESSIBILITY);
                    isShanTtsSpeaking = true;
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
        if (!shadowNeedsResync && currentWord.length() == 0) {
            hideCandidatesImmediately();
            return;
        }
        handler.postDelayed(pendingCandidateUpdate, delayMillis);
    }

    private void saveWordAndReset() {
        saveWordAndReset(currentWord.length() > 0 ? currentWord.toString() : null);
    }

    private void saveWordAndReset(String wordToSave) {
        if (wordToSave != null && !wordToSave.isEmpty() && isUserUnlocked()) {
            dbExecutor.execute(() -> {
                SuggestionDB db = suggestionDB;
                if (db == null) {
                    db = SuggestionDB.getInstance(SaiNawKeyboardService.this);
                    suggestionDB = db;
                }
                db.saveWord(textProcessor.normalizeText(wordToSave));
            });
        }
        currentWord.setLength(0);
        hideCandidatesImmediately();
    }

    private void performCandidateSearch() {
        if (shadowNeedsResync) {
            syncShadowFromIc(getCurrentInputConnection());
        }
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
            syncShadowFromIc(ic);
            int curLen = currentWord.length();
            if (curLen > 0) {
                ic.deleteSurroundingText(curLen, 0);
                if (shadowLine.length() >= curLen) {
                    shadowLine.delete(shadowLine.length() - curLen, shadowLine.length());
                }
            }
            ic.commitText(suggestion + " ", 1);
            if (inputLogic != null) inputLogic.resetPreVowelState();
            appendToShadow(suggestion + " ");
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
        updateShiftState(false);
    }

    private void cancelVoiceTimers() {
        if (pendingVoiceStartTask != null) {
            handler.removeCallbacks(pendingVoiceStartTask);
            pendingVoiceStartTask = null;
        }
        if (voiceReadyWatchdog != null) {
            handler.removeCallbacks(voiceReadyWatchdog);
            voiceReadyWatchdog = null;
        }
        if (voiceResultWatchdog != null) {
            handler.removeCallbacks(voiceResultWatchdog);
            voiceResultWatchdog = null;
        }
    }

    private void safeDestroySpeechRecognizer(SpeechRecognizer old) {
        if (old == null) return;
        try { old.cancel(); } catch (Exception ignored) { }
        try { old.destroy(); } catch (Exception ignored) { }
    }

    private void destroySpeechRecognizer(boolean asyncCleanup) {
        activeRecognizerId++;
        voiceSessionToken++;
        cancelVoiceTimers();
        isListening = false;
        isSpeechReady = false;

        final SpeechRecognizer old = speechRecognizer;
        speechRecognizer = null;
        if (old != null) {
            if (asyncCleanup) {
                handler.post(() -> safeDestroySpeechRecognizer(old));
            } else {
                safeDestroySpeechRecognizer(old);
            }
        }
    }

    private void stopSpeechListeningIfActive() {
        voiceSessionToken++;
        cancelVoiceTimers();
        if (speechRecognizer != null && isListening) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
        }
        isListening = false;
        isSpeechReady = false;
    }

    private ComponentName resolvePreferredSpeechService() {
        try {
            PackageManager pm = getPackageManager();
            Intent serviceIntent = new Intent(RecognitionService.SERVICE_INTERFACE);
            List<ResolveInfo> services = pm.queryIntentServices(serviceIntent, 0);
            if (services == null || services.isEmpty()) return null;

            ComponentName googleAppFallback = null;
            for (ResolveInfo info : services) {
                if (info == null || info.serviceInfo == null) continue;
                String pkg = info.serviceInfo.packageName;
                String cls = info.serviceInfo.name;
                if (pkg == null || cls == null) continue;
                if ("com.google.android.tts".equals(pkg)) {
                    return new ComponentName(pkg, cls);
                }
                if (googleAppFallback == null && "com.google.android.googlequicksearchbox".equals(pkg)) {
                    googleAppFallback = new ComponentName(pkg, cls);
                }
            }
            return googleAppFallback;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean containsMyanmarScript(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u1000' && c <= '\u109F')
                    || (c >= '\uAA60' && c <= '\uAA7F')
                    || (c >= '\uA9E0' && c <= '\uA9FF')) {
                return true;
            }
        }
        return false;
    }

    private static String selectBestVoiceResult(ArrayList<String> results, float[] scores, String languageTag) {
        if (results == null || results.isEmpty()) return "";
        boolean expectMyanmar = "my-MM".equalsIgnoreCase(languageTag);
        String best = null;
        float bestScore = -1.0f;

        for (int i = 0; i < results.size(); i++) {
            String candidate = results.get(i);
            if (candidate == null) continue;
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) continue;

            boolean hasMyanmar = containsMyanmarScript(trimmed);
            if (expectMyanmar && !hasMyanmar) continue;
            if (!expectMyanmar && hasMyanmar) continue;

            float score = (scores != null && i < scores.length) ? scores[i] : 0.0f;
            if (best == null || score > bestScore) {
                best = trimmed;
                bestScore = score;
            }
        }

        if (best == null) {
            for (int i = 0; i < results.size(); i++) {
                String candidate = results.get(i);
                if (candidate != null && !candidate.trim().isEmpty()) {
                    best = candidate.trim();
                    break;
                }
            }
        }

        if (best == null) return "";
        if (expectMyanmar) {
            return Normalizer.normalize(best, Normalizer.Form.NFC);
        }
        return best;
    }

    private Intent createSpeechIntent() {
        String voiceLang = (layoutManager != null && layoutManager.currentLanguageId == 1) ? "my-MM" : "en-US";
        activeVoiceLanguageTag = voiceLang;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLang);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceLang);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, VOICE_COMPLETE_SILENCE_MS);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, VOICE_POSSIBLY_COMPLETE_SILENCE_MS);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        return intent;
    }

    private SpeechRecognizer getOrCreateSpeechRecognizer() {
        if (speechRecognizer != null) {
            return speechRecognizer;
        }

        ComponentName preferredService = resolvePreferredSpeechService();
        if (preferredService == null && !SpeechRecognizer.isRecognitionAvailable(this)) {
            return null;
        }

        final int myRecognizerId = ++activeRecognizerId;
        SpeechRecognizer recognizer = null;
        if (preferredService != null) {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this, preferredService);
            } catch (Exception ignored) {
                recognizer = null;
            }
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        }

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle p) {
                if (myRecognizerId != activeRecognizerId) return;
                if (voiceReadyWatchdog != null) {
                    handler.removeCallbacks(voiceReadyWatchdog);
                    voiceReadyWatchdog = null;
                }
                isSpeechReady = true;
                hasRetriedWakeUp = false;
                silenceTalkBack();
                if (feedbackManager != null) feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}

            @Override
            public void onEndOfSpeech() {
                if (myRecognizerId != activeRecognizerId) return;
                isListening = false;
                final int token = voiceSessionToken;
                if (voiceResultWatchdog != null) handler.removeCallbacks(voiceResultWatchdog);
                voiceResultWatchdog = () -> {
                    if (token == voiceSessionToken && myRecognizerId == activeRecognizerId) {
                        destroySpeechRecognizer(true);
                        if (feedbackManager != null) feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                    }
                };
                handler.postDelayed(voiceResultWatchdog, VOICE_RESULT_TIMEOUT_MS);
            }

            @Override
            public void onError(int error) {
                if (myRecognizerId != activeRecognizerId) return;
                boolean wasReady = isSpeechReady;
                cancelVoiceTimers();
                isListening = false;
                isSpeechReady = false;

                boolean needsRebuild = (
                        error == ERROR_SERVER_DISCONNECTED_COMPAT
                        || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        || error == SpeechRecognizer.ERROR_CLIENT
                        || error == SpeechRecognizer.ERROR_AUDIO
                        || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                );
                if (needsRebuild) {
                    destroySpeechRecognizer(true);
                }

                boolean isWakeUpFailure = !wasReady && (
                        error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                        || error == SpeechRecognizer.ERROR_SERVER
                        || error == SpeechRecognizer.ERROR_CLIENT
                        || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        || error == SpeechRecognizer.ERROR_AUDIO
                        || error == ERROR_SERVER_DISCONNECTED_COMPAT
                );

                if (isWakeUpFailure && !hasRetriedWakeUp) {
                    hasRetriedWakeUp = true;
                    launchVoiceSession(true);
                    return;
                }

                if (feedbackManager != null) feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
            }

            @Override
            public void onResults(Bundle r) {
                if (myRecognizerId != activeRecognizerId) return;
                cancelVoiceTimers();
                isListening = false;
                isSpeechReady = false;
                hasRetriedWakeUp = false;

                ArrayList<String> matches = (r != null) ? r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                float[] scores = (r != null) ? r.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES) : null;
                String bestResult = selectBestVoiceResult(matches, scores, activeVoiceLanguageTag);
                InputConnection ic = getCurrentInputConnection();
                if (!bestResult.isEmpty() && ic != null) {
                    String committed = bestResult + " ";
                    ic.commitText(committed, 1);
                    if (inputLogic != null) inputLogic.resetPreVowelState();
                    appendToShadow(committed);
                    currentWord.setLength(0);
                    hideCandidatesImmediately();
                    updateShiftState(false);
                    if (keyboardView != null && accessibilityManager != null && accessibilityManager.isEnabled()) {
                        keyboardView.announceForAccessibility(bestResult);
                    }
                }
            }

            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int t, Bundle p) {}
        });

        speechRecognizer = recognizer;
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
            if (speechRecognizer != null && isSpeechReady) {
                try {
                    speechRecognizer.stopListening();
                } catch (Exception ignored) {
                    destroySpeechRecognizer(true);
                }
                isListening = false;
            } else {
                stopSpeechListeningIfActive();
            }
            return;
        }

        hasRetriedWakeUp = false;
        launchVoiceSession(false);
    }

    private void launchVoiceSession(final boolean isRetry) {
        cancelVoiceTimers();
        final int sessionToken = ++voiceSessionToken;

        long delayMs;
        if (isRetry) {
            delayMs = VOICE_RETRY_DELAY_MS;
        } else {
            stopSpeech();
            boolean a11yOn = (accessibilityManager != null && accessibilityManager.isEnabled());
            if (a11yOn) {
                silenceTalkBack();
                delayMs = 120L;
            } else {
                delayMs = 0L;
            }
        }

        Runnable startTask = () -> {
            pendingVoiceStartTask = null;
            if (sessionToken != voiceSessionToken) return;

            SpeechRecognizer recognizer = getOrCreateSpeechRecognizer();
            if (recognizer == null) return;

            try {
                isListening = true;
                isSpeechReady = false;
                recognizer.startListening(createSpeechIntent());

                voiceReadyWatchdog = () -> {
                    if (sessionToken == voiceSessionToken && isListening && !isSpeechReady) {
                        destroySpeechRecognizer(true);
                        if (!hasRetriedWakeUp) {
                            hasRetriedWakeUp = true;
                            launchVoiceSession(true);
                        } else if (feedbackManager != null) {
                            feedbackManager.playHaptic(SaiNawFeedbackManager.HAPTIC_TYPE);
                        }
                    }
                };
                handler.postDelayed(voiceReadyWatchdog, VOICE_READY_TIMEOUT_MS);
            } catch (Exception e) {
                destroySpeechRecognizer(true);
                if (!hasRetriedWakeUp) {
                    hasRetriedWakeUp = true;
                    launchVoiceSession(true);
                }
            }
        };

        if (delayMs <= 0L) {
            startTask.run();
        } else {
            pendingVoiceStartTask = startTask;
            handler.postDelayed(startTask, delayMs);
        }
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

    private boolean isTouchExplorationActive() {
        return accessibilityManager != null && accessibilityManager.isTouchExplorationEnabled();
    }

    @Override public void onKey(int p, int[] k) {
        if (isTouchExplorationActive() && touchHandler != null && touchHandler.consumeHoverOwnedTouch()) {
            return;
        }
        if (touchHandler != null && touchHandler.isLongPressHandled()) {
            return;
        }
        handleInput(p, null);
    }

    @Override public void onText(CharSequence t) {
        if (isTouchExplorationActive() && touchHandler != null && touchHandler.consumeHoverOwnedTouch()) {
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || t == null) return;
        stopSpeech();
        ic.commitText(t, 1);
        if (inputLogic != null) inputLogic.resetPreVowelState();
        if (!shadowNeedsResync) {
            appendToShadow(t);
        }
        triggerCandidateUpdate(160);
        if (feedbackManager != null) feedbackManager.playSound(0);
        if (layoutManager != null && !layoutManager.isSymbols && !layoutManager.isEmoji && layoutManager.isCaps && !layoutManager.isCapsLocked) {
            layoutManager.isCaps = false;
            layoutManager.updateKeyboardLayout();
        }
        manualShiftOverride = false;
        updateShiftState(false);
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
            shadowNeedsResync = true;
            if (inputLogic != null) inputLogic.onCursorOrContextChanged();
            return traversedText;
        }
        return null;
    }

    @Override public void swipeDown() {}
    @Override public void swipeUp() {}

    @Override public void onWindowHidden() {
        if (shanVoiceInput != null) shanVoiceInput.cancel();
        stopSpeechListeningIfActive();
        super.onWindowHidden();
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        if (shanVoiceInput != null) shanVoiceInput.cancel();
        stopSpeechListeningIfActive();
        super.onFinishInputView(finishingInput);
    }

    @Override public void onFinishInput() {
        if (shanVoiceInput != null) shanVoiceInput.cancel();
        stopSpeechListeningIfActive();
        super.onFinishInput();
    }

    @Override public void onDestroy() {
        if (accessibilityHelper != null) accessibilityHelper.shutdown();
        if (feedbackManager != null) feedbackManager.release();
        if (shanVoiceInput != null) shanVoiceInput.destroy();
        destroySpeechRecognizer(false);
        super.onDestroy();
        if (shanTts != null) {
            isShanTtsSpeaking = false;
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
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        hasTextSelection = (newSelStart != newSelEnd);
        if (Math.abs(newSelEnd - oldSelEnd) > 2 || newSelEnd == 0) {
            shadowNeedsResync = true;
            if (inputLogic != null) inputLogic.onCursorOrContextChanged();
            updateShiftState(true);
        }
    }

    @Override
    public void onUpdateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo);
    }
}
