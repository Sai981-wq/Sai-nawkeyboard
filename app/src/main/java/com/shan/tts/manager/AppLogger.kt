package com.shan.tts.manager

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {
    // Thread-safe list to store logs
    private val logs = CopyOnWriteArrayList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(tag: String, message: String) {
        val entry = "${dateFormat.format(Date())} [$tag] $message"
        
        // Print to Android System Logcat
        Log.d(tag, message)
        
        // Add to our internal list for the UI
        logs.add(0, entry)
        
        // Keep only last 300 logs to save memory
        if (logs.size > 300) {
            logs.removeAt(logs.size - 1)
        }
    }

    fun getAllLogs(): String {
        return logs.joinToString("\n\n")
    }

    fun clear() {
        logs.clear()
    }
}
