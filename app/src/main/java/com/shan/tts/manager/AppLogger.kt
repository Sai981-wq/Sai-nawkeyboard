package com.shan.tts.manager

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {
    private val logs = CopyOnWriteArrayList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val threadName = Thread.currentThread().name
        val timestamp = dateFormat.format(Date())
        val fullLog = "$timestamp [$threadName] [$tag] $message"
        
        Log.d("CherryTTS", "[$tag] $message") // Logcat တွင်ကြည့်ရန်
        logs.add(0, fullLog)
        
        if (logs.size > 1000) logs.removeAt(logs.size - 1)
    }

    fun getAllLogs(): String = logs.joinToString("\n")
    fun clear() { logs.clear() }
}

