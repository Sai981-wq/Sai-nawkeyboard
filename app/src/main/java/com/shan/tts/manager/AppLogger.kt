package com.shan.tts.manager

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {
    private val logs = CopyOnWriteArrayList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(tag: String, message: String) {
        val entry = "${dateFormat.format(Date())} [$tag] $message"
        Log.d(tag, message)
        logs.add(0, entry)
        if (logs.size > 300) logs.removeAt(logs.size - 1)
    }

    fun getAllLogs(): String = logs.joinToString("\n\n")
    fun clear() { logs.clear() }
}

