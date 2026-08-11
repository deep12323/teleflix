package com.teleflix.app

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

data class DownloadItem(
    val id: String,                  // Unique ID (e.g. fileId or chatId_messageId)
    val title: String,               // Movie / Episode title
    val fileName: String,            // Destination file name
    var fileId: Int,                 // TDLib File ID
    val chatId: Long = 0L,           // Telegram Chat ID
    val messageId: Long = 0L,        // Telegram Message ID
    val posterUrl: String = "",      // Poster image URL or empty
    val localPath: String = "",      // Local file path on disk
    var totalBytes: Long = 0L,       // Total size in bytes
    var downloadedBytes: Long = 0L,  // Downloaded bytes so far
    var speedBytesPerSec: Long = 0L, // Current download speed
    var status: DownloadStatus = DownloadStatus.QUEUED,
    val addedTime: Long = System.currentTimeMillis(),
    val isMultiPart: Boolean = false,
    val partFileIds: MutableList<Int> = mutableListOf(),
    val partChatIds: MutableList<Long> = mutableListOf(),
    val partMessageIds: MutableList<Long> = mutableListOf(),
    val partFileSizes: MutableList<Long> = mutableListOf(),
    val partFileNames: MutableList<String> = mutableListOf(),
    var currentPartIndex: Int = 0
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

    fun getFormattedSize(): String {
        if (totalBytes <= 0) return "Unknown size"
        val mb = totalBytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
    }

    fun getFormattedSpeed(): String {
        if (status != DownloadStatus.DOWNLOADING || speedBytesPerSec <= 0) return ""
        val kb = speedBytesPerSec / 1024.0
        return if (kb >= 1024) String.format("%.1f MB/s", kb / 1024.0) else String.format("%.0f KB/s", kb)
    }

    fun getFormattedEta(): String {
        if (status != DownloadStatus.DOWNLOADING || speedBytesPerSec <= 0L || totalBytes <= 0L) return ""
        val remainingBytes = (totalBytes - downloadedBytes).coerceAtLeast(0L)
        if (remainingBytes <= 0L) return "0s"
        val remainingSecs = remainingBytes / speedBytesPerSec
        val hours = remainingSecs / 3600
        val mins = (remainingSecs % 3600) / 60
        val secs = remainingSecs % 60
        return when {
            hours > 0 -> String.format(java.util.Locale.US, "%dh %02dm", hours, mins)
            mins > 0 -> String.format(java.util.Locale.US, "%02dm %02ds", mins, secs)
            else -> String.format(java.util.Locale.US, "%02ds", secs)
        }
    }
}

