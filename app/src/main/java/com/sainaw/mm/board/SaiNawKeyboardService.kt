package com.sainaw.mm.board

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.util.concurrent.Executors

class SaiNawKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private lateinit var keyboardView: KeyboardView
    private lateinit var qwertyKeyboard: Keyboard
    private lateinit var myanmarKeyboard: Keyboard
    private lateinit var shanKeyboard: Keyboard
    private lateinit var shanShiftKeyboard: Keyboard
    
    private lateinit var currentKeyboard: Keyboard
    private var isCaps = false 
    private lateinit var accessibilityManager: AccessibilityManager
    private var lastAnnouncedIndex = -1

    override fun onCreateInputView(): View {
        val layout = layoutInflater.inflate(R.layout.input_view, null)
        keyboardView = layout.findViewById(R.id.keyboard_view)
        accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager

        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        myanmarKeyboard = Keyboard(this, R.xml.myanmar)
        shanKeyboard = Keyboard(this, R.xml.shan)
        shanShiftKeyboard = Keyboard(this, R.xml.shan_shift)

        currentKeyboard = qwertyKeyboard
        keyboardView.keyboard = currentKeyboard
        keyboardView.setOnKeyboardActionListener(this)
        
        keyboardView.setOnTouchListener { _, event ->
            handleTouch(event)
        }
        return layout
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (!accessibilityManager.isEnabled) return keyboardView.onTouchEvent(event)
        
        val action = event.action
        val touchX = event.x.toInt()
        val touchY = event.y.toInt()
        val keyIndex = getKeyIndex(touchX, touchY)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (keyIndex != -1 && keyIndex != lastAnnouncedIndex) {
                    val key = currentKeyboard.keys[keyIndex]
                    playHaptic()
                    announceKeyText(key)
                    lastAnnouncedIndex = keyIndex
                }
            }
            MotionEvent.ACTION_UP -> {
                if (keyIndex != -1) {
                    val key = currentKeyboard.keys[keyIndex]
                    if (key.text != null) {
                        onText(key.text)
                    } else {
                        val code = key.codes[0]
                        if (code == -10) startVoiceInput() else {
                            onKey(code, null)
                            onRelease(code)
                        }
                    }
                }
                lastAnnouncedIndex = -1
            }
        }
        return true
    }

    override fun onText(text: CharSequence?) {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(text, 1)
        
        if (currentKeyboard == shanShiftKeyboard) {
            isCaps = false
            currentKeyboard = shanKeyboard
            keyboardView.keyboard = currentKeyboard
        }
    }

    private fun getKeyIndex(x: Int, y: Int): Int {
        val keys = currentKeyboard.keys
        for (i in keys.indices) {
            if (keys[i].isInside(x, y)) return i
        }
        return -1
    }

    private fun announceKeyText(key: Keyboard.Key) {
        if (!accessibilityManager.isEnabled) return
        var textToSpeak = key.label?.toString()
        if (textToSpeak == null && key.text != null) textToSpeak = key.text.toString()
        
        when (key.codes[0]) {
            -5 -> textToSpeak = "Delete"
            -1 -> textToSpeak = "Shift"
            32 -> textToSpeak = "Space"
            -4 -> textToSpeak = "Enter"
            -2 -> textToSpeak = "Numbers"
            -3 -> textToSpeak = "Language"
            -10 -> textToSpeak = "Voice Typing"
        }

        if (textToSpeak != null) {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            event.text.add(textToSpeak)
            accessibilityManager.sendAccessibilityEvent(event)
        }
    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val inputConnection = currentInputConnection ?: return

        when (primaryCode) {
            -10 -> startVoiceInput()
            -1 -> { 
                isCaps = !isCaps
                if (currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard) {
                    currentKeyboard = if (isCaps) shanShiftKeyboard else shanKeyboard
                    keyboardView.keyboard = currentKeyboard
                } else {
                    currentKeyboard.isShifted = isCaps
                    keyboardView.invalidateAllKeys()
                }
            }
            -3 -> { 
                currentKeyboard = when (currentKeyboard) {
                    qwertyKeyboard -> myanmarKeyboard
                    myanmarKeyboard -> shanKeyboard
                    shanKeyboard, shanShiftKeyboard -> qwertyKeyboard
                    else -> qwertyKeyboard
                }
                isCaps = false
                keyboardView.keyboard = currentKeyboard
                val langName = when(currentKeyboard) {
                    myanmarKeyboard -> "Myanmar"
                    shanKeyboard -> "Shan"
                    else -> "English"
                }
                speakSystem(langName)
            }
            -4 -> {
                val event = currentInputEditorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
                when (event) {
                    EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO -> sendDefaultEditorAction(true)
                    else -> inputConnection.commitText("\n", 1)
                }
            }
            -5 -> inputConnection.deleteSurroundingText(1, 0)
            0 -> {} 
            else -> {
                // ၁။ Smart Reordering စနစ် (ရှမ်း နှင့် မြန်မာ အတွက်)
                if ((currentKeyboard == myanmarKeyboard || currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard) && 
                    handleSmartReordering(inputConnection, primaryCode)) {
                    return 
                }

                // ၂။ ပုံမှန် စာရိုက်ခြင်း
                var charCode = primaryCode.toChar()
                if (Character.isLetter(charCode) && isCaps && currentKeyboard == qwertyKeyboard) {
                    charCode = Character.toUpperCase(charCode)
                }
                inputConnection.commitText(charCode.toString(), 1)
            }
        }
    }

    // Smart Reordering Function
    private fun handleSmartReordering(ic: InputConnection, primaryCode: Int): Boolean {
        
        // (က) ု + ိ => ိ + ု
        // 4141 = ိ (I)
        if (primaryCode == 4141) { 
            val beforeText = ic.getTextBeforeCursor(1, 0)
            if (!beforeText.isNullOrEmpty()) {
                val preChar = beforeText[0].toString()
                if (preChar == "ု" || preChar == "ူ") {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText("ိ", 1) 
                    ic.commitText(preChar, 1)
                    return true 
                }
            }
        }

        // (ခ) ် + ့ => ့ + ် (အသတ် + အောက်မြစ် => အောက်မြစ် + အသတ်)
        // 4151 = ့ (Aukmyit)
        if (primaryCode == 4151) {
            val beforeText = ic.getTextBeforeCursor(1, 0)
            if (!beforeText.isNullOrEmpty()) {
                val preChar = beforeText[0].toString()
                // ရှေ့ကစာလုံးက '်' (4154) ဖြစ်နေရင် နေရာလဲမယ်
                if (preChar == "်") {
                    ic.deleteSurroundingText(1, 0) // အသတ်ကို ဖျက်မယ်
                    ic.commitText("့", 1) // အောက်မြစ် အရင်ထည့်မယ်
                    ic.commitText("်", 1) // အသတ် နောက်မှထည့်မယ်
                    return true
                }
            }
        }

        // (ဂ) ေ / ႄ + ဗျည်း => ဗျည်း + ေ / ႄ
        val isConsonant = (primaryCode in 4096..4255)
        
        if (isConsonant) {
            val beforeText = ic.getTextBeforeCursor(1, 0)
            if (!beforeText.isNullOrEmpty()) {
                val preChar = beforeText[0].toString()
                if (preChar == "ေ" || preChar == "ႄ") {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(primaryCode.toChar().toString(), 1)
                    ic.commitText(preChar, 1)
                    return true
                }
            }
        }
        return false
    }

    private fun startVoiceInput() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            speakSystem("Voice typing not supported")
        }
    }
    
    private fun speakSystem(text: String) {
        if (accessibilityManager.isEnabled) {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            event.text.add(text)
            accessibilityManager.sendAccessibilityEvent(event)
        }
    }

    private fun playHaptic() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) vibrator.vibrate(10)
        } catch (e: Exception) { }
    }

    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}

