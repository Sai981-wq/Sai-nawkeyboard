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
    
    // TalkBack အတွက် Hover (ပွတ်ဆွဲ) ကို မှတ်သားမည့်ကောင်
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

        currentKeyboard = qwertyKeyboard;
        keyboardView.setKeyboard(currentKeyboard);
        keyboardView.setOnKeyboardActionListener(this);

        // TalkBack အတွက် အဓိက ပြင်ဆင်ချက် (Hover Listener)
        keyboardView.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, MotionEvent event) {
                return handleHover(event);
            }
        });

        // ပုံမှန် Touch (TalkBack ပိတ်ထားချိန်အတွက်)
        keyboardView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (accessibilityManager.isEnabled()) {
                    return false; // TalkBack ဖွင့်ထားရင် Touch ကို မသုံးဘဲ Hover ကိုသုံးမယ်
                }
                return false; // TalkBack ပိတ်ထားရင် Standard KeyboardView touch ကို အလုပ်လုပ်ခိုင်းမယ်
            }
        });

        return layout;
    }

    // TalkBack Logic အစစ် (Hover Event)
    private boolean handleHover(MotionEvent event) {
        if (!accessibilityManager.isEnabled()) return false;

        int action = event.getAction();
        int touchX = (int) event.getX();
        int touchY = (int) event.getY();
        
        // လက်ရှိ လက်ရောက်နေတဲ့ Key ကို ရှာမယ်
        int keyIndex = getNearestKeyIndex(touchX, touchY);

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
                // စတင်ထိတွေ့ချိန်
                if (keyIndex != -1) {
                    lastHoverKeyIndex = keyIndex;
                    playHaptic();
                    announceKeyText(currentKeyboard.getKeys().get(keyIndex));
                }
                break;

            case MotionEvent.ACTION_HOVER_MOVE:
                // ပွတ်ဆွဲနေချိန်
                if (keyIndex != -1 && keyIndex != lastHoverKeyIndex) {
                    lastHoverKeyIndex = keyIndex;
                    // Key အသစ်ပေါ် ရောက်တိုင်း အသံထွက်မယ်
                    playHaptic();
                    announceKeyText(currentKeyboard.getKeys().get(keyIndex));
                }
                break;

            case MotionEvent.ACTION_HOVER_EXIT:
                // လက်ကြွလိုက်ချိန် -> စာရိုက်မယ် (Commit on Lift)
                if (lastHoverKeyIndex != -1) {
                    Keyboard.Key key = currentKeyboard.getKeys().get(lastHoverKeyIndex);
                    
                    // စာရိုက်မည့် Logic
                    int code = key.codes[0];
                    if (code < 0) {
                        onKey(code, null); // Special Keys (Delete, Shift, etc.)
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
                    lastHoverKeyIndex = -1; // Reset
                }
                break;
        }
        return true; 
    }

    // Gap တွေကို ကျော်ပြီး အနီးဆုံး Key ရှာပေးမည့် Logic
    private int getNearestKeyIndex(int x, int y) {
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        
        // ၁. တည့်တည့်ထိမထိ စစ်မယ်
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).isInside(x, y)) return i;
        }
        
        // ၂. မထိရင် အနီးဆုံးကို ရှာမယ် (TalkBack သမားများအတွက် အရေးကြီးသည်)
        int closestIndex = -1;
        double minDistance = Double.MAX_VALUE;
        int threshold = 150; // Pixel အကွာအဝေး

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

    // အသံထွက်မည့် စာသား ပြင်ဆင်ခြင်း
    private void announceKeyText(Keyboard.Key key) {
        if (!accessibilityManager.isEnabled()) return;
        
        String textToSpeak = null;
        int code = key.codes[0];

        // Special Codes Translation
        if (code == -5) textToSpeak = "Delete";
        else if (code == -1) textToSpeak = isCaps ? "Shift On" : "Shift";
        else if (code == 32) textToSpeak = "Space";
        else if (code == -4) textToSpeak = "Enter";
        else if (code == -2) textToSpeak = "Symbols";
        else if (code == -101) textToSpeak = "Next Language"; // Code အသစ် (-101)
        else if (code == -10) textToSpeak = "Voice Typing";

        // Normal Labels
        if (textToSpeak == null && key.label != null) textToSpeak = key.label.toString();
        if (textToSpeak == null && key.text != null) textToSpeak = key.text.toString();

        if (textToSpeak != null) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(textToSpeak);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }

    // Key နှိပ်လိုက်သောအခါ (Touch or TalkBack Lift)
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
            case -101: // Next Language (Code အသစ်)
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
                // Smart Reordering
                if (isShanOrMyanmar() && handleSmartReordering(ic, primaryCode)) return;
                
                char code = (char) primaryCode;
                ic.commitText(String.valueOf(code), 1);
                
                // Auto Unshift
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

