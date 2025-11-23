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
    
    // Keyboards အားလုံး
    private Keyboard qwertyKeyboard;
    private Keyboard qwertyShiftKeyboard;
    private Keyboard myanmarKeyboard;
    private Keyboard myanmarShiftKeyboard;
    private Keyboard shanKeyboard;
    private Keyboard shanShiftKeyboard;
    private Keyboard symbolsKeyboard; // 123 ကီးဘုတ်
    
    private Keyboard currentKeyboard;
    
    private boolean isCaps = false;
    private AccessibilityManager accessibilityManager;
    
    // TalkBack Variables
    private int lastHoverKeyIndex = -1;

    @Override
    public View onCreateInputView() {
        View layout = getLayoutInflater().inflate(R.layout.input_view, null);
        keyboardView = layout.findViewById(R.id.keyboard_view);
        accessibilityManager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);

        // XML များ Load လုပ်ခြင်း
        qwertyKeyboard = new Keyboard(this, R.xml.qwerty);
        qwertyShiftKeyboard = new Keyboard(this, R.xml.qwerty_shift);
        myanmarKeyboard = new Keyboard(this, R.xml.myanmar);
        myanmarShiftKeyboard = new Keyboard(this, R.xml.myanmar_shift);
        shanKeyboard = new Keyboard(this, R.xml.shan);
        shanShiftKeyboard = new Keyboard(this, R.xml.shan_shift);
        symbolsKeyboard = new Keyboard(this, R.xml.symbols); // Symbols Load လုပ်ပြီ

        currentKeyboard = qwertyKeyboard;
        keyboardView.setKeyboard(currentKeyboard);
        keyboardView.setOnKeyboardActionListener(this);

        // TalkBack Logic (Hover)
        keyboardView.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, MotionEvent event) {
                return handleHover(event);
            }
        });

        // Touch Listener (Double Typing ဖြေရှင်းချက်)
        keyboardView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (accessibilityManager.isEnabled()) {
                    // TalkBack ဖွင့်ထားရင် System Touch ကို Block လုပ်မယ် (Return true)
                    // ဒါမှ စာလုံး နှစ်ခါမရိုက်မှာ ဖြစ်သည်
                    return true; 
                }
                return false; // TalkBack ပိတ်ထားရင် ပုံမှန်အတိုင်း လုပ်မယ်
            }
        });

        return layout;
    }

    private boolean handleHover(MotionEvent event) {
        if (!accessibilityManager.isEnabled()) return false;

        int action = event.getAction();
        int touchX = (int) event.getX();
        int touchY = (int) event.getY();
        int keyIndex = getNearestKeyIndex(touchX, touchY);

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                if (keyIndex != -1 && keyIndex != lastHoverKeyIndex) {
                    lastHoverKeyIndex = keyIndex;
                    playHaptic();
                    announceKeyText(currentKeyboard.getKeys().get(keyIndex));
                }
                break;

            case MotionEvent.ACTION_HOVER_EXIT:
                // Lift to Type
                if (lastHoverKeyIndex != -1) {
                    Keyboard.Key key = currentKeyboard.getKeys().get(lastHoverKeyIndex);
                    int code = key.codes[0];
                    
                    if (code < 0) {
                        onKey(code, null);
                    } else {
                        if (key.label != null) onText(key.label);
                        else if (key.text != null) onText(key.text);
                        
                        if (key.codes.length > 0) onKey(key.codes[0], null);
                    }
                    lastHoverKeyIndex = -1;
                }
                break;
        }
        return true; 
    }

    private int getNearestKeyIndex(int x, int y) {
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).isInside(x, y)) return i;
        }
        // Distance Check
        int closestIndex = -1;
        double minDistance = Double.MAX_VALUE;
        int threshold = 150; 
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            int centerX = key.x + key.width / 2;
            int centerY = key.y + key.height / 2;
            double dist = Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2));
            if (dist < minDistance && dist < threshold) {
                minDistance = dist;
                closestIndex = i;
            }
        }
        return closestIndex;
    }

    private void announceKeyText(Keyboard.Key key) {
        if (!accessibilityManager.isEnabled()) return;
        
        String text = null;
        int code = key.codes[0];

        if (code == -5) text = "Delete";
        else if (code == -1) text = isCaps ? "Shift On" : "Shift";
        else if (code == 32) text = "Space";
        else if (code == -4) text = "Enter";
        else if (code == -2) text = "Numbers and Symbols"; // Symbols
        else if (code == -6) text = "Alphabet"; // ABC
        else if (code == -101) text = "Next Language";
        else if (code == -10) text = "Voice Typing";

        if (text == null && key.label != null) text = key.label.toString();
        if (text == null && key.text != null) text = key.text.toString();

        if (text != null) {
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
            case -1: // Shift
                isCaps = !isCaps;
                updateKeyboardLayout();
                break;
            case -2: // Switch to Symbols (123)
                currentKeyboard = symbolsKeyboard;
                keyboardView.setKeyboard(currentKeyboard);
                break;
            case -6: // Switch back to ABC from Symbols
                currentKeyboard = qwertyKeyboard; // Default back to English
                keyboardView.setKeyboard(currentKeyboard);
                break;
            case -101: // Next Language
                changeLanguage();
                break;
            case -4: // Enter
                int options = getCurrentInputEditorInfo().imeOptions;
                int action = options & EditorInfo.IME_MASK_ACTION;
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_GO) {
                    sendDefaultEditorAction(true);
                } else {
                    ic.commitText("\n", 1);
                }
                break;
            case -5: // Delete
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
        if (currentKeyboard == qwertyKeyboard || currentKeyboard == qwertyShiftKeyboard || currentKeyboard == symbolsKeyboard) {
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
        if (primaryCode == 4141) { 
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
        return false;
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

    private void playHaptic() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(10);
        } catch (Exception e) {}
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

