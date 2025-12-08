package com.shan.tts.manager

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogHistory {
    private val logs = ArrayList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Error အသစ်ဝင်လာရင် ထိပ်ဆုံးမှာ ထည့်မယ်
    fun add(msg: String) {
        val time = dateFormat.format(Date())
        val logEntry = "[$time] $msg"
        
        synchronized(logs) {
            logs.add(0, logEntry)
            // စာကြောင်း ၂၀၀ ထက်ကျော်ရင် အောက်ဆုံးက အဟောင်းတွေ ဖျက်မယ် (Memory မလေးအောင်)
            if (logs.size > 200) {
                logs.removeAt(logs.lastIndex)
            }
        }
    }

    // ရှိသမျှ Error အကုန်ပြန်ယူမယ်
    fun getAll(): String {
        synchronized(logs) {
            return logs.joinToString("\n\n")
        }
    }

    // ရှင်းထုတ်မယ်
    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
    }
}
