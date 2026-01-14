package com.shan.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

class InstallVoiceData : Activity() {
    private val TAG = "ShanInstallVoiceData"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "InstallVoiceData started")

        try {
            // Voice data is already included in assets
            setResult(Activity.RESULT_OK)
        } catch (e: Exception) {
            Log.e(TAG, "InstallVoiceData error", e)
            setResult(Activity.RESULT_CANCELED)
        } finally {
            finish()
        }
    }
}