package com.shan.tts.manager

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {
    private val logs = CopyOnWriteArrayList<String>()
    private val dateFormat = SimpleDateFormat("mm:ss.SSS", Locale.US) // မိနစ်:စက္ကန့်.မီလီစက္ကန့်

    fun log(tag: String, message: String) {
        // Thread Name ပါထည့်မှတ်မယ် (Debugging တွက်အရေးကြီးတယ်)
        val threadName = Thread.currentThread().name
        val timestamp = dateFormat.format(Date())
        val entry = "$timestamp [$threadName] [$tag] $message"
        
        Log.d("CherryTTS_$tag", message)
        logs.add(0, entry)
        
        // Memory မပွအောင် Log 500 ထိပဲထားမယ်
        if (logs.size > 500) logs.removeAt(logs.size - 1)
    }

    fun getAllLogs(): String = logs.joinToString("\n")
    fun clear() { logs.clear() }
}

