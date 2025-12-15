package com.shan.tts.manager

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "CherryTTS_Monitor"
    private val logBuilder = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val finalMsg = "$timestamp : $message"
        Log.d(TAG, message)
        appendBuffer(finalMsg)
    }

    fun error(message: String, e: Exception? = null) {
        val timestamp = dateFormat.format(Date())
        val sb = StringBuilder()
        sb.append("$timestamp [ERROR] : $message")
        
        if (e != null) {
            sb.append("\nExample Cause: ${e.message}")
            sb.append("\nStack Trace:\n${Log.getStackTraceString(e)}")
        }
        
        val finalMsg = sb.toString()
        Log.e(TAG, finalMsg)
        appendBuffer(finalMsg)
    }

    private fun appendBuffer(msg: String) {
        synchronized(this) {
            logBuilder.append(msg).append("\n\n")
            if (logBuilder.length > 100000) {
                logBuilder.delete(0, logBuilder.length - 80000)
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

