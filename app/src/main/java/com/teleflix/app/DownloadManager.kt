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
    private var lastBytesIncreaseTimeMap = HashMap<String, Long>()
    private var lastBytesCountMap = HashMap<String, Long>()
    private var consecutiveFetchFailuresMap = HashMap<String, Int>()
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

                        val rawLocalPath = obj.optString("localPath", "")
                        val rawFileName = obj.optString("fileName", "")
                        val cleanLocal = cleanFilePath(rawLocalPath)
                        val cleanName = sanitizeFileName(if (rawFileName.isNotBlank()) rawFileName else cleanLocal)

                        val item = DownloadItem(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            fileName = cleanName,
                            fileId = obj.getInt("fileId"),
                            chatId = obj.optLong("chatId", 0L),
                            messageId = obj.optLong("messageId", 0L),
                            posterUrl = obj.optString("posterUrl", ""),
                            localPath = cleanLocal,
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
        return getPrefs(context).getString("storage_mode", "public_downloads") ?: "public_downloads"
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

    fun getMaxConcurrentDownloads(context: Context): Int {
        return getPrefs(context).getInt("max_concurrent_downloads", 2)
    }

    fun setMaxConcurrentDownloads(context: Context, max: Int) {
        getPrefs(context).edit().putInt("max_concurrent_downloads", max.coerceIn(1, 6)).apply()
        processNextQueuedItem(context)
    }

    fun getActiveDownloadsDir(context: Context): File {
        val mode = getStorageMode(context)
        val dir = when (mode) {
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
        if (dir != null) return dir

        val pubDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Teleflix")
        if (!pubDir.exists()) pubDir.mkdirs()
        if (pubDir.exists()) return pubDir

        return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    }

    fun getFormattedActivePath(context: Context): String {
        return getActiveDownloadsDir(context).absolutePath
    }

    fun sanitizeFileName(rawName: String, defaultExt: String = "mp4"): String {
        if (rawName.isBlank()) return "download.$defaultExt"
        var clean = rawName
            .removePrefix("📺 ").removePrefix("🗄️ ").removePrefix("📦 ")
            .removePrefix("Select:").removePrefix("Select").trim()
        if (clean.isBlank()) return "download.$defaultExt"

        // Fix double extensions like .mp3.mp4, .mkv.mp4, .mp4.mp4, .avi.mp4, .zip.mp4, etc.
        val doubleExtRegex = Regex("""(?i)\.(mp3|mp4|mkv|avi|mov|wmv|flv|webm|m4a|aac|flac|ogg|wav|m3u8|ts|zip|rar|7z|apk|pdf|srt|vtt|ass)\.(mp4|mkv|avi)$""")
        while (doubleExtRegex.containsMatchIn(clean)) {
            clean = clean.replace(doubleExtRegex) { matchResult ->
                "." + matchResult.groupValues[1]
            }
        }

        // Check if filename already has a valid file extension (e.g. .mp3, .mp4, .mkv, .avi, etc.)
        val hasExtRegex = Regex("""\.[a-zA-Z0-9]{2,5}$""", RegexOption.IGNORE_CASE)
        if (hasExtRegex.containsMatchIn(clean)) {
            return clean
        }

        return "$clean.$defaultExt"
    }

    fun cleanFilePath(path: String): String {
        if (path.isBlank()) return path
        val file = File(path)
        val parent = file.parentFile
        val cleanName = sanitizeFileName(file.name)
        return if (parent != null) File(parent, cleanName).absolutePath else cleanName
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
        val cleanName = sanitizeFileName(if (fileName.isNotBlank()) fileName else title, defaultExt = "mp4")

        synchronized(downloadsMap) {
            val existing = downloadsMap[downloadId]
            if (existing != null && existing.status == DownloadStatus.COMPLETED && File(cleanFilePath(existing.localPath)).exists()) {
                return existing
            }

            val downloadsDir = getActiveDownloadsDir(context)
            val destFile = File(downloadsDir, cleanName)

            val maxConcurrent = getMaxConcurrentDownloads(context)
            val currentActiveCount = downloadsMap.values.count { it.status == DownloadStatus.DOWNLOADING }
            val initialStatus = if (currentActiveCount >= maxConcurrent) DownloadStatus.QUEUED else DownloadStatus.DOWNLOADING

            val item = DownloadItem(
                id = downloadId,
                title = title,
                fileName = cleanName,
                fileId = fileId,
                chatId = chatId,
                messageId = messageId,
                posterUrl = posterUrl,
                localPath = destFile.absolutePath,
                totalBytes = totalBytes,
                downloadedBytes = 0L,
                status = initialStatus,
                addedTime = System.currentTimeMillis()
            )

            downloadsMap[downloadId] = item
            resetSpeedTracking(downloadId)
            saveToPrefs(context)
            updateFlow()

            if (initialStatus == DownloadStatus.DOWNLOADING) {
                // Request TDLib to start downloading file
                if (fileId != 0 || (chatId != 0L && messageId != 0L)) {
                    TeleflixLogger.log(TAG, "Starting download '${title}': fileId=$fileId, chatId=$chatId, messageId=$messageId (Parallel slot ${currentActiveCount + 1}/$maxConcurrent)")
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
                                val res = if (chatId != 0L && messageId != 0L) {
                                    TelegramClient.sendRequest(TdApi.AddFileToDownloads(targetId, chatId, messageId, 32))
                                } else {
                                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                        req.fileId = targetId
                                        req.priority = 32
                                        req.offset = 0
                                        req.limit = 0
                                        req.synchronous = false
                                    })
                                }
                                TeleflixLogger.log(TAG, "Initial AddFileToDownloads request for $targetId returned: ${res?.javaClass?.simpleName}")
                            }
                        } catch (e: Exception) {
                            TeleflixLogger.log(TAG, "Failed initial DownloadFile request for $fileId: ${e.message}", isError = true)
                        }
                    }
                } else {
                    TeleflixLogger.log(TAG, "Starting download '${title}' with fileId=0, chatId=$chatId, messageId=$messageId", isError = true)
                }
            } else {
                TeleflixLogger.log(TAG, "Queued download '${title}' behind active downloading items ($currentActiveCount/$maxConcurrent active)")
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

            val destFileName = sanitizeFileName(cleanBaseName, defaultExt = "mkv")

            val downloadsDir = getActiveDownloadsDir(context)
            val destFile = File(downloadsDir, destFileName)

            val totalSize = parts.sumOf { it.fileSize }

            val maxConcurrent = getMaxConcurrentDownloads(context)
            val currentActiveCount = downloadsMap.values.count { it.status == DownloadStatus.DOWNLOADING }
            val initialStatus = if (currentActiveCount >= maxConcurrent) DownloadStatus.QUEUED else DownloadStatus.DOWNLOADING

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
                status = initialStatus,
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
            resetSpeedTracking(downloadId)
            saveToPrefs(context)
            updateFlow()

            val firstChatId = firstPart?.chatId ?: 0L
            val firstMessageId = firstPart?.messageId ?: 0L
            val firstFileId = firstPart?.fileId ?: 0

            if (initialStatus == DownloadStatus.DOWNLOADING) {
                TeleflixLogger.log(TAG, "Starting multipart download '$cleanTitle': parts=${parts.size}, firstFileId=$firstFileId (Parallel slot ${currentActiveCount + 1}/$maxConcurrent)")
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
                            val res = if (firstChatId != 0L && firstMessageId != 0L) {
                                TelegramClient.sendRequest(TdApi.AddFileToDownloads(targetId, firstChatId, firstMessageId, 32))
                            } else {
                                TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                    req.fileId = targetId
                                    req.priority = 32
                                    req.offset = 0
                                    req.limit = 0
                                    req.synchronous = false
                                })
                            }
                            TeleflixLogger.log(TAG, "Initial multipart AddFileToDownloads for $targetId returned: ${res?.javaClass?.simpleName}")
                        }
                    } catch (e: Exception) {
                        TeleflixLogger.log(TAG, "Failed initial DownloadFile for multipart first part $firstFileId: ${e.message}", isError = true)
                    }
                }
            } else {
                TeleflixLogger.log(TAG, "Queued multipart download '$cleanTitle' behind active downloading item")
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
                    val size = inChannel.size()
                    var transferred = 0L
                    while (transferred < size) {
                        val bytes = inChannel.transferTo(transferred, size - transferred, outChannel)
                        if (bytes <= 0L) break
                        transferred += bytes
                    }
                }
            }
        } catch (e: Exception) {
            source.copyTo(dest, overwrite = true)
        }
    }

    private fun startDownloadLoop(context: Context) {
        synchronized(this) {
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
                    delay(150)
                }
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
                    if (item.isMultiPart && item.partFileIds.size > item.currentPartIndex) {
                        item.partFileIds[item.currentPartIndex] = refreshedId
                    }
                    try { TelegramClient.sendRequest(TdApi.AddFileToDownloads(refreshedId, item.chatId, item.messageId, 32)) } catch (_: Exception) {}
                    TeleflixLogger.log(TAG, "Resolved fresh video fileId=$refreshedId for item ${item.id}")
                }
            } catch (e: Exception) {
                TeleflixLogger.log(TAG, "Error fetching message for download item ${item.id}: ${e.message}", isError = true)
            }
        }

        if (currentFileId != 0) {
            var fileObj: TdApi.File? = try {
                TelegramClient.sendRequest(TdApi.GetFile(currentFileId)) as? TdApi.File
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
                        if (item.isMultiPart && item.partFileIds.size > item.currentPartIndex) {
                            item.partFileIds[item.currentPartIndex] = refreshedId
                        }
                        try { TelegramClient.sendRequest(TdApi.AddFileToDownloads(refreshedId, item.chatId, item.messageId, 32)) } catch (_: Exception) {}
                        fileObj = TelegramClient.sendRequest(TdApi.GetFile(currentFileId)) as? TdApi.File
                        TeleflixLogger.log(TAG, "Successfully refreshed & queued fileId for item ${item.id}: new fileId=$refreshedId")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TeleflixLogger.log(TAG, "Message refresh after GetFile failure failed for ${item.id}: ${e.message}", isError = true)
                }
            }

            if (fileObj != null) {
                consecutiveFetchFailuresMap.remove(item.id)
                withContext(Dispatchers.Main) {
                    onFileUpdate(context, fileObj)
                }

                val lastBytes = lastBytesCountMap[item.id] ?: -1L
                if (fileObj.local.downloadedSize > lastBytes) {
                    lastBytesCountMap[item.id] = fileObj.local.downloadedSize
                    lastBytesIncreaseTimeMap[item.id] = now
                } else if (fileObj.local.downloadedSize > 0 && !fileObj.local.isDownloadingCompleted) {
                    val lastIncrease = lastBytesIncreaseTimeMap[item.id] ?: now
                    val stallDuration = now - lastIncrease
                    val isNearComp = (item.totalBytes > 0 && fileObj.local.downloadedSize >= (item.totalBytes - 25 * 1024 * 1024)) ||
                                     (fileObj.expectedSize > 0 && fileObj.local.downloadedSize >= (fileObj.expectedSize - 25 * 1024 * 1024))
                    if (stallDuration > 25000L && !isNearComp) {
                        lastBytesIncreaseTimeMap[item.id] = now
                        TeleflixLogger.log(TAG, "[DownloadManager] Frozen socket detected for '${item.title}' at ${fileObj.local.downloadedSize} bytes (stalled >25s). Resetting socket connection for fileId=$currentFileId...")
                        try {
                            TelegramClient.sendRequest(TdApi.ToggleDownloadIsPaused(currentFileId, true))
                            TelegramClient.sendRequest(TdApi.ToggleDownloadIsPaused(currentFileId, false))
                            val res = TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                req.fileId = currentFileId
                                req.priority = 32
                                req.offset = fileObj.local.downloadedSize.coerceAtLeast(0L)
                                req.limit = 0
                                req.synchronous = false
                            })
                            TeleflixLogger.log(TAG, "[DownloadManager] Soft-reset socket connection after stall for fileId=$currentFileId: res=${res?.javaClass?.simpleName}")
                        } catch (e: Exception) {
                            TeleflixLogger.log(TAG, "Failed stall recovery download request for $currentFileId: ${e.message}", isError = true)
                        }
                    }
                }

                val lastPing = lastDownloadRetryTimeMap[currentFileId] ?: 0L
                val isNearCompletion = (item.totalBytes > 0 && fileObj.local.downloadedSize >= (item.totalBytes - 25 * 1024 * 1024)) ||
                                       (fileObj.expectedSize > 0 && fileObj.local.downloadedSize >= (fileObj.expectedSize - 25 * 1024 * 1024))

                if (!fileObj.local.isDownloadingCompleted && !fileObj.local.isDownloadingActive && !isNearCompletion) {
                    if (now - lastPing > 15000L) {
                        lastDownloadRetryTimeMap[currentFileId] = now
                        try {
                            TelegramClient.sendRequest(TdApi.ToggleDownloadIsPaused(currentFileId, false))
                            val res = if (fileObj.local.downloadedSize == 0L && item.chatId != 0L && item.messageId != 0L) {
                                TelegramClient.sendRequest(TdApi.AddFileToDownloads(currentFileId, item.chatId, item.messageId, 32))
                            } else {
                                TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                    req.fileId = currentFileId
                                    req.priority = 32
                                    req.offset = fileObj.local.downloadedSize.coerceAtLeast(0L)
                                    req.limit = 0
                                    req.synchronous = false
                                })
                            }
                            TeleflixLogger.log(TAG, "Re-triggered active download for fileId=$currentFileId at ${fileObj.local.downloadedSize} bytes: res=${res?.javaClass?.simpleName}")
                        } catch (e: Exception) {
                            TeleflixLogger.log(TAG, "Failed download re-trigger request for $currentFileId: ${e.message}", isError = true)
                            if (item.chatId != 0L && item.messageId != 0L) {
                                try {
                                    val msg = TelegramClient.sendRequest(TdApi.GetMessage(item.chatId, item.messageId)) as? TdApi.Message
                                    val freshId = extractFileIdFromMessage(msg)
                                    if (freshId != null && freshId != 0 && freshId != currentFileId) {
                                        item.fileId = freshId
                                        TelegramClient.sendRequest(TdApi.AddFileToDownloads(freshId, item.chatId, item.messageId, 32))
                                        TeleflixLogger.log(TAG, "Auto-refreshed stale fileId=$currentFileId to fresh fileId=$freshId for '${item.title}'")
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            } else {
                val failures = (consecutiveFetchFailuresMap[item.id] ?: 0) + 1
                consecutiveFetchFailuresMap[item.id] = failures
                if (failures >= 15) {
                    TeleflixLogger.log(TAG, "Download item ${item.id} has invalid fileId=$currentFileId and could not be fetched after $failures attempts. Marking FAILED.", isError = true)
                    item.status = DownloadStatus.FAILED
                    saveToPrefs(context)
                    updateFlow()
                } else {
                    TeleflixLogger.log(TAG, "Download item ${item.id} temporary GetFile failure (attempt $failures/15) for fileId=$currentFileId. Retrying...")
                }
            }
        } else {
            val failures = (consecutiveFetchFailuresMap[item.id] ?: 0) + 1
            consecutiveFetchFailuresMap[item.id] = failures
            if (failures >= 15) {
                TeleflixLogger.log(TAG, "Download item ${item.id} has fileId=0 and cannot be refreshed after $failures attempts. Marking FAILED.", isError = true)
                item.status = DownloadStatus.FAILED
                saveToPrefs(context)
                updateFlow()
            }
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
                    try { TelegramClient.sendRequest(TdApi.AddFileToDownloads(refreshedId, chatId, messageId, 32)) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching part message for download item ${item.id}", e)
            }
        }

        if (partFileId != 0) {
            var fileObj: TdApi.File? = try {
                TelegramClient.sendRequest(TdApi.GetFile(partFileId)) as? TdApi.File
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
                        try { TelegramClient.sendRequest(TdApi.AddFileToDownloads(refreshedId, chatId, messageId, 32)) } catch (_: Exception) {}
                        fileObj = TelegramClient.sendRequest(TdApi.GetFile(partFileId)) as? TdApi.File
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Refresh after GetFile failure failed for part ${item.id}", e)
                }
            }

            if (fileObj != null) {
                consecutiveFetchFailuresMap.remove("${item.id}_part_$idx")
                val sumCompletedParts = item.partFileSizes.take(idx).sum()
                val currentPartDownloaded = fileObj.local.downloadedSize
                item.downloadedBytes = sumCompletedParts + currentPartDownloaded

                updateDownloadSpeed(item, now)

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
                    val remainingBytes = (item.totalBytes - item.downloadedBytes).coerceAtLeast(0L)
                    val etaStr = formatEtaTime(remainingBytes, item.speedBytesPerSec)
                    TeleflixLogger.log(TAG, "Multipart download progress for '${item.title}': Part ${idx + 1}/${item.partFileIds.size} ($partPct) | Overall: $totalPct ($dlMB / $totMB) at $speedMBs | ETA: $etaStr | fileId=$partFileId")
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
                        val nextChatId = item.partChatIds.getOrNull(item.currentPartIndex) ?: 0L
                        val nextMsgId = item.partMessageIds.getOrNull(item.currentPartIndex) ?: 0L
                        if (nextFileId != 0) {
                            try {
                                if (nextChatId != 0L && nextMsgId != 0L) {
                                    TelegramClient.sendRequest(TdApi.AddFileToDownloads(nextFileId, nextChatId, nextMsgId, 32))
                                } else {
                                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                        req.fileId = nextFileId
                                        req.priority = 32
                                        req.offset = 0
                                        req.limit = 0
                                        req.synchronous = false
                                    })
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed DownloadFile for next part $nextFileId", e)
                            }
                        }
                    }
                } else {
                    val partKey = "${item.id}_part_$idx"
                    val lastBytes = lastBytesCountMap[partKey] ?: -1L
                    if (fileObj.local.downloadedSize > lastBytes) {
                        lastBytesCountMap[partKey] = fileObj.local.downloadedSize
                        lastBytesIncreaseTimeMap[partKey] = now
                    } else if (fileObj.local.downloadedSize > 0 && !fileObj.local.isDownloadingCompleted) {
                        val lastIncrease = lastBytesIncreaseTimeMap[partKey] ?: now
                        val stallDuration = now - lastIncrease
                        val isNearComp = (expectedPartSize > 0 && fileObj.local.downloadedSize >= (expectedPartSize - 20 * 1024 * 1024))
                        if (stallDuration > 25000L && !isNearComp) {
                            lastBytesIncreaseTimeMap[partKey] = now
                            TeleflixLogger.log(TAG, "[DownloadManager] Multipart frozen socket detected for '${item.title}' (Part ${idx + 1}/${item.partFileIds.size}) at ${fileObj.local.downloadedSize} bytes (stalled >25s). Soft-resetting connection for partFileId=$partFileId...")
                            try {
                                TelegramClient.sendRequest(TdApi.ToggleDownloadIsPaused(partFileId, true))
                                TelegramClient.sendRequest(TdApi.ToggleDownloadIsPaused(partFileId, false))
                                val res = TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                    req.fileId = partFileId
                                    req.priority = 32
                                    req.offset = fileObj.local.downloadedSize.coerceAtLeast(0L)
                                    req.limit = 0
                                    req.synchronous = false
                                })
                                TeleflixLogger.log(TAG, "[DownloadManager] Soft-reset connection for partFileId=$partFileId: res=${res?.javaClass?.simpleName}")
                            } catch (e: Exception) {
                                TeleflixLogger.log(TAG, "Failed stall recovery download request for partFileId=$partFileId: ${e.message}", isError = true)
                            }
                        }
                    }
                }

                val lastPing = lastDownloadRetryTimeMap[partFileId] ?: 0L
                val isNearCompletion = (expectedPartSize > 0 && fileObj.local.downloadedSize >= (expectedPartSize - 20 * 1024 * 1024))

                if (!fileObj.local.isDownloadingCompleted && !fileObj.local.isDownloadingActive && !isNearCompletion) {
                    if (now - lastPing > 15000L) {
                        lastDownloadRetryTimeMap[partFileId] = now
                        try {
                            TelegramClient.sendRequest(TdApi.ToggleDownloadIsPaused(partFileId, false))
                            val res = if (fileObj.local.downloadedSize == 0L && chatId != 0L && messageId != 0L) {
                                TelegramClient.sendRequest(TdApi.AddFileToDownloads(partFileId, chatId, messageId, 32))
                            } else {
                                TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                    req.fileId = partFileId
                                    req.priority = 32
                                    req.offset = fileObj.local.downloadedSize.coerceAtLeast(0L)
                                    req.limit = 0
                                    req.synchronous = false
                                })
                            }
                            TeleflixLogger.log(TAG, "Re-triggered multipart active download for partFileId=$partFileId at ${fileObj.local.downloadedSize} bytes: res=${res?.javaClass?.simpleName}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed DownloadFile retry for part $partFileId", e)
                            if (chatId != 0L && messageId != 0L) {
                                try {
                                    val msg = TelegramClient.sendRequest(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message
                                    val freshId = extractFileIdFromMessage(msg)
                                    if (freshId != null && freshId != 0 && freshId != partFileId) {
                                        item.partFileIds[idx] = freshId
                                        item.fileId = freshId
                                        TelegramClient.sendRequest(TdApi.AddFileToDownloads(freshId, chatId, messageId, 32))
                                        TeleflixLogger.log(TAG, "Auto-refreshed stale multipart partFileId=$partFileId to fresh fileId=$freshId for '${item.title}'")
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                // Pre-trigger the next split part concurrently in the background so TDLib downloads parts in parallel
                if (idx + 1 < item.partFileIds.size) {
                    val nextPartId = item.partFileIds[idx + 1]
                    val nextChatId = item.partChatIds.getOrNull(idx + 1) ?: 0L
                    val nextMsgId = item.partMessageIds.getOrNull(idx + 1) ?: 0L
                    val lastNextPing = lastDownloadRetryTimeMap[nextPartId] ?: 0L
                    if (now - lastNextPing > 20000L && nextPartId != 0) {
                        lastDownloadRetryTimeMap[nextPartId] = now
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                if (nextChatId != 0L && nextMsgId != 0L) {
                                    TelegramClient.sendRequest(TdApi.AddFileToDownloads(nextPartId, nextChatId, nextMsgId, 24))
                                } else {
                                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                        req.fileId = nextPartId
                                        req.priority = 24
                                        req.offset = 0
                                        req.limit = 0
                                        req.synchronous = false
                                    })
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                updateFlow()
            } else {
                val key = "${item.id}_part_$idx"
                val failures = (consecutiveFetchFailuresMap[key] ?: 0) + 1
                consecutiveFetchFailuresMap[key] = failures
                if (failures >= 15) {
                    Log.e(TAG, "Multipart item ${item.id} part index $idx cannot resolve valid fileId after $failures attempts. Marking FAILED.")
                    item.status = DownloadStatus.FAILED
                    saveToPrefs(context)
                    updateFlow()
                } else {
                    TeleflixLogger.log(TAG, "Multipart item ${item.id} part index $idx temporary GetFile failure (attempt $failures/15) for partFileId=$partFileId. Retrying...")
                }
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
                resetSpeedTracking(item.id)
                item.partFileIds.forEach { partId ->
                    if (partId != 0) {
                        runCatching { TelegramClient.deleteFile(partId) }
                    }
                }
                saveToPrefs(context)
                updateFlow()
                TeleflixLogger.log(TAG, "Successfully merged all ${item.partFileIds.size} parts and purged TDLib part caches for '${item.title}'")
                processNextQueuedItem(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error merging multipart download: ${e.message}", e)
                item.status = DownloadStatus.FAILED
                saveToPrefs(context)
                updateFlow()
                processNextQueuedItem(context)
            }
        }
    }

    fun pauseDownload(context: Context, downloadId: String) {
        synchronized(downloadsMap) {
            val item = downloadsMap[downloadId] ?: return
            if (item.status == DownloadStatus.DOWNLOADING) {
                item.status = DownloadStatus.PAUSED
                item.speedBytesPerSec = 0L
                resetSpeedTracking(downloadId)
                saveToPrefs(context)
                updateFlow()

                CoroutineScope(Dispatchers.IO).launch {
                    val activeId = if (item.isMultiPart) item.partFileIds.getOrNull(item.currentPartIndex) ?: item.fileId else item.fileId
                    if (activeId != 0) {
                        try {
                            TelegramClient.sendRequest(TdApi.ToggleDownloadIsPaused(activeId, true))
                            TelegramClient.sendRequest(TdApi.CancelDownloadFile(activeId, false))
                        } catch (_: Exception) {}
                    }
                    TeleflixLogger.log(TAG, "Download PAUSED for '${item.title}'")
                    processNextQueuedItem(context)
                }
            }
        }
    }

    fun resumeDownload(context: Context, downloadId: String) {
        synchronized(downloadsMap) {
            val item = downloadsMap[downloadId] ?: return
            if (item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.FAILED || item.status == DownloadStatus.QUEUED) {
                val maxConcurrent = getMaxConcurrentDownloads(context)
                val otherDownloading = downloadsMap.values.filter { it.id != downloadId && it.status == DownloadStatus.DOWNLOADING }
                if (otherDownloading.size >= maxConcurrent) {
                    val toDemote = otherDownloading.maxByOrNull { it.addedTime }
                    if (toDemote != null) {
                        toDemote.status = DownloadStatus.QUEUED
                        toDemote.speedBytesPerSec = 0L
                        val activeOtherId = if (toDemote.isMultiPart) toDemote.partFileIds.getOrNull(toDemote.currentPartIndex) ?: toDemote.fileId else toDemote.fileId
                        if (activeOtherId != 0) {
                            CoroutineScope(Dispatchers.IO).launch {
                                try { TelegramClient.sendRequest(TdApi.ToggleDownloadIsPaused(activeOtherId, true)) } catch (_: Exception) {}
                            }
                        }
                    }
                }

                item.status = DownloadStatus.DOWNLOADING
                resetSpeedTracking(downloadId)
                saveToPrefs(context)
                updateFlow()

                val activeId = if (item.isMultiPart) item.partFileIds.getOrNull(item.currentPartIndex) ?: item.fileId else item.fileId
                val chatId = if (item.isMultiPart) item.partChatIds.getOrNull(item.currentPartIndex) ?: item.chatId else item.chatId
                val messageId = if (item.isMultiPart) item.partMessageIds.getOrNull(item.currentPartIndex) ?: item.messageId else item.messageId

                if (activeId != 0 || (chatId != 0L && messageId != 0L)) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            var targetId = activeId
                            if (chatId != 0L && messageId != 0L) {
                                try {
                                    val msg = TelegramClient.sendRequest(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message
                                    val freshId = extractFileIdFromMessage(msg)
                                    if (freshId != null && freshId != 0) {
                                        targetId = freshId
                                        item.fileId = freshId
                                        if (item.isMultiPart && item.partFileIds.size > item.currentPartIndex) {
                                            item.partFileIds[item.currentPartIndex] = freshId
                                        }
                                        TeleflixLogger.log(TAG, "Pre-warmed message and resolved targetId=$freshId on resume for '${item.title}'")
                                    }
                                } catch (e: Exception) {
                                    TeleflixLogger.log(TAG, "GetMessage failed during resumeDownload for $chatId/$messageId: ${e.message}", isError = true)
                                }
                            }
                            if (targetId != 0) {
                                lastDownloadRetryTimeMap[targetId] = System.currentTimeMillis()
                                try { TelegramClient.sendRequest(TdApi.ToggleDownloadIsPaused(targetId, false)) } catch (_: Exception) {}
                                val res = if (chatId != 0L && messageId != 0L) {
                                    TelegramClient.sendRequest(TdApi.AddFileToDownloads(targetId, chatId, messageId, 32))
                                } else {
                                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                                        req.fileId = targetId
                                        req.priority = 32
                                        req.offset = 0
                                        req.limit = 0
                                        req.synchronous = false
                                    })
                                }
                                TeleflixLogger.log(TAG, "Resume AddFileToDownloads/DownloadFile for $targetId returned: ${res?.javaClass?.simpleName}")
                            }
                        } catch (e: Exception) {
                            TeleflixLogger.log(TAG, "Failed resume download request for activeId=$activeId: ${e.message}", isError = true)
                        }
                        TeleflixLogger.log(TAG, "Download RESUMED for '${item.title}'")
                    }
                }
                TeleflixDownloadService.start(context)
                startDownloadLoop(context)
            }
        }
    }

    private fun processNextQueuedItem(context: Context) {
        synchronized(downloadsMap) {
            val maxConcurrent = getMaxConcurrentDownloads(context)
            val currentActive = downloadsMap.values.count { it.status == DownloadStatus.DOWNLOADING }
            val slotsAvailable = maxConcurrent - currentActive
            if (slotsAvailable > 0) {
                val nextQueuedItems = downloadsMap.values
                    .filter { it.status == DownloadStatus.QUEUED }
                    .sortedBy { it.addedTime }
                    .take(slotsAvailable)
                for (nextQueued in nextQueuedItems) {
                    TeleflixLogger.log(TAG, "Auto-starting next queued download '${nextQueued.title}' ($slotsAvailable parallel slot(s) available)")
                    resumeDownload(context, nextQueued.id)
                }
            }
        }
    }

    fun cancelDownload(context: Context, downloadId: String) {
        synchronized(downloadsMap) {
            val item = downloadsMap.remove(downloadId) ?: return
            resetSpeedTracking(downloadId)
            saveToPrefs(context)
            updateFlow()

            CoroutineScope(Dispatchers.IO).launch {
                val activeId = if (item.isMultiPart) item.partFileIds.getOrNull(item.currentPartIndex) ?: item.fileId else item.fileId
                if (activeId != 0) {
                    try {
                        TelegramClient.sendRequest(TdApi.RemoveFileFromDownloads(activeId, true))
                        TelegramClient.sendRequest(TdApi.CancelDownloadFile(activeId, true))
                    } catch (_: Exception) {}
                }
                TeleflixLogger.log(TAG, "Download CANCELLED for '${item.title}'")
                processNextQueuedItem(context)
            }
            val f = File(item.localPath)
            if (f.exists()) f.delete()
            for (i in 0 until item.partFileIds.size) {
                val temp = getPartTempFile(context, item.id, i)
                if (temp.exists()) temp.delete()
            }
        }
    }

    fun deleteDownloadedFile(context: Context, downloadId: String) {
        synchronized(downloadsMap) {
            val item = downloadsMap.remove(downloadId) ?: return
            resetSpeedTracking(downloadId)
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
                // Ignore downloads that are already finalized
                if (item.status == DownloadStatus.COMPLETED || item.status == DownloadStatus.FAILED) {
                    continue
                }

                // Strict match: Never match fileId == 0 to random incoming TDLib file events (thumbnails/probes)
                val matchesSingle = !item.isMultiPart && (item.fileId != 0 && item.fileId == file.id)
                val matchesMulti = item.isMultiPart && item.partFileIds.contains(file.id)

                if (matchesSingle || matchesMulti) {
                    if (matchesSingle) {
                        item.downloadedBytes = file.local.downloadedSize
                        val reportedSize = if (file.expectedSize > 0) file.expectedSize else if (file.size > 0) file.size else 0L
                        if (reportedSize > item.totalBytes || item.totalBytes == 0L) {
                            item.totalBytes = reportedSize
                        }
                    } else if (matchesMulti) {
                        val partIdx = item.partFileIds.indexOf(file.id)
                        if (partIdx >= 0 && partIdx == item.currentPartIndex) {
                            val sumCompletedParts = item.partFileSizes.take(partIdx).sum()
                            item.downloadedBytes = sumCompletedParts + file.local.downloadedSize
                        }
                    }

                    updateDownloadSpeed(item, now)

                    val lastLogTime = lastProgressLogTimeMap[item.id] ?: 0L
                    if (now - lastLogTime >= 5000L && item.status == DownloadStatus.DOWNLOADING && item.downloadedBytes > 0) {
                        lastProgressLogTimeMap[item.id] = now
                        val pct = if (item.totalBytes > 0) String.format(java.util.Locale.US, "%.1f%%", (item.downloadedBytes.toDouble() / item.totalBytes) * 100) else "N/A"
                        val dlMB = String.format(java.util.Locale.US, "%.2f MB", item.downloadedBytes / (1024.0 * 1024.0))
                        val totMB = if (item.totalBytes > 0) String.format(java.util.Locale.US, "%.2f MB", item.totalBytes / (1024.0 * 1024.0)) else "Unknown"
                        val speedMBs = String.format(java.util.Locale.US, "%.2f MB/s", item.speedBytesPerSec / (1024.0 * 1024.0))
                        val remainingBytes = (item.totalBytes - item.downloadedBytes).coerceAtLeast(0L)
                        val etaStr = formatEtaTime(remainingBytes, item.speedBytesPerSec)
                        TeleflixLogger.log(TAG, "Download progress for '${item.title}': $pct ($dlMB / $totMB) at $speedMBs | ETA: $etaStr | fileId=${file.id}")
                    }

                    if (matchesSingle) {
                        val tdlibPath = file.local?.path ?: ""

                        val isCompleted = item.status == DownloadStatus.DOWNLOADING &&
                            (file.local.isDownloadingCompleted || (item.totalBytes > 0 && file.local.downloadedSize >= item.totalBytes))

                        if (isCompleted) {
                            var copySuccess = false
                            if (tdlibPath.isNotBlank()) {
                                try {
                                    val source = File(tdlibPath)
                                    val cleanPath = cleanFilePath(item.localPath)
                                    item.localPath = cleanPath
                                    val target = File(cleanPath)

                                    if (source.exists() && source.length() > 0) {
                                        // Verify size sanity: ensure source isn't truncated before declaring complete
                                        if (item.totalBytes > 0 && source.length() < (item.totalBytes * 0.95)) {
                                            TeleflixLogger.log(TAG, "Source file size (${source.length()}) significantly less than expected totalBytes (${item.totalBytes}) for '${item.title}'. Postponing copy...", isError = true)
                                        } else {
                                            if (target.parentFile?.exists() == false) {
                                                target.parentFile?.mkdirs()
                                            }
                                            if (source.absolutePath != target.absolutePath) {
                                                fastCopyFile(source, target)
                                                if (source.exists() && target.length() < source.length()) {
                                                    TeleflixLogger.log(TAG, "fastCopyFile incomplete (${target.length()}/${source.length()} bytes). Falling back to InputStream copy...")
                                                    source.copyTo(target, overwrite = true)
                                                }
                                                TeleflixLogger.log(TAG, "Copied completed download to ${target.absolutePath} (${target.length()} bytes)")
                                                if (target.exists() && target.length() >= source.length() && target.length() > 0) {
                                                    copySuccess = true
                                                    runCatching { TelegramClient.deleteFile(item.fileId) }
                                                    runCatching { source.delete() }
                                                    TeleflixLogger.log(TAG, "Purged internal TDLib download cache for completed fileId=${item.fileId}")
                                                } else {
                                                    TeleflixLogger.log(TAG, "Preserving source TDLib cache: target file size mismatch (${target.length()} vs ${source.length()} bytes)", isError = true)
                                                }
                                            } else {
                                                copySuccess = true
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    TeleflixLogger.log(TAG, "Failed copying completed download: ${e.message}", isError = true)
                                }
                            }

                            if (copySuccess || tdlibPath.isBlank()) {
                                item.status = DownloadStatus.COMPLETED
                                if (item.totalBytes > 0) item.downloadedBytes = item.totalBytes
                                item.speedBytesPerSec = 0L
                                resetSpeedTracking(item.id)
                                TeleflixLogger.log(TAG, "Download COMPLETED for '${item.title}': downloaded=${item.downloadedBytes}/${item.totalBytes} bytes")
                                processNextQueuedItem(context)
                            }
                        } else if (file.local.isDownloadingActive && item.status != DownloadStatus.DOWNLOADING) {
                            item.status = DownloadStatus.DOWNLOADING
                        }
                    } else if (matchesMulti && file.local.isDownloadingActive && item.status == DownloadStatus.PAUSED) {
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

    private fun formatEtaTime(remainingBytes: Long, speedBytesPerSec: Long): String {
        if (remainingBytes <= 0L) return "0s"
        if (speedBytesPerSec <= 0L) return "Calculating..."
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

    private fun updateDownloadSpeed(item: DownloadItem, now: Long) {
        val lastCalcTime = lastSpeedCalcTimeMap[item.id] ?: 0L
        if (lastCalcTime == 0L) {
            lastSpeedCalcTimeMap[item.id] = now
            lastDownloadedBytesMap[item.id] = item.downloadedBytes
            if (item.downloadedBytes > 0) {
                lastBytesIncreaseTimeMap[item.id] = now
                lastBytesCountMap[item.id] = item.downloadedBytes
            }
            return
        }

        val elapsed = now - lastCalcTime
        if (elapsed >= 1000L) {
            val lastBytes = lastDownloadedBytesMap[item.id] ?: item.downloadedBytes
            val bytesDiff = item.downloadedBytes - lastBytes

            if (bytesDiff > 0) {
                val instantSpeed = (bytesDiff * 1000) / elapsed
                val prevSpeed = item.speedBytesPerSec
                item.speedBytesPerSec = if (prevSpeed <= 0L) {
                    instantSpeed
                } else {
                    (0.35 * instantSpeed + 0.65 * prevSpeed).toLong()
                }
                lastDownloadedBytesMap[item.id] = item.downloadedBytes
                lastSpeedCalcTimeMap[item.id] = now
                lastBytesIncreaseTimeMap[item.id] = now
            } else {
                val lastIncrease = lastBytesIncreaseTimeMap[item.id] ?: now
                val stallDuration = now - lastIncrease
                if (stallDuration > 15000L) {
                    item.speedBytesPerSec = 0L
                } else {
                    item.speedBytesPerSec = (item.speedBytesPerSec * 0.85).toLong()
                }
                lastSpeedCalcTimeMap[item.id] = now
            }
        }
    }

    private fun resetSpeedTracking(downloadId: String) {
        lastSpeedCalcTimeMap.remove(downloadId)
        lastDownloadedBytesMap.remove(downloadId)
        lastBytesIncreaseTimeMap.remove(downloadId)
        lastBytesCountMap.remove(downloadId)
        lastProgressLogTimeMap.remove(downloadId)
        consecutiveFetchFailuresMap.remove(downloadId)
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
