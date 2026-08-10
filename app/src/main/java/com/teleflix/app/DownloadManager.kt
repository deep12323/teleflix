package com.teleflix.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object DownloadManager {
    private const val TAG = "DownloadManager"
    private const val PREFS_NAME = "teleflix_downloads_prefs"
    private const val KEY_DOWNLOADS = "downloads_json"

    private val downloadsMap = LinkedHashMap<String, DownloadItem>()
    private val _downloadsFlow = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloadsFlow: StateFlow<List<DownloadItem>> = _downloadsFlow.asStateFlow()

    private var lastSpeedCalcTime = System.currentTimeMillis()
    private var lastSpeedCalcTimeMap = HashMap<String, Long>()
    private var lastDownloadedBytesMap = HashMap<String, Long>()
    private var lastDownloadRetryTimeMap = HashMap<Int, Long>()
    private var lastProgressLogTimeMap = HashMap<String, Long>()
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
                        
                        val isMultiPart = obj.optBoolean("isMultiPart", false)
                        val partFileIds = mutableListOf<Int>()
                        val partChatIds = mutableListOf<Long>()
                        val partMessageIds = mutableListOf<Long>()
                        val partFileSizes = mutableListOf<Long>()
                        val partFileNames = mutableListOf<String>()

                        val pfArr = obj.optJSONArray("partFileIds")
                        if (pfArr != null) {
                            for (j in 0 until pfArr.length()) partFileIds.add(pfArr.getInt(j))
                        }
                        val pcArr = obj.optJSONArray("partChatIds")
                        if (pcArr != null) {
                            for (j in 0 until pcArr.length()) partChatIds.add(pcArr.getLong(j))
                        }
                        val pmArr = obj.optJSONArray("partMessageIds")
                        if (pmArr != null) {
                            for (j in 0 until pmArr.length()) partMessageIds.add(pmArr.getLong(j))
                        }
                        val psArr = obj.optJSONArray("partFileSizes")
                        if (psArr != null) {
                            for (j in 0 until psArr.length()) partFileSizes.add(psArr.getLong(j))
                        }
                        val fnArr = obj.optJSONArray("partFileNames")
                        if (fnArr != null) {
                            for (j in 0 until fnArr.length()) partFileNames.add(fnArr.getString(j))
                        }

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
                            addedTime = obj.optLong("addedTime", System.currentTimeMillis()),
                            isMultiPart = isMultiPart,
                            partFileIds = partFileIds,
                            partChatIds = partChatIds,
                            partMessageIds = partMessageIds,
                            partFileSizes = partFileSizes,
                            partFileNames = partFileNames,
                            currentPartIndex = obj.optInt("currentPartIndex", 0)
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
                        put("isMultiPart", item.isMultiPart)
                        put("currentPartIndex", item.currentPartIndex)
                        put("partFileIds", JSONArray(item.partFileIds))
                        put("partChatIds", JSONArray(item.partChatIds))
                        put("partMessageIds", JSONArray(item.partMessageIds))
                        put("partFileSizes", JSONArray(item.partFileSizes))
                        put("partFileNames", JSONArray(item.partFileNames))
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
        _downloadsFlow.value = synchronized(downloadsMap) {
            downloadsMap.values.map { it.copy() }.sortedByDescending { it.addedTime }
        }
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
            if (fileId != 0 || (chatId != 0L && messageId != 0L)) {
                TeleflixLogger.log(TAG, "Starting download '${title}': fileId=$fileId, chatId=$chatId, messageId=$messageId")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        var targetId = fileId
                        if (chatId != 0L && messageId != 0L) {
                            try {
                                val msg = TelegramClient.sendRequest(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message
                                val freshId = extractFileIdFromMessage(msg)
                                if (freshId != null && freshId != 0) {
                                    targetId = freshId
                                    item.fileId = freshId
                                    TeleflixLogger.log(TAG, "Fetched fresh video fileId=$freshId from message $chatId/$messageId")
                                }
                            } catch (e: Exception) {
                                TeleflixLogger.log(TAG, "GetMessage failed during startDownload for $chatId/$messageId: ${e.message}", isError = true)
                            }
                        }
                        if (targetId != 0) {
                            lastDownloadRetryTimeMap[targetId] = System.currentTimeMillis()
                            val res = TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                req.fileId = targetId
                                req.priority = 32
                                req.offset = 0
                                req.limit = 0
                                req.synchronous = false
                            })
                            TeleflixLogger.log(TAG, "Initial DownloadFile request for $targetId returned: ${res?.javaClass?.simpleName}")
                        }
                    } catch (e: Exception) {
                        TeleflixLogger.log(TAG, "Failed initial DownloadFile request for $fileId: ${e.message}", isError = true)
                    }
                }
            } else {
                TeleflixLogger.log(TAG, "Starting download '${title}' with fileId=0, chatId=$chatId, messageId=$messageId", isError = true)
            }

            // Start background service & active polling loop
            TeleflixDownloadService.start(context)
            startDownloadLoop(context)

            return item
        }
    }

    fun startMultiPartDownload(
        context: Context,
        title: String,
        baseName: String,
        parts: List<TelegramVideoMessage>,
        posterUrl: String = ""
    ): DownloadItem {
        val firstPart = parts.firstOrNull()
        val downloadId = "group_${firstPart?.chatId}_${firstPart?.messageId}_${parts.size}"
        
        synchronized(downloadsMap) {
            val existing = downloadsMap[downloadId]
            if (existing != null && existing.status == DownloadStatus.COMPLETED && File(existing.localPath).exists()) {
                return existing
            }

            val cleanTitle = title.removePrefix("📦 ").removePrefix("🗄️ ").trim()
            val cleanBaseName = baseName.removePrefix("📦 ").removePrefix("🗄️ ").trim()

            val ext = if (cleanBaseName.contains(".")) cleanBaseName.substringAfterLast('.') else "mkv"
            val rawName = if (cleanBaseName.contains(".")) cleanBaseName.substringBeforeLast('.') else cleanBaseName
            val destFileName = "$rawName.$ext"

            val downloadsDir = getActiveDownloadsDir(context)
            val destFile = File(downloadsDir, destFileName)

            val totalSize = parts.sumOf { it.fileSize }

            val item = DownloadItem(
                id = downloadId,
                title = "$cleanTitle (Combined Video)",
                fileName = destFileName,
                fileId = firstPart?.fileId ?: 0,
                chatId = firstPart?.chatId ?: 0L,
                messageId = firstPart?.messageId ?: 0L,
                posterUrl = posterUrl,
                localPath = destFile.absolutePath,
                totalBytes = totalSize,
                downloadedBytes = 0L,
                status = DownloadStatus.DOWNLOADING,
                addedTime = System.currentTimeMillis(),
                isMultiPart = true,
                partFileIds = parts.map { it.fileId }.toMutableList(),
                partChatIds = parts.map { it.chatId }.toMutableList(),
                partMessageIds = parts.map { it.messageId }.toMutableList(),
                partFileSizes = parts.map { it.fileSize }.toMutableList(),
                partFileNames = parts.map { it.fileName }.toMutableList(),
                currentPartIndex = 0
            )

            downloadsMap[downloadId] = item
            saveToPrefs(context)
            updateFlow()

            val firstChatId = firstPart?.chatId ?: 0L
            val firstMessageId = firstPart?.messageId ?: 0L
            val firstFileId = firstPart?.fileId ?: 0

            TeleflixLogger.log(TAG, "Starting multipart download '$cleanTitle': parts=${parts.size}, firstFileId=$firstFileId")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    var targetId = firstFileId
                    if (firstChatId != 0L && firstMessageId != 0L) {
                        try {
                            val msg = TelegramClient.sendRequest(TdApi.GetMessage(firstChatId, firstMessageId)) as? TdApi.Message
                            val freshId = extractFileIdFromMessage(msg)
                            if (freshId != null && freshId != 0) {
                                targetId = freshId
                                item.fileId = freshId
                                if (item.partFileIds.isNotEmpty()) item.partFileIds[0] = freshId
                                TeleflixLogger.log(TAG, "Fetched fresh multipart part 1 fileId=$freshId from $firstChatId/$firstMessageId")
                            }
                        } catch (e: Exception) {
                            TeleflixLogger.log(TAG, "GetMessage failed during multipart startDownload: ${e.message}", isError = true)
                        }
                    }
                    if (targetId != 0) {
                        lastDownloadRetryTimeMap[targetId] = System.currentTimeMillis()
                        val res = TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                            req.fileId = targetId
                            req.priority = 32
                            req.offset = 0
                            req.limit = 0
                            req.synchronous = false
                        })
                        TeleflixLogger.log(TAG, "Initial multipart DownloadFile for $targetId returned: ${res?.javaClass?.simpleName}")
                    }
                } catch (e: Exception) {
                    TeleflixLogger.log(TAG, "Failed initial DownloadFile for multipart first part $firstFileId: ${e.message}", isError = true)
                }
            }

            TeleflixDownloadService.start(context)
            startDownloadLoop(context)

            return item
        }
    }

    private fun fastCopyFile(source: File, dest: File) {
        try {
            if (dest.parentFile?.exists() == false) {
                dest.parentFile?.mkdirs()
            }
            FileInputStream(source).channel.use { inChannel ->
                FileOutputStream(dest).channel.use { outChannel ->
                    inChannel.transferTo(0, inChannel.size(), outChannel)
                }
            }
        } catch (e: Exception) {
            source.copyTo(dest, overwrite = true)
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

                val now = System.currentTimeMillis()
                for (item in activeItems) {
                    try {
                        if (item.isMultiPart) {
                            processMultiPartDownloadStep(appContext, item, now)
                        } else {
                            processSinglePartDownloadStep(appContext, item, now)
                        }
                    } catch (e: Exception) {
                        TeleflixLogger.log(TAG, "Error in download loop for item ${item.id}: ${e.message}", isError = true)
                    }
                }
                delay(300)
            }
        }
    }

    private suspend fun processSinglePartDownloadStep(context: Context, item: DownloadItem, now: Long) {
        var currentFileId = item.fileId

        if (currentFileId == 0 && item.chatId != 0L && item.messageId != 0L) {
            try {
                val msg = TelegramClient.sendRequest(TdApi.GetMessage(item.chatId, item.messageId)) as? TdApi.Message
                val refreshedId = extractFileIdFromMessage(msg)
                if (refreshedId != null && refreshedId != 0) {
                    currentFileId = refreshedId
                    item.fileId = refreshedId
                    TeleflixLogger.log(TAG, "Resolved fresh video fileId=$refreshedId for item ${item.id}")
                }
            } catch (e: Exception) {
                TeleflixLogger.log(TAG, "Error fetching message for download item ${item.id}: ${e.message}", isError = true)
            }
        }

        if (currentFileId != 0) {
            var fileObj: TdApi.File? = try {
                TelegramClient.sendRequest(TdApi.GetFile(currentFileId)) as? TdApi.File
            } catch (e: Exception) {
                TeleflixLogger.log(TAG, "GetFile failed for fileId $currentFileId: ${e.message}, attempting message refresh...", isError = true)
                null
            }

            if (fileObj == null && item.chatId != 0L && item.messageId != 0L) {
                try {
                    val msg = TelegramClient.sendRequest(TdApi.GetMessage(item.chatId, item.messageId)) as? TdApi.Message
                    val refreshedId = extractFileIdFromMessage(msg)
                    if (refreshedId != null && refreshedId != 0) {
                        currentFileId = refreshedId
                        item.fileId = refreshedId
                        fileObj = TelegramClient.sendRequest(TdApi.GetFile(currentFileId)) as? TdApi.File
                        TeleflixLogger.log(TAG, "Successfully refreshed fileId for item ${item.id}: new fileId=$refreshedId")
                    }
                } catch (e: Exception) {
                    TeleflixLogger.log(TAG, "Message refresh after GetFile failure failed for ${item.id}: ${e.message}", isError = true)
                }
            }

            if (fileObj != null) {
                withContext(Dispatchers.Main) {
                    onFileUpdate(context, fileObj)
                }

                if (!fileObj.local.canBeDownloaded && !fileObj.local.isDownloadingActive && !fileObj.local.isDownloadingCompleted) {
                    val lastRetry = lastDownloadRetryTimeMap[currentFileId] ?: 0L
                    if (now - lastRetry > 5000L) {
                        lastDownloadRetryTimeMap[currentFileId] = now
                        try {
                            val res = TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                req.fileId = currentFileId
                                req.priority = 32
                                req.offset = 0
                                req.limit = 0
                                req.synchronous = false
                            })
                            TeleflixLogger.log(TAG, "Re-issued DownloadFile for unqueued fileId=$currentFileId: res=${res?.javaClass?.simpleName}")
                        } catch (e: Exception) {
                            TeleflixLogger.log(TAG, "Failed DownloadFile request for $currentFileId: ${e.message}", isError = true)
                        }
                    }
                }
            } else {
                TeleflixLogger.log(TAG, "Download item ${item.id} has invalid fileId=$currentFileId and could not be fetched. Marking FAILED.", isError = true)
                item.status = DownloadStatus.FAILED
                saveToPrefs(context)
                updateFlow()
            }
        } else {
            TeleflixLogger.log(TAG, "Download item ${item.id} has fileId=0 and cannot be refreshed. Marking FAILED.", isError = true)
            item.status = DownloadStatus.FAILED
            saveToPrefs(context)
            updateFlow()
        }
    }

    private suspend fun processMultiPartDownloadStep(context: Context, item: DownloadItem, now: Long) {
        val idx = item.currentPartIndex
        if (idx >= item.partFileIds.size) {
            finalizeMultiPartMerge(context, item)
            return
        }

        var partFileId = item.partFileIds.getOrNull(idx) ?: 0
        val chatId = item.partChatIds.getOrNull(idx) ?: 0L
        val messageId = item.partMessageIds.getOrNull(idx) ?: 0L

        if (partFileId == 0 && chatId != 0L && messageId != 0L) {
            try {
                val msg = TelegramClient.sendRequest(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message
                val refreshedId = extractFileIdFromMessage(msg)
                if (refreshedId != null && refreshedId != 0) {
                    partFileId = refreshedId
                    item.partFileIds[idx] = refreshedId
                    item.fileId = refreshedId
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching part message for download item ${item.id}", e)
            }
        }

        if (partFileId != 0) {
            var fileObj: TdApi.File? = try {
                TelegramClient.sendRequest(TdApi.GetFile(partFileId)) as? TdApi.File
            } catch (e: Exception) {
                Log.w(TAG, "GetFile failed for part $partFileId: ${e.message}, attempting refresh...")
                null
            }

            if (fileObj == null && chatId != 0L && messageId != 0L) {
                try {
                    val msg = TelegramClient.sendRequest(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message
                    val refreshedId = extractFileIdFromMessage(msg)
                    if (refreshedId != null && refreshedId != 0) {
                        partFileId = refreshedId
                        item.partFileIds[idx] = refreshedId
                        item.fileId = refreshedId
                        fileObj = TelegramClient.sendRequest(TdApi.GetFile(partFileId)) as? TdApi.File
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Refresh after GetFile failure failed for part ${item.id}", e)
                }
            }

            if (fileObj != null) {
                val sumCompletedParts = item.partFileSizes.take(idx).sum()
                val currentPartDownloaded = fileObj.local.downloadedSize
                item.downloadedBytes = sumCompletedParts + currentPartDownloaded

                val lastCalcTime = lastSpeedCalcTimeMap[item.id] ?: 0L
                if (now - lastCalcTime >= 1000L) {
                    val lastBytes = lastDownloadedBytesMap[item.id] ?: item.downloadedBytes
                    val bytesDiff = (item.downloadedBytes - lastBytes).coerceAtLeast(0)
                    val elapsed = (now - lastCalcTime).coerceAtLeast(1)
                    item.speedBytesPerSec = (bytesDiff * 1000) / elapsed
                    lastDownloadedBytesMap[item.id] = item.downloadedBytes
                    lastSpeedCalcTimeMap[item.id] = now
                }

                val expectedPartSize = item.partFileSizes.getOrNull(idx) ?: 0L
                val lastLogTime = lastProgressLogTimeMap[item.id] ?: 0L
                if (now - lastLogTime >= 5000L && item.status == DownloadStatus.DOWNLOADING && item.downloadedBytes > 0) {
                    lastProgressLogTimeMap[item.id] = now
                    val currentPartSize = if (expectedPartSize > 0) expectedPartSize else if (fileObj.expectedSize > 0) fileObj.expectedSize else fileObj.size
                    val partPct = if (currentPartSize > 0) String.format(java.util.Locale.US, "%.1f%%", (currentPartDownloaded.toDouble() / currentPartSize) * 100) else "N/A"
                    val totalPct = if (item.totalBytes > 0) String.format(java.util.Locale.US, "%.1f%%", (item.downloadedBytes.toDouble() / item.totalBytes) * 100) else "N/A"
                    val dlMB = String.format(java.util.Locale.US, "%.2f MB", item.downloadedBytes / (1024.0 * 1024.0))
                    val totMB = if (item.totalBytes > 0) String.format(java.util.Locale.US, "%.2f MB", item.totalBytes / (1024.0 * 1024.0)) else "Unknown"
                    val speedMBs = String.format(java.util.Locale.US, "%.2f MB/s", item.speedBytesPerSec / (1024.0 * 1024.0))
                    TeleflixLogger.log(TAG, "Multipart download progress for '${item.title}': Part ${idx + 1}/${item.partFileIds.size} ($partPct) | Overall: $totalPct ($dlMB / $totMB) at $speedMBs | fileId=$partFileId")
                }

                val tdlibPath = fileObj.local?.path ?: ""

                val isPartDone = fileObj.local.isDownloadingCompleted || 
                    (expectedPartSize > 0 && fileObj.local.downloadedSize >= expectedPartSize)

                if (isPartDone) {
                    if (tdlibPath.isNotBlank()) {
                        val partTempFile = getPartTempFile(context, item.id, idx)
                        val source = File(tdlibPath)
                        if (source.exists()) {
                            fastCopyFile(source, partTempFile)
                        }
                    }

                    item.currentPartIndex++
                    saveToPrefs(context)
                    updateFlow()

                    if (item.currentPartIndex >= item.partFileIds.size) {
                        finalizeMultiPartMerge(context, item)
                    } else {
                        val nextFileId = item.partFileIds.getOrNull(item.currentPartIndex) ?: 0
                        if (nextFileId != 0) {
                            try {
                                TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                    req.fileId = nextFileId
                                    req.priority = 32
                                    req.offset = 0
                                    req.limit = 0
                                    req.synchronous = false
                                })
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed DownloadFile for next part $nextFileId", e)
                            }
                        }
                    }
                } else {
                    // Pre-fetch next part in advance while current part is downloading
                    val nextIdx = idx + 1
                    if (nextIdx < item.partFileIds.size) {
                        val nextFileId = item.partFileIds.getOrNull(nextIdx) ?: 0
                        if (nextFileId != 0) {
                            val lastPrefetch = lastDownloadRetryTimeMap[nextFileId] ?: 0L
                            if (now - lastPrefetch > 10000L) {
                                lastDownloadRetryTimeMap[nextFileId] = now
                                try {
                                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                        req.fileId = nextFileId
                                        req.priority = 16
                                        req.offset = 0
                                        req.limit = 0
                                        req.synchronous = false
                                    })
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    if (!fileObj.local.canBeDownloaded && !fileObj.local.isDownloadingActive && !fileObj.local.isDownloadingCompleted) {
                        val lastRetry = lastDownloadRetryTimeMap[partFileId] ?: 0L
                        if (now - lastRetry > 5000L) {
                            lastDownloadRetryTimeMap[partFileId] = now
                            try {
                                TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                    req.fileId = partFileId
                                    req.priority = 32
                                    req.offset = 0
                                    req.limit = 0
                                    req.synchronous = false
                                })
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed DownloadFile retry for part $partFileId", e)
                            }
                        }
                    }
                }
                updateFlow()
            } else {
                Log.e(TAG, "Multipart item ${item.id} part index $idx cannot resolve valid fileId. Marking FAILED.")
                item.status = DownloadStatus.FAILED
                saveToPrefs(context)
                updateFlow()
            }
        }
    }

    private fun extractFileIdFromMessage(msg: TdApi.Message?): Int? {
        if (msg == null) return null
        return when (val content = msg.content) {
            is TdApi.MessageVideo -> content.video.video.id
            is TdApi.MessageDocument -> content.document.document.id
            is TdApi.MessageAudio -> content.audio.audio.id
            is TdApi.MessageVoiceNote -> content.voiceNote.voice.id
            is TdApi.MessageAnimation -> content.animation.animation.id
            else -> null
        }
    }

    private fun getPartTempFile(context: Context, downloadId: String, partIndex: Int): File {
        val cacheDir = File(context.cacheDir, "multipart_temp")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, "${downloadId}_part_$partIndex.tmp")
    }

    private fun finalizeMultiPartMerge(context: Context, item: DownloadItem) {
        synchronized(downloadsMap) {
            try {
                val targetFile = File(item.localPath)
                if (targetFile.parentFile?.exists() == false) {
                    targetFile.parentFile?.mkdirs()
                }
                if (targetFile.exists()) targetFile.delete()

                FileOutputStream(targetFile, true).use { outStream ->
                    val buffer = ByteArray(256 * 1024)
                    for (i in 0 until item.partFileIds.size) {
                        val partFile = getPartTempFile(context, item.id, i)
                        if (partFile.exists()) {
                            FileInputStream(partFile).use { inStream ->
                                var readBytes: Int
                                while (inStream.read(buffer).also { readBytes = it } != -1) {
                                    outStream.write(buffer, 0, readBytes)
                                }
                            }
                            partFile.delete()
                        }
                    }
                }

                item.status = DownloadStatus.COMPLETED
                item.downloadedBytes = item.totalBytes
                item.speedBytesPerSec = 0L
                saveToPrefs(context)
                updateFlow()
                Log.d(TAG, "Successfully merged all ${item.partFileIds.size} parts for ${item.title} into ${item.localPath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error merging multipart download: ${e.message}", e)
                item.status = DownloadStatus.FAILED
                saveToPrefs(context)
                updateFlow()
            }
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
                    val activeId = if (item.isMultiPart) item.partFileIds.getOrNull(item.currentPartIndex) ?: item.fileId else item.fileId
                    if (activeId != 0) {
                        try {
                            TelegramClient.sendRequest(TdApi.CancelDownloadFile(activeId, false))
                        } catch (_: Exception) {}
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

                val activeId = if (item.isMultiPart) item.partFileIds.getOrNull(item.currentPartIndex) ?: item.fileId else item.fileId
                if (activeId != 0) {
                    lastDownloadRetryTimeMap[activeId] = System.currentTimeMillis()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                req.fileId = activeId
                                req.priority = 32
                                req.offset = 0
                                req.limit = 0
                                req.synchronous = false
                            })
                        } catch (_: Exception) {}
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
                val activeId = if (item.isMultiPart) item.partFileIds.getOrNull(item.currentPartIndex) ?: item.fileId else item.fileId
                if (activeId != 0) {
                    try {
                        TelegramClient.sendRequest(TdApi.CancelDownloadFile(activeId, true))
                    } catch (_: Exception) {}
                }
                val f = File(item.localPath)
                if (f.exists()) f.delete()
                for (i in 0 until item.partFileIds.size) {
                    val temp = getPartTempFile(context, item.id, i)
                    if (temp.exists()) temp.delete()
                }
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
                for (i in 0 until item.partFileIds.size) {
                    val temp = getPartTempFile(context, item.id, i)
                    if (temp.exists()) temp.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete local file: ${item.localPath}", e)
            }
        }
    }

    fun clearAllDownloads(context: Context) {
        synchronized(downloadsMap) {
            val allIds = downloadsMap.keys.toList()
            for (id in allIds) {
                val item = downloadsMap.remove(id) ?: continue
                try {
                    val file = File(item.localPath)
                    if (file.exists()) file.delete()
                    for (i in 0 until item.partFileIds.size) {
                        val temp = getPartTempFile(context, item.id, i)
                        if (temp.exists()) temp.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete local file: ${item.localPath}", e)
                }
            }
            saveToPrefs(context)
            updateFlow()
        }
    }

    fun onFileUpdate(context: Context, file: TdApi.File) {
        synchronized(downloadsMap) {
            var updated = false
            val now = System.currentTimeMillis()

            for (item in downloadsMap.values) {
                if (!item.isMultiPart && (item.fileId == file.id || (item.fileId == 0 && file.id != 0))) {
                    if (item.fileId == 0) {
                        item.fileId = file.id
                    }

                    item.downloadedBytes = file.local.downloadedSize
                    val reportedSize = if (file.expectedSize > 0) file.expectedSize else if (file.size > 0) file.size else 0L
                    if (reportedSize > item.totalBytes || item.totalBytes == 0L) {
                        item.totalBytes = reportedSize
                    }

                    val lastCalcTime = lastSpeedCalcTimeMap[item.id] ?: 0L
                    if (now - lastCalcTime >= 1000L) {
                        val lastBytes = lastDownloadedBytesMap[item.id] ?: item.downloadedBytes
                        val bytesDiff = (item.downloadedBytes - lastBytes).coerceAtLeast(0)
                        val elapsed = (now - lastCalcTime).coerceAtLeast(1)
                        item.speedBytesPerSec = (bytesDiff * 1000) / elapsed
                        lastDownloadedBytesMap[item.id] = item.downloadedBytes
                        lastSpeedCalcTimeMap[item.id] = now
                    }

                    val lastLogTime = lastProgressLogTimeMap[item.id] ?: 0L
                    if (now - lastLogTime >= 5000L && item.status == DownloadStatus.DOWNLOADING && item.downloadedBytes > 0) {
                        lastProgressLogTimeMap[item.id] = now
                        val pct = if (item.totalBytes > 0) String.format(java.util.Locale.US, "%.1f%%", (item.downloadedBytes.toDouble() / item.totalBytes) * 100) else "N/A"
                        val dlMB = String.format(java.util.Locale.US, "%.2f MB", item.downloadedBytes / (1024.0 * 1024.0))
                        val totMB = if (item.totalBytes > 0) String.format(java.util.Locale.US, "%.2f MB", item.totalBytes / (1024.0 * 1024.0)) else "Unknown"
                        val speedMBs = String.format(java.util.Locale.US, "%.2f MB/s", item.speedBytesPerSec / (1024.0 * 1024.0))
                        TeleflixLogger.log(TAG, "Download progress for '${item.title}': $pct ($dlMB / $totMB) at $speedMBs | fileId=${file.id}")
                    }

                    val tdlibPath = file.local?.path ?: ""

                    val isCompleted = file.local.isDownloadingCompleted || 
                        (item.totalBytes > 0 && file.local.downloadedSize >= item.totalBytes)

                    if (isCompleted) {
                        item.status = DownloadStatus.COMPLETED
                        if (item.totalBytes > 0) item.downloadedBytes = item.totalBytes
                        item.speedBytesPerSec = 0L
                        TeleflixLogger.log(TAG, "Download COMPLETED for '${item.title}': downloaded=${item.downloadedBytes}/${item.totalBytes} bytes")

                        if (tdlibPath.isNotBlank()) {
                            try {
                                val source = File(tdlibPath)
                                val cleanPath = if (item.localPath.endsWith(".mp4.mp4", ignoreCase = true)) {
                                    item.localPath.substring(0, item.localPath.length - 4)
                                } else {
                                    item.localPath
                                }
                                val target = File(cleanPath)
                                if (target.parentFile?.exists() == false) {
                                    target.parentFile?.mkdirs()
                                }
                                if (source.exists() && source.absolutePath != target.absolutePath) {
                                    fastCopyFile(source, target)
                                    TeleflixLogger.log(TAG, "Copied completed download to ${target.absolutePath}")
                                }
                            } catch (e: Exception) {
                                TeleflixLogger.log(TAG, "Failed copying completed download: ${e.message}", isError = true)
                            }
                        }
                    } else if (file.local.isDownloadingActive) {
                        item.status = DownloadStatus.DOWNLOADING
                    }
                    updated = true
                }
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

    fun isFileIdActive(fileId: Int): Boolean {
        if (fileId == 0) return false
        synchronized(downloadsMap) {
            return downloadsMap.values.any { item ->
                item.status == DownloadStatus.DOWNLOADING &&
                (item.fileId == fileId || (item.isMultiPart && item.partFileIds.getOrNull(item.currentPartIndex) == fileId))
            }
        }
    }
}
