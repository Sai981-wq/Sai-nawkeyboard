package com.shan.tts.manager

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {
    private val logs = CopyOnWriteArrayList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Log ထည့်ရန် Function
    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "$timestamp [$tag] $message"
        
        // Logcat မှာလည်းပြမယ်
        Log.d(tag, message)
        
        // List ထဲထည့်မယ် (အကြောင်းရေ ၅၀၀ ထက်မကျော်စေရ)
        logs.add(0, entry) 
        if (logs.size > 500) {
            logs.removeAt(logs.size - 1)
        }
    }

    // Log တွေကို ပြန်ယူရန်
    fun getAllLogs(): String {
        return logs.joinToString("\n\n")
    }

    // ရှင်းထုတ်ရန်
    fun clear() {
        logs.clear()
    }
}
