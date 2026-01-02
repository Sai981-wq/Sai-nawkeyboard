package com.shan.tts.manager

import android.util.Log

object AppLogger {
    private const val TAG = "CherryTTS_Debug"

    fun log(message: String) {
        Log.d(TAG, message)
    }

    fun error(message: String, e: Throwable? = null) {
        if (e != null) {
            Log.e(TAG, "$message: ${e.message}", e)
        } else {
            Log.e(TAG, message)
        }
    }
}

