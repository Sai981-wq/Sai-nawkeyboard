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

class SaiNawKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private lateinit var keyboardView: KeyboardView
    
    // Keyboards
    private lateinit var qwertyKeyboard: Keyboard
    private lateinit var qwertyShiftKeyboard: Keyboard
    private lateinit var myanmarKeyboard: Keyboard
    private lateinit var myanmarShiftKeyboard: Keyboard
    private lateinit var shanKeyboard: Keyboard
    private lateinit var shanShiftKeyboard: Keyboard
    
    private lateinit var currentKeyboard: Keyboard
    private var isCaps = false 
    private lateinit var accessibilityManager: AccessibilityManager
    
    // TalkBack Variables
    private var lastAnnouncedIndex = -1
    private var lastTouchX = 0
    private var lastTouchY = 0

    override fun onCreateInputView(): View {
        val layout = layoutInflater.inflate(R.layout.input_view, null)
        keyboardView = layout.findViewById(R.id.keyboard_view)
        accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager

        // XML များ Load လုပ်ခြင်း
        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        qwertyShiftKeyboard = Keyboard(this, R.xml.qwerty_shift)
        myanmarKeyboard = Keyboard(this, R.xml.myanmar)
        myanmarShiftKeyboard = Keyboard(this, R.xml.myanmar_shift)
        shanKeyboard = Keyboard(this, R.xml.shan)
        shanShiftKeyboard = Keyboard(this, R.xml.shan_shift)

        // Default Keyboard
        currentKeyboard = qwertyKeyboard
        keyboardView.keyboard = currentKeyboard
        keyboardView.setOnKeyboardActionListener(this)
        
        // TalkBack Touch Handler (အရေးအကြီးဆုံးအပိုင်း)
        keyboardView.setOnTouchListener { _, event ->
            handleTouch(event)
        }
        return layout
    }

    // TalkBack အတွက် Touch Logic (ပြင်ဆင်ထားသည်)
    private fun handleTouch(event: MotionEvent): Boolean {
        // TalkBack ပိတ်ထားရင် မူလအတိုင်း အလုပ်လုပ်မယ်
        if (!accessibilityManager.isEnabled) return keyboardView.onTouchEvent(event)
        
        val action = event.action
        val touchX = event.x.toInt()
        val touchY = event.y.toInt()
        
        // Gap တွေပါ မကျန်ရအောင် Nearest Key ကို ရှာမယ်
        val keyIndex = getNearestKeyIndex(touchX, touchY)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (keyIndex != -1) {
                    lastAnnouncedIndex = keyIndex
                    playHaptic()
                    announceKeyText(currentKeyboard.keys[keyIndex])
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // လက်ရွှေ့တဲ့အခါ Key ပြောင်းသွားရင် အသံထွက်မယ်
                if (keyIndex != -1 && keyIndex != lastAnnouncedIndex) {
                    lastAnnouncedIndex = keyIndex
                    playHaptic()
                    announceKeyText(currentKeyboard.keys[keyIndex])
                }
            }
            MotionEvent.ACTION_UP -> {
                // လက်ကြွလိုက်မှ စာရိုက်မယ် (Lift-to-type)
                if (keyIndex != -1) {
                    val key = currentKeyboard.keys[keyIndex]
                    if (key.label != null || key.codes[0] < 0) {
                        // Special Keys (Shift, Delete, Enter etc)
                        onKey(key.codes[0], null)
                    } else {
                        // Normal Text
                        onText(key.text ?: key.label)
                        // Normal Key ဆိုရင် Code အနေနဲ့ပါ ပို့မယ် (For cleanup)
                        if (key.codes.isNotEmpty()) {
                            onKey(key.codes[0], null)
                        }
                    }
                }
                lastAnnouncedIndex = -1
            }
        }
        return true
    }

    // အနီးဆုံး Key ကို ရှာပေးမည့် Function (Gap ပြဿနာဖြေရှင်းရန်)
    private fun getNearestKeyIndex(x: Int, y: Int): Int {
        val keys = currentKeyboard.keys
        
        // ၁။ အရင်ဆုံး တည့်တည့်ထိမထိ စစ်မယ် (Direct Hit)
        for (i in keys.indices) {
            if (keys[i].isInside(x, y)) return i
        }

        // ၂။ တည့်တည့်မထိရင် အနီးဆုံးကို ရှာမယ် (Distance Check)
        var closestIndex = -1
        var minDistance = Double.MAX_VALUE
        val threshold = 100 // Pixel အကွာအဝေး (လိုအပ်ရင် လျှော့နိုင်/တိုးနိုင်)

        for (i in keys.indices) {
            val key = keys[i]
            val keyCenterX = key.x + key.width / 2
            val keyCenterY = key.y + key.height / 2
            
            // Pythagoras Theorem နဲ့ အကွာအဝေး တွက်ခြင်း
            val dist = Math.sqrt(Math.pow((x - keyCenterX).toDouble(), 2.0) + Math.pow((y - keyCenterY).toDouble(), 2.0))
            
            if (dist < minDistance && dist < threshold) {
                minDistance = dist
                closestIndex = i
            }
        }
        return closestIndex
    }

    private fun announceKeyText(key: Keyboard.Key) {
        if (!accessibilityManager.isEnabled) return
        
        var textToSpeak = key.label?.toString()
        
        // Special Codes for TalkBack
        when (key.codes[0]) {
            -5 -> textToSpeak = "Delete"
            -1 -> textToSpeak = if (isCaps) "Shift On" else "Shift"
            32 -> textToSpeak = "Space"
            -4 -> textToSpeak = "Enter"
            -2 -> textToSpeak = "Symbols"
            -3 -> textToSpeak = "Next Language" // အစ်ကိုလိုချင်တဲ့အတိုင်း
            -10 -> textToSpeak = "Voice Typing"
        }

        // Label မရှိရင် Output Text ကို ဖတ်မယ်
        if (textToSpeak == null && key.text != null) {
            textToSpeak = key.text.toString()
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
            -1 -> { // Shift Logic
                isCaps = !isCaps
                updateKeyboardLayout() // Keyboard ပြောင်းပြီး Refresh လုပ်မယ်
            }
            -3 -> { // Language Switch Logic
                changeLanguage()
            }
            -4 -> { // Enter Logic
                val event = currentInputEditorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
                when (event) {
                    EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO -> sendDefaultEditorAction(true)
                    else -> inputConnection.commitText("\n", 1)
                }
            }
            -5 -> inputConnection.deleteSurroundingText(1, 0)
            0 -> {} // Ignore dummy codes
            else -> {
                // Smart Reordering (Logic အဟောင်းအတိုင်း)
                 if (isShanOrMyanmar() && handleSmartReordering(inputConnection, primaryCode)) {
                    return 
                }

                val charCode = primaryCode.toChar()
                inputConnection.commitText(charCode.toString(), 1)

                // Shift နှိပ်ပြီး တစ်လုံးရိုက်ပြီးရင် ပြန်ဖြုတ်မယ် (Auto Unshift)
                if (isCaps) {
                    isCaps = false
                    updateKeyboardLayout()
                }
            }
        }
    }

    private fun changeLanguage() {
        currentKeyboard = when (currentKeyboard) {
            qwertyKeyboard, qwertyShiftKeyboard -> myanmarKeyboard
            myanmarKeyboard, myanmarShiftKeyboard -> shanKeyboard
            shanKeyboard, shanShiftKeyboard -> qwertyKeyboard
            else -> qwertyKeyboard
        }
        isCaps = false // ဘာသာပြောင်းရင် Shift ဖြုတ်မယ်
        keyboardView.keyboard = currentKeyboard // View Update
        
        // TalkBack သမားကို ဘာသာစကားပြောင်းကြောင်း အသိပေးမယ်
        val langName = when(currentKeyboard) {
            myanmarKeyboard -> "Myanmar"
            shanKeyboard -> "Shan"
            else -> "English"
        }
        speakSystem(langName)
    }

    private fun updateKeyboardLayout() {
        // Caps အဖွင့်/အပိတ် ပေါ်မူတည်ပြီး Keyboard ရွေးမယ်
        if (isCaps) {
            if (currentKeyboard == qwertyKeyboard) currentKeyboard = qwertyShiftKeyboard
            else if (currentKeyboard == myanmarKeyboard) currentKeyboard = myanmarShiftKeyboard
            else if (currentKeyboard == shanKeyboard) currentKeyboard = shanShiftKeyboard
        } else {
            if (currentKeyboard == qwertyShiftKeyboard) currentKeyboard = qwertyKeyboard
            else if (currentKeyboard == myanmarShiftKeyboard) currentKeyboard = myanmarKeyboard
            else if (currentKeyboard == shanShiftKeyboard) currentKeyboard = shanKeyboard
        }
        // အရေးကြီးဆုံးလိုင်း (View ကို Refresh လုပ်ခြင်း)
        keyboardView.keyboard = currentKeyboard 
        keyboardView.invalidateAllKeys()
    }
    
    private fun isShanOrMyanmar(): Boolean {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard
    }

    // Smart Reordering Logic (အစ်ကို့ Code အတိုင်း)
    private fun handleSmartReordering(ic: InputConnection, primaryCode: Int): Boolean {
        // ု + ိ -> ိ + ု
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
        // ့ + ် -> ် + ့
        if (primaryCode == 4154) { // Code 4154 is 'tap' (check XML)
             // Logic here depends on exact codes used in XML
             // Provided XML uses 4154 for 'tap' in some, check usage
        }
        // ေ / ႄ + ဗျည်း -> ဗျည်း + ေ / ႄ
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

    override fun onText(text: CharSequence?) {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(text, 1)
        if (isCaps) {
            isCaps = false
            updateKeyboardLayout()
        }
    }
    
    // Unused methods
    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}

