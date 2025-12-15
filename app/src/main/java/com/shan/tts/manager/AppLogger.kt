package com.shan.tts.manager

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "CherryTTS_Monitor"
    private val logBuilder = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Log များကို သိမ်းဆည်းခြင်း
    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val finalMsg = "$timestamp : $message"
        Log.d(TAG, message) // Logcat မှာလည်းပြမယ်
        appendBuffer(finalMsg) // ဖုန်း Screen ပေါ်ပြဖို့ သိမ်းမယ်
    }

    fun error(message: String, e: Exception? = null) {
        val timestamp = dateFormat.format(Date())
        val finalMsg = if (e != null) {
            "$timestamp [ERROR] : $message \n${Log.getStackTraceString(e)}"
        } else {
            "$timestamp [ERROR] : $message"
        }
        Log.e(TAG, message)
        appendBuffer(finalMsg)
    }

    private fun appendBuffer(msg: String) {
        synchronized(this) {
            logBuilder.append(msg).append("\n\n")
            // စာလုံးရေ ၅၀၀၀၀ ထက်ကျော်ရင် အရှေ့ကဟာတွေ ဖျက်မယ် (Memory မလေးအောင်)
            if (logBuilder.length > 50000) {
                logBuilder.delete(0, logBuilder.length - 40000)
            }
        }
    }

    // Activity ကနေ လှမ်းခေါ်မယ့် Function
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

