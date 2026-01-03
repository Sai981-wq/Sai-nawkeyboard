package com.shan.tts.manager

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {
    private const val TAG = "CherryTTS_Debug"
    
    // Thread-safe list to store logs in memory
    private val logHistory = CopyOnWriteArrayList<String>()
    
    // Maximum number of lines to keep in memory
    private const val MAX_LOGS = 1500 
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Standard Debug Log
    fun log(message: String) {
        val entry = formatLog("D", message)
        Log.d(TAG, message)
        addToHistory(entry)
    }

    // Error Log
    fun error(message: String, e: Throwable? = null) {
        val fullMsg = if (e != null) "$message: ${e.message}\n${Log.getStackTraceString(e)}" else message
        val entry = formatLog("E", fullMsg)
        Log.e(TAG, fullMsg)
        addToHistory(entry)
    }

    private fun formatLog(type: String, msg: String): String {
        val time = dateFormat.format(Date())
        return "$time [$type] $msg"
    }

    private fun addToHistory(entry: String) {
        logHistory.add(entry)
        
        // Remove old logs if limit reached to save memory
        if (logHistory.size > MAX_LOGS) {
            // Remove the first 100 logs at once to avoid frequent resizing
            try {
                if (logHistory.isNotEmpty()) {
                    logHistory.subList(0, 100).clear()
                }
            } catch (e: Exception) {
                // Ignore modification errors
            }
        }
    }

    // Called by LogViewerActivity to display logs
    fun getAllLogs(): String {
        return if (logHistory.isEmpty()) {
            "No logs yet..."
        } else {
            // Join lines for TextView
            logHistory.joinToString("\n\n")
        }
    }

    // Clear logs from memory
    fun clear() {
        logHistory.clear()
        log("Logs cleared by user.")
    }
}

