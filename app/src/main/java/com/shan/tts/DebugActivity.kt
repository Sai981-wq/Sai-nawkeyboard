package com.shan.tts

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class DebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this)

        val message = "Welcome to Shan TTS\n\n" +
                      "Created by: Sai Naw\n\n" +
                      "This engine is dedicated to the Shan blind community, \n" +
                      "to make technology accessible in the Shan language.\n\n" +
                      "Your feedback is essential for improvement. \n" +
                      "If you find any bugs or have suggestions for new words, \n" +
                      "please contact:\n" +
                      "sainaw1331@gmail.com\n\n" +
                      "This engine will be continuously updated.\n" +
                      "Thank you for your support."

        textView.text = message

        textView.textSize = 18f
        textView.setPadding(80, 80, 80, 80)
        textView.gravity = Gravity.CENTER

        setContentView(textView)
    }
}

