package com.teleflix.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DownloadManager {
    private const val TAG = "DownloadManager"
    private const val PREFS_NAME = "teleflix_downloads_prefs"
    private const val KEY_DOWNLOADS = "downloads_json"

    private val downloadsMap = LinkedHashMap<String, DownloadItem>()
    private val _downloadsFlow = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloadsFlow: StateFlow<List<DownloadItem>> = _downloadsFlow.asStateFlow()

    private var lastSpeedCalcTime = System.currentTimeMillis()
    private var lastDownloadedBytesMap = HashMap<String, Long>()
    private var downloadLoopJob: Job? = null

    fun init(context: Context) {
        loadFromPrefs(context)
        if (hasActiveDownloads()) {
            startDownloadLoop(context)
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun loadFromPrefs(context: Context) {
        synchronized(downloadsMap) {
            downloadsMap.clear()
            val jsonStr = getPrefs(context).getString(KEY_DOWNLOADS, null)
            if (!jsonStr.isNullOrBlank()) {
                try {
                    val array = JSONArray(jsonStr)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val statusStr = obj.optString("status", DownloadStatus.QUEUED.name)
                        val status = runCatching { DownloadStatus.valueOf(statusStr) }.getOrDefault(DownloadStatus.QUEUED)
                        
                        val item = DownloadItem(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            fileName = obj.getString("fileName"),
                            fileId = obj.getInt("fileId"),
                            chatId = obj.optLong("chatId", 0L),
                            messageId = obj.optLong("messageId", 0L),
                            posterUrl = obj.optString("posterUrl", ""),
                            localPath = obj.optString("localPath", ""),
                            totalBytes = obj.optLong("totalBytes", 0L),
                            downloadedBytes = obj.optLong("downloadedBytes", 0L),
                            status = if (status == DownloadStatus.DOWNLOADING) DownloadStatus.PAUSED else status,
                            addedTime = obj.optLong("addedTime", System.currentTimeMillis())
                        )
                        downloadsMap[item.id] = item
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading downloads from prefs", e)
                }
            }
            updateFlow()
        }
    }

    private fun saveToPrefs(context: Context) {
        synchronized(downloadsMap) {
            try {
                val array = JSONArray()
                for (item in downloadsMap.values) {
                    val obj = JSONObject().apply {
                        put("id", item.id)
                        put("title", item.title)
                        put("fileName", item.fileName)
                        put("fileId", item.fileId)
                        put("chatId", item.chatId)
                        put("messageId", item.messageId)
                        put("posterUrl", item.posterUrl)
                        put("localPath", item.localPath)
                        put("totalBytes", item.totalBytes)
                        put("downloadedBytes", item.downloadedBytes)
                        put("status", item.status.name)
                        put("addedTime", item.addedTime)
                    }
                    array.put(obj)
                }
                getPrefs(context).edit().putString(KEY_DOWNLOADS, array.toString()).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving downloads to prefs", e)
            }
        }
    }

    private fun updateFlow() {
        _downloadsFlow.value = downloadsMap.values.toList().sortedByDescending { it.addedTime }
    }

    fun getStorageMode(context: Context): String {
        return getPrefs(context).getString("storage_mode", "app_storage") ?: "app_storage"
    }

    fun setStorageMode(context: Context, mode: String) {
        getPrefs(context).edit().putString("storage_mode", mode).apply()
    }

    fun getCustomPath(context: Context): String {
        return getPrefs(context).getString("custom_storage_path", "") ?: ""
    }

    fun setCustomPath(context: Context, path: String) {
        getPrefs(context).edit().putString("custom_storage_path", path.trim()).apply()
    }

    fun getActiveDownloadsDir(context: Context): File {
        val mode = getStorageMode(context)
        val dir = when (mode) {
            "public_downloads" -> {
                val pubDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Teleflix")
                if (!pubDir.exists()) pubDir.mkdirs()
                if (pubDir.exists()) pubDir else null
            }
            "custom" -> {
                val pathStr = getCustomPath(context)
                if (pathStr.isNotBlank()) {
                    val customDir = File(pathStr)
                    if (!customDir.exists()) customDir.mkdirs()
                    if (customDir.exists()) customDir else null
                } else null
            }
            else -> null
        }
        return dir ?: (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir)
    }

    fun getFormattedActivePath(context: Context): String {
        return getActiveDownloadsDir(context).absolutePath
    }

    fun startDownload(
        context: Context,
        title: String,
        fileName: String,
        fileId: Int,
        chatId: Long = 0L,
        messageId: Long = 0L,
        posterUrl: String = "",
        totalBytes: Long = 0L
    ): DownloadItem {
        val downloadId = if (chatId != 0L && messageId != 0L) "${chatId}_${messageId}" else fileId.toString()
        
        synchronized(downloadsMap) {
            val existing = downloadsMap[downloadId]
            if (existing != null && existing.status == DownloadStatus.COMPLETED && File(existing.localPath).exists()) {
                return existing
            }

            val downloadsDir = getActiveDownloadsDir(context)
            val destFile = File(downloadsDir, fileName.ifBlank { "video_$fileId.mp4" })

            val item = DownloadItem(
                id = downloadId,
                title = title,
                fileName = fileName,
                fileId = fileId,
                chatId = chatId,
                messageId = messageId,
                posterUrl = posterUrl,
                localPath = destFile.absolutePath,
                totalBytes = totalBytes,
                downloadedBytes = 0L,
                status = DownloadStatus.DOWNLOADING,
                addedTime = System.currentTimeMillis()
            )

            downloadsMap[downloadId] = item
            saveToPrefs(context)
            updateFlow()

            // Request TDLib to start downloading file
            if (fileId != 0) {
                CoroutineScope(Dispatchers.IO).launch {
                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                        req.fileId = fileId
                        req.priority = 32
                        req.offset = 0
                        req.limit = 0
                        req.synchronous = false
                    })
                }
            }

            // Start background service & active polling loop
            TeleflixDownloadService.start(context)
            startDownloadLoop(context)

            return item
        }
    }

    private fun startDownloadLoop(context: Context) {
        if (downloadLoopJob?.isActive == true) return
        val appContext = context.applicationContext
        downloadLoopJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && hasActiveDownloads()) {
                val activeItems = synchronized(downloadsMap) {
                    downloadsMap.values.filter { it.status == DownloadStatus.DOWNLOADING }
                }

                for (item in activeItems) {
                    try {
                        var currentFileId = item.fileId

                        // Refresh message if fileId is zero
                        if (currentFileId == 0 && item.chatId != 0L && item.messageId != 0L) {
                            val msg = TelegramClient.sendRequest(TdApi.GetMessage(item.chatId, item.messageId)) as? TdApi.Message
                            val refreshedId = extractFileIdFromMessage(msg)
                            if (refreshedId != null && refreshedId != 0) {
                                currentFileId = refreshedId
                                item.fileId = refreshedId
                            }
                        }

                        if (currentFileId != 0) {
                            val fileObj = TelegramClient.sendRequest(TdApi.GetFile(currentFileId)) as? TdApi.File
                            if (fileObj != null) {
                                withContext(Dispatchers.Main) {
                                    onFileUpdate(appContext, fileObj)
                                }

                                if (!fileObj.local.isDownloadingActive && !fileObj.local.isDownloadingCompleted) {
                                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                        req.fileId = currentFileId
                                        req.priority = 32
                                        req.offset = 0
                                        req.limit = 0
                                        req.synchronous = false
                                    })
                                }
                            } else {
                                TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                    req.fileId = currentFileId
                                    req.priority = 32
                                    req.offset = 0
                                    req.limit = 0
                                    req.synchronous = false
                                })
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in download loop for item ${item.id}", e)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun extractFileIdFromMessage(msg: TdApi.Message?): Int? {
        if (msg == null) return null
        return when (val content = msg.content) {
            is TdApi.MessageVideo -> content.video.video.id
            is TdApi.MessageDocument -> content.document.document.id
            is TdApi.MessageAudio -> content.audio.audio.id
            else -> null
        }
    }

    fun pauseDownload(context: Context, downloadId: String) {
        synchronized(downloadsMap) {
            val item = downloadsMap[downloadId] ?: return
            if (item.status == DownloadStatus.DOWNLOADING) {
                item.status = DownloadStatus.PAUSED
                item.speedBytesPerSec = 0L
                saveToPrefs(context)
                updateFlow()

                CoroutineScope(Dispatchers.IO).launch {
                    if (item.fileId != 0) {
                        TelegramClient.sendRequest(TdApi.CancelDownloadFile(item.fileId, false))
                    }
                }
            }
        }
    }

    fun resumeDownload(context: Context, downloadId: String) {
        synchronized(downloadsMap) {
            val item = downloadsMap[downloadId] ?: return
            if (item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.FAILED) {
                item.status = DownloadStatus.DOWNLOADING
                saveToPrefs(context)
                updateFlow()

                if (item.fileId != 0) {
                    CoroutineScope(Dispatchers.IO).launch {
                        TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                            req.fileId = item.fileId
                            req.priority = 32
                            req.offset = 0
                            req.limit = 0
                            req.synchronous = false
                        })
                    }
                }
                TeleflixDownloadService.start(context)
                startDownloadLoop(context)
            }
        }
    }

    fun cancelDownload(context: Context, downloadId: String) {
        synchronized(downloadsMap) {
            val item = downloadsMap.remove(downloadId) ?: return
            saveToPrefs(context)
            updateFlow()

            CoroutineScope(Dispatchers.IO).launch {
                if (item.fileId != 0) {
                    TelegramClient.sendRequest(TdApi.CancelDownloadFile(item.fileId, true))
                }
                val f = File(item.localPath)
                if (f.exists()) f.delete()
            }
        }
    }

    fun deleteDownloadedFile(context: Context, downloadId: String) {
        synchronized(downloadsMap) {
            val item = downloadsMap.remove(downloadId) ?: return
            saveToPrefs(context)
            updateFlow()

            try {
                val file = File(item.localPath)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete local file: ${item.localPath}", e)
            }
        }
    }

    fun onFileUpdate(context: Context, file: TdApi.File) {
        synchronized(downloadsMap) {
            var updated = false
            val now = System.currentTimeMillis()
            val timeDiff = (now - lastSpeedCalcTime).coerceAtLeast(1)

            for (item in downloadsMap.values) {
                if (item.fileId == file.id || (item.fileId == 0 && file.id != 0)) {
                    if (item.fileId == 0) {
                        item.fileId = file.id
                    }

                    item.downloadedBytes = file.local.downloadedSize
                    if (file.expectedSize > 0) {
                        item.totalBytes = file.expectedSize
                    } else if (file.size > 0) {
                        item.totalBytes = file.size
                    }

                    // Calculate speed
                    val lastBytes = lastDownloadedBytesMap[item.id] ?: item.downloadedBytes
                    val bytesDiff = (item.downloadedBytes - lastBytes).coerceAtLeast(0)
                    item.speedBytesPerSec = (bytesDiff * 1000) / timeDiff
                    lastDownloadedBytesMap[item.id] = item.downloadedBytes

                    val tdlibPath = file.local?.path ?: ""

                    // Check if file completed
                    if (file.local.isDownloadingCompleted || (file.size > 0 && file.local.downloadedSize >= file.size)) {
                        item.status = DownloadStatus.COMPLETED
                        item.downloadedBytes = if (file.size > 0) file.size else item.downloadedBytes
                        item.totalBytes = if (file.size > 0) file.size else item.totalBytes
                        item.speedBytesPerSec = 0L

                        if (tdlibPath.isNotBlank()) {
                            try {
                                val source = File(tdlibPath)
                                val target = File(item.localPath)
                                if (target.parentFile?.exists() == false) {
                                    target.parentFile?.mkdirs()
                                }
                                if (source.exists() && source.absolutePath != target.absolutePath) {
                                    source.copyTo(target, overwrite = true)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed copying completed download: ${e.message}")
                            }
                        }
                    } else if (file.local.isDownloadingActive) {
                        item.status = DownloadStatus.DOWNLOADING
                    }
                    updated = true
                }
            }

            if (timeDiff >= 1000) {
                lastSpeedCalcTime = now
            }

            if (updated) {
                saveToPrefs(context)
                updateFlow()
            }
        }
    }

    fun hasActiveDownloads(): Boolean {
        synchronized(downloadsMap) {
            return downloadsMap.values.any { it.status == DownloadStatus.DOWNLOADING }
        }
    }

    fun getActiveDownloadsCount(): Int {
        synchronized(downloadsMap) {
            return downloadsMap.values.count { it.status == DownloadStatus.DOWNLOADING }
        }
    }
}
