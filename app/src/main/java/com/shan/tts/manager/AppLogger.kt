package com.shan.tts.manager

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {
    private const val TAG = "CherryTTS_Debug"
    
    // Thread-safe list to store logs
    private val logHistory = CopyOnWriteArrayList<String>()
    private const val MAX_LOGS = 1000 // Keep last 1000 lines
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(message: String) {
        Log.d(TAG, message)
        addToHistory("D", message)
    }

    fun error(message: String, e: Throwable? = null) {
        val fullMsg = if (e != null) "$message: ${e.message}\n${Log.getStackTraceString(e)}" else message
        Log.e(TAG, fullMsg)
        addToHistory("E", fullMsg)
    }

    private fun addToHistory(type: String, msg: String) {
        val time = dateFormat.format(Date())
        val entry = "$time [$type] $msg"
        
        logHistory.add(entry)
        
        // Trim old logs to prevent memory issues
        if (logHistory.size > MAX_LOGS) {
            logHistory.removeAt(0)
        }
    }

    // Called by LogViewerActivity
    fun getAllLogs(): String {
        return if (logHistory.isEmpty()) {
            "No logs yet..."
        } else {
            logHistory.joinToString("\n\n")
        }
    }

    // Called by LogViewerActivity (changed from clearLogs to clear)
    fun clear() {
        logHistory.clear()
        log("Logs cleared by user.")
    }
}

