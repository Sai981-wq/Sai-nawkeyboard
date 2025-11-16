package com.sainaw.mm.board

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvSelectedFile: TextView

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.also { uri ->
                saveDictionaryUri(uri)
                tvSelectedFile.text = "Selected: ${uri.lastPathSegment}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tvSelectedFile = findViewById(R.id.tv_selected_file)
        
        updateSelectedFileText()

        findViewById<Button>(R.id.btn_choose_dictionary).setOnClickListener {
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        filePickerLauncher.launch(intent)
    }

    private fun saveDictionaryUri(uri: Uri) {
        val prefs = getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("dictionary_uri", uri.toString()).apply()
    }
    
    private fun updateSelectedFileText() {
        val prefs = getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE)
        val uriString = prefs.getString("dictionary_uri", null)
        if (uriString != null) {
            tvSelectedFile.text = "Selected: ${Uri.parse(uriString).lastPathSegment}"
        } else {
            tvSelectedFile.text = "No file selected."
        }
    }
}
