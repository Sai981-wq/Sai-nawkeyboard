package com.shan.tts.manager

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "CherryTTS_Debug"
    private val logBuilder = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Log with Thread Name (To debug concurrency)
    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val threadName = Thread.currentThread().name
        val finalMsg = "$timestamp [$threadName] : $message"
        
        Log.d(TAG, finalMsg)
        appendBuffer(finalMsg)
    }

    fun error(message: String, e: Exception? = null) {
        val timestamp = dateFormat.format(Date())
        val threadName = Thread.currentThread().name
        val sb = StringBuilder()
        sb.append("$timestamp [$threadName] [ERROR] : $message")
        
        if (e != null) {
            sb.append("\nCause: ${e.message}")
            sb.append("\nTrace: ${Log.getStackTraceString(e)}")
        }
        
        val finalMsg = sb.toString()
        Log.e(TAG, finalMsg)
        appendBuffer(finalMsg)
    }

    private fun appendBuffer(msg: String) {
        synchronized(this) {
            logBuilder.append(msg).append("\n")
            // Limit buffer to avoid OutOfMemory during heavy logging
            if (logBuilder.length > 150000) {
                logBuilder.delete(0, logBuilder.length - 100000)
            }
        }
    }

    fun getAllLogs(): String {
        synchronized(this) {
            return logBuilder.toString()
        }
    }

    fun clear() {
        synchronized(this) {
            logBuilder.setLength(0)
        }
    }
}

