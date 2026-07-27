package com.teleflix.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

object TeleflixLogger {
    private const val MAX_LOG_ENTRIES = 800
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String, isError: Boolean = false) {
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] [$tag] $message"
        
        if (isError) {
            Log.e(tag, message)
        } else {
            Log.d(tag, message)
        }

        logQueue.add(entry)
        while (logQueue.size > MAX_LOG_ENTRIES) {
            logQueue.poll()
        }
    }

    fun getFormattedLogs(): String {
        val list = logQueue.toList()
        if (list.isEmpty()) {
            return "--- No Diagnostic Logs Recorded Yet ---"
        }
        return list.joinToString("\n")
    }

    fun copyLogsToClipboard(context: Context): Boolean {
        return try {
            val logs = getFormattedLogs()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Teleflix Diagnostic Logs", logs)
            clipboard?.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.e("TeleflixLogger", "Failed to copy logs: ${e.message}")
            false
        }
    }

    fun clearLogs() {
        logQueue.clear()
        log("TeleflixLogger", "Logs cleared by user")
    }
}
