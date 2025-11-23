package com.sainaw.mm.board;

import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.speech.RecognizerIntent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.os.Vibrator;
import java.util.List;

public class SaiNawKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    private KeyboardView keyboardView;
    private Keyboard qwertyKeyboard;
    private Keyboard qwertyShiftKeyboard;
    private Keyboard myanmarKeyboard;
    private Keyboard myanmarShiftKeyboard;
    private Keyboard shanKeyboard;
    private Keyboard shanShiftKeyboard;
    private Keyboard currentKeyboard;
    
    private boolean isCaps = false;
    private AccessibilityManager accessibilityManager;
    private int lastAnnouncedIndex = -1;

    @Override
    public View onCreateInputView() {
        View layout = getLayoutInflater().inflate(R.layout.input_view, null);
        keyboardView = layout.findViewById(R.id.keyboard_view);
        accessibilityManager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);

        qwertyKeyboard = new Keyboard(this, R.xml.qwerty);
        qwertyShiftKeyboard = new Keyboard(this, R.xml.qwerty_shift);
        myanmarKeyboard = new Keyboard(this, R.xml.myanmar);
        myanmarShiftKeyboard = new Keyboard(this, R.xml.myanmar_shift);
        shanKeyboard = new Keyboard(this, R.xml.shan);
        shanShiftKeyboard = new Keyboard(this, R.xml.shan_shift);

        currentKeyboard = qwertyKeyboard;
        keyboardView.setKeyboard(currentKeyboard);
        keyboardView.setOnKeyboardActionListener(this);

        // TalkBack Touch Handler
        keyboardView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleTouch(event);
            }
        });

        return layout;
    }

    private boolean handleTouch(MotionEvent event) {
        if (!accessibilityManager.isEnabled()) {
            return keyboardView.onTouchEvent(event);
        }

        int action = event.getAction();
        int touchX = (int) event.getX();
        int touchY = (int) event.getY();
        int keyIndex = getNearestKeyIndex(touchX, touchY);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (keyIndex != -1) {
                    lastAnnouncedIndex = keyIndex;
                    playHaptic();
                    announceKeyText(currentKeyboard.getKeys().get(keyIndex));
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (keyIndex != -1 && keyIndex != lastAnnouncedIndex) {
                    lastAnnouncedIndex = keyIndex;
                    playHaptic();
                    announceKeyText(currentKeyboard.getKeys().get(keyIndex));
                }
                break;
            case MotionEvent.ACTION_UP:
                if (keyIndex != -1) {
                    Keyboard.Key key = currentKeyboard.getKeys().get(keyIndex);
                    int code = key.codes[0];
                    
                    if (code < 0) {
                        onKey(code, null);
                    } else {
                        if (key.label != null) {
                            onText(key.label);
                        } else if (key.text != null) {
                            onText(key.text);
                        }
                        if (key.codes.length > 0) {
                            onKey(key.codes[0], null);
                        }
                    }
                }
                lastAnnouncedIndex = -1;
                break;
        }
        return true;
    }

    private int getNearestKeyIndex(int x, int y) {
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        
        // Direct Check
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).isInside(x, y)) return i;
        }
        
        // Distance Check
        int closestIndex = -1;
        double minDistance = Double.MAX_VALUE;
        int threshold = 150; 

        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            int keyCenterX = key.x + key.width / 2;
            int keyCenterY = key.y + key.height / 2;
            
            double dist = Math.sqrt(Math.pow(x - keyCenterX, 2) + Math.pow(y - keyCenterY, 2));
            
            if (dist < minDistance && dist < threshold) {
                minDistance = dist;
                closestIndex = i;
            }
        }
        return closestIndex;
    }

    private void announceKeyText(Keyboard.Key key) {
        if (!accessibilityManager.isEnabled()) return;
        
        String textToSpeak = null;
        if (key.label != null) textToSpeak = key.label.toString();
        
        int code = key.codes[0];
        if (code == -5) textToSpeak = "Delete";
        else if (code == -1) textToSpeak = isCaps ? "Shift On" : "Shift";
        else if (code == 32) textToSpeak = "Space";
        else if (code == -4) textToSpeak = "Enter";
        else if (code == -2) textToSpeak = "Symbols";
        else if (code == -3) textToSpeak = "Next Language";
        else if (code == -10) textToSpeak = "Voice Typing";

        if (textToSpeak == null && key.text != null) textToSpeak = key.text.toString();

        if (textToSpeak != null) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(textToSpeak);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }

    private void playHaptic() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(10);
        } catch (Exception e) {}
    }

    private void startVoiceInput() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            speakSystem("Voice typing not supported");
        }
    }

    private void speakSystem(String text) {
        if (accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        switch (primaryCode) {
            case -10: startVoiceInput(); break;
            case -1:
                isCaps = !isCaps;
                updateKeyboardLayout();
                break;
            case -3:
                changeLanguage();
                break;
            case -4:
                int options = getCurrentInputEditorInfo().imeOptions;
                int action = options & EditorInfo.IME_MASK_ACTION;
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_GO) {
                    sendDefaultEditorAction(true);
                } else {
                    ic.commitText("\n", 1);
                }
                break;
            case -5:
                ic.deleteSurroundingText(1, 0);
                break;
            case 0: break;
            default:
                if (isShanOrMyanmar() && handleSmartReordering(ic, primaryCode)) return;
                
                char code = (char) primaryCode;
                ic.commitText(String.valueOf(code), 1);
                
                if (isCaps) {
                    isCaps = false;
                    updateKeyboardLayout();
                }
        }
    }

    private void changeLanguage() {
        if (currentKeyboard == qwertyKeyboard || currentKeyboard == qwertyShiftKeyboard) {
            currentKeyboard = myanmarKeyboard;
            speakSystem("Myanmar");
        } else if (currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard) {
            currentKeyboard = shanKeyboard;
            speakSystem("Shan");
        } else {
            currentKeyboard = qwertyKeyboard;
            speakSystem("English");
        }
        isCaps = false;
        keyboardView.setKeyboard(currentKeyboard);
    }

    private void updateKeyboardLayout() {
        if (isCaps) {
            if (currentKeyboard == qwertyKeyboard) currentKeyboard = qwertyShiftKeyboard;
            else if (currentKeyboard == myanmarKeyboard) currentKeyboard = myanmarShiftKeyboard;
            else if (currentKeyboard == shanKeyboard) currentKeyboard = shanShiftKeyboard;
        } else {
            if (currentKeyboard == qwertyShiftKeyboard) currentKeyboard = qwertyKeyboard;
            else if (currentKeyboard == myanmarShiftKeyboard) currentKeyboard = myanmarKeyboard;
            else if (currentKeyboard == shanShiftKeyboard) currentKeyboard = shanKeyboard;
        }
        keyboardView.setKeyboard(currentKeyboard);
        keyboardView.invalidateAllKeys();
    }

    private boolean isShanOrMyanmar() {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard;
    }

    private boolean handleSmartReordering(InputConnection ic, int primaryCode) {
        // Simple Reordering Logic
        if (primaryCode == 4141) { // ိ
            CharSequence before = ic.getTextBeforeCursor(1, 0);
            if (before != null && before.length() > 0) {
                char c = before.charAt(0);
                if (c == 'ု' || c == 'ူ') {
                    ic.deleteSurroundingText(1, 0);
                    ic.commitText("ိ", 1);
                    ic.commitText(String.valueOf(c), 1);
                    return true;
                }
            }
        }
        // Add other reordering logic here as needed
        return false;
    }

    @Override public void onText(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
            if (isCaps) {
                isCaps = false;
                updateKeyboardLayout();
            }
        }
    }
    
    @Override public void onPress(int primaryCode) {}
    @Override public void onRelease(int primaryCode) {}
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}
