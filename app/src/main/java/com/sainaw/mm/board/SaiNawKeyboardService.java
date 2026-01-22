package com.sainaw.mm.board;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.KeyboardView;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import java.util.ArrayList;

public class SaiNawKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    private SaiNawCoreLogic coreLogic;
    private SaiNawUIHelper uiHelper;
    private KeyboardView keyboardView;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isListening = false;

    private final BroadcastReceiver userUnlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                if (coreLogic != null) coreLogic.initDB(context);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        coreLogic = new SaiNawCoreLogic(this);
        uiHelper = new SaiNawUIHelper(this, coreLogic);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(userUnlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        }
    }

    @Override
    public View onCreateInputView() {
        View layout = getLayoutInflater().inflate(R.layout.input_view, null);
        keyboardView = layout.findViewById(R.id.keyboard_view);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);
        keyboardView.setOnHoverListener((v, event) -> uiHelper.handleHover(event));
        
        uiHelper.attach(keyboardView);
        setupSpeechRecognizer();
        return layout;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        uiHelper.updateEditorInfo(attribute);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        uiHelper.onStartInputView(info);
        coreLogic.resetCurrentWord();
    }

    @Override
    public void onPress(int primaryCode) {
        uiHelper.handlePress(primaryCode);
    }

    @Override
    public void onRelease(int primaryCode) {
        if (uiHelper.handleRelease(primaryCode)) return;
        
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        switch (primaryCode) {
            case -5:
                handleDelete(ic);
                break;
            case -10:
                startVoiceInput();
                break;
            case -1:
                uiHelper.toggleShift();
                break;
            case -2:
                uiHelper.toggleSymbols();
                break;
            case -6:
                uiHelper.toggleAlpha();
                break;
            case -7:
                uiHelper.toggleEmoji();
                break;
            case -101:
                uiHelper.changeLanguage();
                break;
            case -4:
                handleEnter(ic);
                break;
            case 32:
                ic.commitText(" ", 1);
                uiHelper.playClick(32);
                uiHelper.smartEchoSpace(ic);
                coreLogic.saveAndReset();
                break;
            default:
                handleCharacter(primaryCode, ic);
        }
    }

    private void handleCharacter(int primaryCode, InputConnection ic) {
        String output = coreLogic.processInput(primaryCode);
        if (output != null) {
            ic.commitText(output, 1);
            uiHelper.playClick(primaryCode);
            uiHelper.smartEchoChar(ic);
            if (uiHelper.isCapsTemp()) uiHelper.resetCaps();
        }
    }

    private void handleDelete(InputConnection ic) {
        CharSequence selectedText = ic.getSelectedText(0);
        if (selectedText != null && selectedText.length() > 0) {
            ic.commitText("", 1);
        } else {
            ic.deleteSurroundingText(1, 0);
        }
        uiHelper.playClick(-5);
        uiHelper.announceText("Delete");
        coreLogic.backspace();
    }

    private void handleEnter(InputConnection ic) {
        EditorInfo editorInfo = getCurrentInputEditorInfo();
        boolean isMultiLine = (editorInfo.inputType & EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        
        if (!isMultiLine && (editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_NONE) {
            sendDefaultEditorAction(true);
        } else {
            ic.commitText("\n", 1);
        }
        uiHelper.playClick(-4);
        uiHelper.announceText("Enter");
        coreLogic.saveAndReset();
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
                    if (m != null && !m.isEmpty()) {
                        getCurrentInputConnection().commitText(m.get(0) + " ", 1);
                    }
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
        } else {
            try {
                speechRecognizer.startListening(speechIntent);
                isListening = true;
            } catch (Exception ignored) {}
        }
    }

    @Override public void onKey(int primaryCode, int[] keyCodes) {}
    @Override public void onText(CharSequence text) { getCurrentInputConnection().commitText(text, 1); }
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (coreLogic != null) coreLogic.close();
        try { unregisterReceiver(userUnlockReceiver); } catch (Exception ignored) {}
    }
}
