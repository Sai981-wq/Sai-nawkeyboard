package com.shan.tts.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LogViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI ကို Code နဲ့ပဲ ရေးလိုက်ပါမယ် (XML မလိုအောင်)
        val scrollView = ScrollView(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        val btnRefresh = Button(this).apply { text = "Refresh Log" }
        val btnCopy = Button(this).apply { text = "Copy All Logs" }
        val btnClear = Button(this).apply { text = "Clear Logs" }
        val tvLogs = TextView(this).apply { textSize = 14f }

        layout.addView(btnRefresh)
        layout.addView(btnCopy)
        layout.addView(btnClear)
        layout.addView(tvLogs)
        scrollView.addView(layout)
        setContentView(scrollView)

        fun refresh() {
            tvLogs.text = AppLogger.getAllLogs()
        }

        btnRefresh.setOnClickListener { refresh() }
        
        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("TTS Logs", tvLogs.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        btnClear.setOnClickListener {
            AppLogger.clear()
            refresh()
        }

        refresh()
    }
}
