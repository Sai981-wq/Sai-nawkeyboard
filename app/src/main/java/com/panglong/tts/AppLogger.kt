package com.panglong.tts

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private var listener: ((String) -> Unit)? = null
    private val logHistory = StringBuilder()

    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val formattedMessage = "[$timestamp] $message\n"
        logHistory.append(formattedMessage)
        listener?.invoke(logHistory.toString())
    }

    fun observe(callback: (String) -> Unit) {
        listener = callback
        callback(logHistory.toString())
    }
}
