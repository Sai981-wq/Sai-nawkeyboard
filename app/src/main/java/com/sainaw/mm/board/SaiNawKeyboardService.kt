package com.sainaw.mm.board

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.media.AudioManager
import android.net.Uri
import android.os.Vibrator
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class SaiNawKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private lateinit var keyboardView: KeyboardView
    
    private lateinit var qwertyKeyboard: Keyboard
    private lateinit var symbolsKeyboard: Keyboard
    private lateinit var myanmarKeyboard: Keyboard
    private lateinit var shanKeyboard: Keyboard
    
    private lateinit var currentKeyboard: Keyboard
    private var isCaps = false 

    private lateinit var accessibilityManager: AccessibilityManager

    private lateinit var candidatesContainer: LinearLayout
    private var currentWord = StringBuilder()
    
    @Volatile
    private var dictionary = listOf<String>()
    
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreateInputView(): View {
        val layout = layoutInflater.inflate(R.layout.input_view, null)
        keyboardView = layout.findViewById(R.id.keyboard_view)
        candidatesContainer = layout.findViewById(R.id.candidates_container)

        accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager

        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        symbolsKeyboard = Keyboard(this, R.xml.symbols)
        myanmarKeyboard = Keyboard(this, R.xml.myanmar)
        shanKeyboard = Keyboard(this, R.xml.shan)

        currentKeyboard = qwertyKeyboard
        keyboardView.keyboard = currentKeyboard
        keyboardView.setOnKeyboardActionListener(this)
        
        loadDictionary()

        return layout
    }

    override fun onPress(primaryCode: Int) {
        if (primaryCode == 0) return 

        playHaptic() 
        playClick()  

        val key = findKey(primaryCode)
        if (key != null) {
            announceKey(key)
        }
    }

    override fun onRelease(primaryCode: Int) {
        if (primaryCode > 0) {
            val inputConnection = currentInputConnection ?: return
            
            var charToCommit = primaryCode.toChar()
            
            if (isCaps && charToCommit.isLetter()) {
                charToCommit = charToCommit.toUpperCase()
                isCaps = false 
            }
            
            inputConnection.commitText(charToCommit.toString(), 1)
            
            if (primaryCode == 32) { // Space
                currentWord.clear()
            } else {
                currentWord.append(charToCommit)
            }
            updateSuggestions()
        }
    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        if (primaryCode >= 0) return
        val inputConnection = currentInputConnection ?: return

        when (primaryCode) {
            -1 -> {
                isCaps = true 
            }
            -2 -> {
                currentKeyboard = symbolsKeyboard
                keyboardView.keyboard = currentKeyboard
            }
            -3 -> {
                currentKeyboard = when (currentKeyboard) {
                    qwertyKeyboard -> myanmarKeyboard
                    myanmarKeyboard -> shanKeyboard
                    shanKeyboard -> qwertyKeyboard
                    else -> qwertyKeyboard
                }
                keyboardView.keyboard = currentKeyboard
                isCaps = false
            }
            -4 -> {
                val event = currentInputEditorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
                when (event) {
                    EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO -> sendDefaultEditorAction(true)
                    else -> inputConnection.commitText("\n", 1)
                }
                currentWord.clear()
                updateSuggestions()
            }
            -5 -> {
                inputConnection.deleteSurroundingText(1, 0)
                if (currentWord.isNotEmpty()) {
                    currentWord.deleteCharAt(currentWord.length - 1)
                }
                updateSuggestions()
            }
            -6 -> {
                currentKeyboard = qwertyKeyboard
                keyboardView.keyboard = currentKeyboard
            }
            -7 -> { }
        }
    }

    private fun findKey(primaryCode: Int): Keyboard.Key? {
        for (key in currentKeyboard.keys) {
            if (key.codes[0] == primaryCode) {
                return key
            }
        }
        return null
    }

    private fun announceKey(key: Keyboard.Key) {
        if (!accessibilityManager.isEnabled) return
        val label = key.label ?: return 

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
        event.packageName = packageName
        event.className = javaClass.name
        event.text.add(label) 

        accessibilityManager.sendAccessibilityEvent(event)
    }

    private fun loadDictionary() {
        executor.execute {
            val prefs = getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE)
            val uriString = prefs.getString("dictionary_uri", null)
            
            if (uriString == null) {
                dictionary = listOf()
                return@execute
            }

            val words = mutableListOf<String>()
            try {
                val uri = Uri.parse(uriString)
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val word = line?.trim()
                            if (!word.isNullOrEmpty()) {
                                words.add(word)
                            }
                        }
                    }
                }
                dictionary = words
            } catch (e: Exception) {
                e.printStackTrace()
                dictionary = listOf()
            }
        }
    }

    private fun updateSuggestions() {
        candidatesContainer.removeAllViews()

        if (currentWord.isEmpty()) {
            return
        }

        val suggestions = dictionary.filter { 
            it.startsWith(currentWord.toString(), ignoreCase = true) 
        }

        for (word in suggestions) {
            val textView = TextView(this)
            textView.text = word
            textView.setTextColor(0xFFFFFFFF.toInt())
            textView.setPadding(16, 16, 16, 16)
            textView.isClickable = true
            
            textView.setOnClickListener {
                currentInputConnection?.commitText(word + " ", 1)
                currentWord.clear()
                updateSuggestions()
            }
            candidatesContainer.addView(textView)
        }
    }

    private fun playHaptic() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(5)
            }
        } catch (e: Exception) { }
    }

    private fun playClick() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.5f)
        } catch (e: Exception) { }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    override fun onText(text: CharSequence?) { }
    override fun swipeLeft() { }
    override fun swipeRight() { }
    override fun swipeDown() { }
    override fun swipeUp() { }
}

