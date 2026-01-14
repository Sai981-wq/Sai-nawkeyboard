package com.shan.tts

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

object LogUtils {
    private const val TAG = "ShanTtsLog"
    private val logExecutor = Executors.newSingleThreadExecutor()
    private var logFile: File? = null
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "shan_tts_crash_log_$timeStamp.txt"
            
            val downloadsDir = File(context.getExternalFilesDir(null), "ShanTTS_Logs")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            
            logFile = File(downloadsDir, fileName)
            isInitialized = true
            
            logToFile("=== Shan TTS Log Started ===")
            logToFile("Timestamp: ${Date()}")
            logToFile("App Version: ${getAppVersion(context)}")
            logToFile("Android Version: ${android.os.Build.VERSION.RELEASE}")
            logToFile("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            logToFile("=================================")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize log system: ${e.message}")
        }
    }

    fun logToFile(message: String) {
        logExecutor.execute {
            try {
                if (!isInitialized || logFile == null) return@execute
                
                val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val logMessage = "[$timestamp] $message\n"
                
                FileOutputStream(logFile!!, true).use { fos ->
                    fos.write(logMessage.toByteArray())
                }
                
                Log.d(TAG, message)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log: ${e.message}")
            }
        }
    }

    fun logException(tag: String, message: String, exception: Throwable?) {
        val exceptionText = if (exception != null) {
            "Exception: ${exception.javaClass.simpleName}\n" +
            "Message: ${exception.message}\n" +
            "Stack Trace:\n${getStackTraceString(exception)}"
        } else {
            "No exception details"
        }
        
        val fullMessage = "$tag: $message\n$exceptionText"
        logToFile(fullMessage)
    }

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    fun getCurrentLogFile(): File? {
        return logFile
    }

    fun getLogDirectory(context: Context): File {
        return File(context.getExternalFilesDir(null), "ShanTTS_Logs")
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    fun getLogFiles(context: Context): List<File> {
        val logDir = getLogDirectory(context)
        return if (logDir.exists() && logDir.isDirectory) {
            logDir.listFiles()?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }

    fun getLatestLogFile(context: Context): File? {
        val logFiles = getLogFiles(context)
            .filter { it.name.startsWith("shan_tts_crash_log_") }
        
        return if (logFiles.isNotEmpty()) {
            logFiles.maxBy { it.lastModified() }
        } else {
            null
        }
    }
}