package com.shan.tts.manager

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {
    private const val TAG = "CherryTTS_Debug"
    private val logHistory = CopyOnWriteArrayList<String>()
    private const val MAX_LOGS = 1500
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
        if (logHistory.size > MAX_LOGS) {
            logHistory.removeAt(0)
        }
    }

    fun getAllLogs(): String {
        return if (logHistory.isEmpty()) "No logs yet..." else logHistory.joinToString("\n\n")
    }

    fun clear() {
        logHistory.clear()
        log("Logs cleared.")
    }
}

