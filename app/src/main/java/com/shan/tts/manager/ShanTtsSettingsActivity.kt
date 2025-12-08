package com.shan.tts.manager

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class ShanTtsSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView = TextView(this)
        textView.text = "Shan TTS တွင် လက်ရှိ ပြင်ဆင်ရန် အချက်အလက်များ မရှိသေးပါ။"
        textView.setPadding(60, 60, 60, 60)
        textView.textSize = 18f
        setContentView(textView)
    }
}
