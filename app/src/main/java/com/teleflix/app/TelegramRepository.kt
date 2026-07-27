package com.teleflix.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.TdApi
import java.io.File

data class TelegramVideoMessage(
    val messageId: Long,
    val chatId: Long,
    val fileName: String,
    val fileId: Int,
    val fileSize: Long,
    val duration: Int,
    val mimeType: String,
    val caption: String,
    val thumbnailFileId: Int? = null
)

data class ForumTopicData(
    val topicId: Int,
    val displayName: String,
    val channelTitle: String,
    val thumbnailChatId: Long = 0L,
    val thumbnailMessageId: Long = 0L
)

data class SplitFileGroup(
    val baseName: String,
    val parts: List<TelegramVideoMessage>,  // ordered by part number
    val totalSize: Long
)

data class ZipFileEntry(
    val message: TelegramVideoMessage,
    val innerFileName: String  // will be discovered during streaming
)

object TelegramRepository {
    private const val TAG = "TelegramRepository"

    private var appContext: Context? = null

    val authState: StateFlow<TelegramAuthState> = TelegramClient.authState

    fun sessionMarker(context: Context) = File(context.filesDir, "tdlib_session_ok")

    fun wipeTdlibFiles(context: Context) {
        sessionMarker(context).delete()
        File(context.filesDir, "tdlib").deleteRecursively()
        File(context.filesDir, "tdlib_files").deleteRecursively()
        TelegramClient.clearNativeLibraryCache(context)
        Log.d(TAG, "TDLib database and native library wiped")
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        TelegramStreamingProxy.prefetchSizeMb = getBufferSizeMb(context)
        TelegramStreamingProxy.start()
        
        // Only wipe old media cache on startup if the user chose "No Cache" (limit <= 1)
        if (getCacheLimitMb(context) <= 1L) {
            clearCache(context)
        }

        TelegramClient.initialize(context)
    }

    fun getContext(): Context {
        return appContext ?: throw Exception("TelegramRepository is not initialized with context")
    }

    fun isAuthenticated(): Boolean {
        return TelegramClient.authState.value is TelegramAuthState.Ready
    }

    suspend fun waitUntilAuthenticated(): Boolean {
        var elapsed = 0L
        val timeoutMs = 3000L
        while (elapsed < timeoutMs) {
            val state = TelegramClient.authState.value
            if (state is TelegramAuthState.Ready) return true
            if (state is TelegramAuthState.WaitPhone || state is TelegramAuthState.WaitCode || state is TelegramAuthState.WaitPassword) return false
            kotlinx.coroutines.delay(100)
            elapsed += 100
        }
        return false
    }

    fun startAuth(context: Context) = TelegramClient.initialize(context)
    fun requestQrCode() = TelegramClient.requestQrCode()
    fun submitPhone(phone: String) = TelegramClient.submitPhone(phone)
    fun submitCode(code: String) = TelegramClient.submitCode(code)
    fun submitPassword(password: String) = TelegramClient.submitPassword(password)

    fun disconnect(context: Context) {
        TelegramClient.reset()
        wipeTdlibFiles(context)
    }

    suspend fun getCacheSize(context: Context): Long {
        return try {
            val stats = TelegramClient.sendRequest(TdApi.GetStorageStatisticsFast()) as? TdApi.StorageStatisticsFast
            val tdlibSize = stats?.filesSize ?: 0L
            if (tdlibSize > 0L) {
                tdlibSize
            } else {
                val dir1 = File(context.filesDir, "tdlib_files")
                val dir2 = File(context.filesDir, "tdlib")
                val size1 = if (dir1.exists()) dir1.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L
                val size2 = if (dir2.exists()) dir2.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L
                size1 + size2
            }
        } catch (e: Exception) {
            val dir1 = File(context.filesDir, "tdlib_files")
            val dir2 = File(context.filesDir, "tdlib")
            val size1 = if (dir1.exists()) dir1.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L
            val size2 = if (dir2.exists()) dir2.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L
            size1 + size2
        }
    }

    fun clearCache(context: Context) {
        TelegramClient.optimizeStorage()
        
        // Also try to manually wipe just in case
        try {
            File(context.filesDir, "tdlib_files").listFiles()?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {}
    }

    suspend fun getChatId(identifier: String): Long? {
        var clean = identifier.trim()
        if (clean.isEmpty()) return null
        if (clean.startsWith("@-") || (clean.startsWith("@") && clean.substring(1).toLongOrNull() != null)) {
            clean = clean.removePrefix("@")
        }

        clean.toLongOrNull()?.let { numericId ->
            // For raw numeric IDs, ensure TDLib has the chat loaded in its local cache.
            // Without this, archived or unloaded chats will fail on SearchChatMessages.
            try {
                TelegramClient.sendRequest(TdApi.GetChat(numericId))
            } catch (e: Exception) {
                Log.w(TAG, "GetChat failed for $numericId, trying to open: ${e.message}")
                // Try loading archive chats in case they haven't been loaded yet
                try {
                    TelegramClient.sendRequest(TdApi.LoadChats(TdApi.ChatListArchive(), 100))
                } catch (_: Exception) {}
                // Retry after loading
                try {
                    TelegramClient.sendRequest(TdApi.GetChat(numericId))
                } catch (e2: Exception) {
                    Log.e(TAG, "GetChat still failed for $numericId after loading archive: ${e2.message}")
                }
            }
            return numericId
        }

        val username = clean.removePrefix("@")
        // Try public search first (works for public channels)
        try {
            val chat = TelegramClient.sendRequest(TdApi.SearchPublicChat(username)) as? TdApi.Chat
            if (chat != null) return chat.id
        } catch (e: Exception) {
            Log.w(TAG, "SearchPublicChat failed for $username: ${e.message}")
        }

        // Fallback: search among all chats the user has joined (works for private/archive channels)
        try {
            val chats = TelegramClient.sendRequest(TdApi.SearchChatsOnServer(username, 5)) as? TdApi.Chats
            if (chats != null && chats.chatIds.isNotEmpty()) {
                return chats.chatIds.first()
            }
        } catch (e: Exception) {
            Log.w(TAG, "SearchChatsOnServer failed for $username: ${e.message}")
        }

        Log.e(TAG, "Could not resolve channel: $identifier")
        return null
    }

    suspend fun getChannelTitle(identifier: String): String {
        val chatId = getChatId(identifier) ?: return identifier
        return try {
            val chat = TelegramClient.sendRequest(TdApi.GetChat(chatId)) as? TdApi.Chat
            if (chat != null && chat.title.isNotBlank()) chat.title else identifier
        } catch (_: Exception) {
            identifier
        }
    }

    suspend fun getChannelVideos(identifier: String, page: Int, limit: Int = 50): Pair<String, List<TelegramVideoMessage>>? {
        val chatId = getChatId(identifier) ?: return null

        var title = identifier
        try {
            val chat = TelegramClient.sendRequest(TdApi.GetChat(chatId)) as? TdApi.Chat
            if (chat != null) title = chat.title
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load title for channel $identifier: ${e.message}")
        }
        
        val results = mutableListOf<TelegramVideoMessage>()
        val seen = mutableSetOf<Pair<String, Long>>()

        val prefs = TelegramRepository.getContext().getSharedPreferences("telegram_pagination", android.content.Context.MODE_PRIVATE)
        if (page == 1) {
            // Clear old cursors
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith("${chatId}_") }.forEach { remove(it) }
            }.apply()
        }

        var currentDocCursor = if (page == 1) 0L else prefs.getLong("${chatId}_doc_page_$page", 0L)
        var currentVidCursor = if (page == 1) 0L else prefs.getLong("${chatId}_vid_page_$page", 0L)
        var currentAudCursor = if (page == 1) 0L else prefs.getLong("${chatId}_aud_page_$page", 0L)

        // Only fetch if cursor is not -1 (which we'll use to indicate end of stream)
        var fetchDoc = currentDocCursor != -1L
        var fetchVid = currentVidCursor != -1L
        var fetchAud = currentAudCursor != -1L

        // Loop until we find at least one valid video/document or reach the end of history.
        // This prevents pagination from breaking if a chunk of 50 messages contains only non-video documents (e.g. PDFs/SRTs).
        while (results.isEmpty() && (fetchDoc || fetchVid || fetchAud)) {
            if (fetchDoc) {
                try {
                    val historyResult = TelegramClient.sendRequest(TdApi.SearchChatMessages().also { req ->
                        req.chatId = chatId
                        req.query = ""
                        req.senderId = null
                        req.fromMessageId = currentDocCursor
                        req.offset = 0
                        req.limit = limit
                        req.filter = TdApi.SearchMessagesFilterDocument()
                        req.topicId = null
                    })
                    
                    val found = (historyResult as? TdApi.FoundChatMessages)
                    if (found != null) {
                        currentDocCursor = if (found.nextFromMessageId == 0L) -1L else found.nextFromMessageId
                        prefs.edit().putLong("${chatId}_doc_page_${page + 1}", currentDocCursor).apply()
                        fetchDoc = currentDocCursor != -1L
                        for (msg in found.messages) extractMediaMessage(msg, seen, results)
                    } else {
                        fetchDoc = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Search document messages failed: ${e.message}")
                    fetchDoc = false
                }
            }

            if (fetchVid) {
                try {
                    val historyResult = TelegramClient.sendRequest(TdApi.SearchChatMessages().also { req ->
                        req.chatId = chatId
                        req.query = ""
                        req.senderId = null
                        req.fromMessageId = currentVidCursor
                        req.offset = 0
                        req.limit = limit
                        req.filter = TdApi.SearchMessagesFilterVideo()
                        req.topicId = null
                    })
                    
                    val found = (historyResult as? TdApi.FoundChatMessages)
                    if (found != null) {
                        currentVidCursor = if (found.nextFromMessageId == 0L) -1L else found.nextFromMessageId
                        prefs.edit().putLong("${chatId}_vid_page_${page + 1}", currentVidCursor).apply()
                        fetchVid = currentVidCursor != -1L
                        for (msg in found.messages) extractMediaMessage(msg, seen, results)
                    } else {
                        fetchVid = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Search video messages failed: ${e.message}")
                    fetchVid = false
                }
            }

            if (fetchAud) {
                try {
                    val historyResult = TelegramClient.sendRequest(TdApi.SearchChatMessages().also { req ->
                        req.chatId = chatId
                        req.query = ""
                        req.senderId = null
                        req.fromMessageId = currentAudCursor
                        req.offset = 0
                        req.limit = limit
                        req.filter = TdApi.SearchMessagesFilterAudio()
                        req.topicId = null
                    })
                    
                    val found = (historyResult as? TdApi.FoundChatMessages)
                    if (found != null) {
                        currentAudCursor = if (found.nextFromMessageId == 0L) -1L else found.nextFromMessageId
                        prefs.edit().putLong("${chatId}_aud_page_${page + 1}", currentAudCursor).apply()
                        fetchAud = currentAudCursor != -1L
                        for (msg in found.messages) extractMediaMessage(msg, seen, results)
                    } else {
                        fetchAud = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Search audio messages failed: ${e.message}")
                    fetchAud = false
                }
            }
        }

        
        results.sortByDescending { it.messageId }

        return title to results
    }

    suspend fun isForumChannel(chatId: Long): Boolean {
        return try {
            val chat = TelegramClient.sendRequest(TdApi.GetChat(chatId)) as? TdApi.Chat ?: return false
            val supergroupType = chat.type as? TdApi.ChatTypeSupergroup ?: return false
            val supergroup = TelegramClient.sendRequest(TdApi.GetSupergroup(supergroupType.supergroupId)) as? TdApi.Supergroup
            supergroup?.isForum == true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check if forum: ${e.message}")
            false
        }
    }

    suspend fun getForumTopics(chatId: Long): List<ForumTopicData> {
        val results = mutableListOf<ForumTopicData>()
        var channelTitle = ""
        try {
            val chat = TelegramClient.sendRequest(TdApi.GetChat(chatId)) as? TdApi.Chat
            if (chat != null) channelTitle = chat.title
        } catch (e: Exception) {}

        try {
            var offsetDate = 0
            var offsetMessageId = 0L
            var offsetTopicId = 0
            var hasMore = true

            while (hasMore) {
                val topicsResult = TelegramClient.sendRequest(TdApi.GetForumTopics(
                    chatId, "", offsetDate, offsetMessageId, offsetTopicId, 100
                )) as? TdApi.ForumTopics ?: break

                for (topic in topicsResult.topics) {
                    val info = topic.info
                    if (info.isHidden) continue

                    val emoji = getTopicEmoji(info)
                    val displayName = if (emoji.isNotEmpty()) "$emoji ${info.name}" else info.name
                    results.add(ForumTopicData(
                        topicId = info.forumTopicId,
                        displayName = displayName,
                        channelTitle = channelTitle
                    ))
                }

                if (topicsResult.topics.size < 100 || topicsResult.nextOffsetDate == 0) {
                    hasMore = false
                } else {
                    offsetDate = topicsResult.nextOffsetDate
                    offsetMessageId = topicsResult.nextOffsetMessageId
                    offsetTopicId = topicsResult.nextOffsetForumTopicId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get forum topics: ${e.message}")
        }

        // Fetch thumbnail from first video in each topic
        for (i in results.indices) {
            val topicData = results[i]
            try {
                val topicFilter = TdApi.MessageTopicForum(topicData.topicId)
                for (filter in listOf(TdApi.SearchMessagesFilterVideo(), TdApi.SearchMessagesFilterDocument(), TdApi.SearchMessagesFilterAudio())) {
                    val searchResult = TelegramClient.sendRequest(TdApi.SearchChatMessages().also { req ->
                        req.chatId = chatId
                        req.topicId = topicFilter
                        req.query = ""
                        req.senderId = null
                        req.fromMessageId = 0
                        req.offset = 0
                        req.limit = 1
                        req.filter = filter
                    })
                    val found = (searchResult as? TdApi.FoundChatMessages)
                    if (found != null && found.messages.isNotEmpty()) {
                        val msg = found.messages[0]
                        results[i] = topicData.copy(
                            thumbnailChatId = chatId,
                            thumbnailMessageId = msg.id
                        )
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch thumbnail for topic ${topicData.topicId}: ${e.message}")
            }
        }

        return results
    }

    private fun getTopicEmoji(info: TdApi.ForumTopicInfo): String {
        if (info.isGeneral) return "📋"
        val icon = info.icon ?: return ""
        if (icon.customEmojiId != 0L) {
            // We can't easily render custom emojis in text, use a colored circle as fallback
            return getColorEmoji(icon.color)
        }
        return getColorEmoji(icon.color)
    }

    private fun getColorEmoji(color: Int): String {
        // Map TDLib topic icon colors to circle emojis
        return when (color) {
            0x6FB9F0 -> "🔵"
            0xFFD67E -> "🟡"
            0xCB86DB -> "🟣"
            0x8EEE98 -> "🟢"
            0xFF93B2 -> "🩷"
            0xFB6F5F -> "🔴"
            else -> "📁"
        }
    }

    suspend fun getTopicVideos(chatId: Long, topicId: Int, page: Int, limit: Int = 50): List<TelegramVideoMessage> {
        val results = mutableListOf<TelegramVideoMessage>()
        val seen = mutableSetOf<Pair<String, Long>>()

        val prefs = getContext().getSharedPreferences("telegram_pagination", android.content.Context.MODE_PRIVATE)
        if (page == 1) {
            // Clear old cursors for this topic
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith("${chatId}_topic${topicId}_") }.forEach { remove(it) }
            }.apply()
        }

        var currentDocCursor = if (page == 1) 0L else prefs.getLong("${chatId}_topic${topicId}_doc_page_$page", 0L)
        var currentVidCursor = if (page == 1) 0L else prefs.getLong("${chatId}_topic${topicId}_vid_page_$page", 0L)
        var currentAudCursor = if (page == 1) 0L else prefs.getLong("${chatId}_topic${topicId}_aud_page_$page", 0L)

        var fetchDoc = currentDocCursor != -1L
        var fetchVid = currentVidCursor != -1L
        var fetchAud = currentAudCursor != -1L

        if (!fetchDoc && !fetchVid && !fetchAud && page > 1) return results // Reached end of history for all filters

        val topicFilter = TdApi.MessageTopicForum(topicId)

        // Use dedicated Video and Document filters so TDLib only returns matching messages
        while (results.isEmpty() && (fetchDoc || fetchVid || fetchAud)) {
            if (fetchDoc) {
                try {
                    val searchResult = TelegramClient.sendRequest(TdApi.SearchChatMessages().also { req ->
                        req.chatId = chatId
                        req.topicId = topicFilter
                        req.query = ""
                        req.senderId = null
                        req.fromMessageId = currentDocCursor
                        req.offset = 0
                        req.limit = limit
                        req.filter = TdApi.SearchMessagesFilterDocument()
                    })

                    val found = (searchResult as? TdApi.FoundChatMessages)
                    if (found != null) {
                        currentDocCursor = if (found.nextFromMessageId == 0L) -1L else found.nextFromMessageId
                        fetchDoc = currentDocCursor != -1L
                        for (msg in found.messages) extractMediaMessage(msg, seen, results)
                    } else {
                        fetchDoc = false
                        currentDocCursor = -1L
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SearchChatMessages doc filter failed for topic $topicId: ${e.message}")
                    fetchDoc = false
                    currentDocCursor = -1L
                }
            }

            if (fetchVid) {
                try {
                    val searchResult = TelegramClient.sendRequest(TdApi.SearchChatMessages().also { req ->
                        req.chatId = chatId
                        req.topicId = topicFilter
                        req.query = ""
                        req.senderId = null
                        req.fromMessageId = currentVidCursor
                        req.offset = 0
                        req.limit = limit
                        req.filter = TdApi.SearchMessagesFilterVideo()
                    })

                    val found = (searchResult as? TdApi.FoundChatMessages)
                    if (found != null) {
                        currentVidCursor = if (found.nextFromMessageId == 0L) -1L else found.nextFromMessageId
                        fetchVid = currentVidCursor != -1L
                        for (msg in found.messages) extractMediaMessage(msg, seen, results)
                    } else {
                        fetchVid = false
                        currentVidCursor = -1L
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SearchChatMessages vid filter failed for topic $topicId: ${e.message}")
                    fetchVid = false
                    currentVidCursor = -1L
                }
            }
            if (fetchAud) {
                try {
                    val searchResult = TelegramClient.sendRequest(TdApi.SearchChatMessages().also { req ->
                        req.chatId = chatId
                        req.topicId = topicFilter
                        req.query = ""
                        req.senderId = null
                        req.fromMessageId = currentAudCursor
                        req.offset = 0
                        req.limit = limit
                        req.filter = TdApi.SearchMessagesFilterAudio()
                    })

                    val found = (searchResult as? TdApi.FoundChatMessages)
                    if (found != null) {
                        currentAudCursor = if (found.nextFromMessageId == 0L) -1L else found.nextFromMessageId
                        fetchAud = currentAudCursor != -1L
                        for (msg in found.messages) extractMediaMessage(msg, seen, results)
                    } else {
                        fetchAud = false
                        currentAudCursor = -1L
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SearchChatMessages aud filter failed for topic $topicId: ${e.message}")
                    fetchAud = false
                    currentAudCursor = -1L
                }
            }
        }

        // Save cursors for next page
        prefs.edit()
            .putLong("${chatId}_topic${topicId}_doc_page_${page + 1}", currentDocCursor)
            .putLong("${chatId}_topic${topicId}_vid_page_${page + 1}", currentVidCursor)
            .putLong("${chatId}_topic${topicId}_aud_page_${page + 1}", currentAudCursor)
            .apply()

        results.sortByDescending { it.messageId }
        return results
    }

    private var cachedCustomChannels: List<String> = emptyList()

    fun getCustomChannels(context: Context): List<String> {
        val prefs = context.getSharedPreferences("teleflix_tdlib_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("custom_channels", "") ?: ""
        if (raw.isBlank()) {
            cachedCustomChannels = emptyList()
            return emptyList()
        }
        val list = raw.split(",", " ", "\n", "\r", ";").map { it.trim() }.filter { it.isNotEmpty() }
        cachedCustomChannels = list
        return list
    }

    fun saveCustomChannels(context: Context, channels: List<String>) {
        val prefs = context.getSharedPreferences("teleflix_tdlib_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_channels", channels.joinToString(",")).apply()
        cachedCustomChannels = channels
    }

    fun getCacheLimitMb(context: Context): Long {
        val prefs = context.getSharedPreferences("teleflix_tdlib_prefs", Context.MODE_PRIVATE)
        return prefs.getLong("cache_limit_mb", 1L) // Default to 1MB (No Cache)
    }

    fun saveCacheLimitMb(context: Context, limit: Long) {
        val prefs = context.getSharedPreferences("teleflix_tdlib_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("cache_limit_mb", limit).apply()
    }

    fun getBufferSizeMb(context: Context): Long {
        val prefs = context.getSharedPreferences("teleflix_tdlib_prefs", Context.MODE_PRIVATE)
        return prefs.getLong("buffer_size_mb", 20L) // Default 20MB
    }

    fun saveBufferSizeMb(context: Context, limit: Long) {
        val prefs = context.getSharedPreferences("teleflix_tdlib_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("buffer_size_mb", limit).apply()
    }

    fun getApiId(context: Context): Int {
        val prefs = context.getSharedPreferences("teleflix_tdlib_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("api_id", 0) // Default 0 means unset
    }

    fun saveApiId(context: Context, apiId: Int) {
        val prefs = context.getSharedPreferences("teleflix_tdlib_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("api_id", apiId).apply()
    }

    fun getApiHash(context: Context): String {
        val prefs = context.getSharedPreferences("teleflix_tdlib_prefs", Context.MODE_PRIVATE)
        return prefs.getString("api_hash", "") ?: ""
    }

    fun saveApiHash(context: Context, apiHash: String) {
        val prefs = context.getSharedPreferences("teleflix_tdlib_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("api_hash", apiHash).apply()
    }

    suspend fun searchVideoMessages(
        query: String,
        limit: Int = 1000,
        includeAudio: Boolean = true
    ): List<TelegramVideoMessage> {
        val results = mutableListOf<TelegramVideoMessage>()
        val seen = mutableSetOf<Pair<String, Long>>()

        val filters = mutableListOf<TdApi.SearchMessagesFilter>(
            TdApi.SearchMessagesFilterDocument(),
            TdApi.SearchMessagesFilterVideo()
        )
        if (includeAudio) {
            filters.add(TdApi.SearchMessagesFilterAudio())
        }

        if (cachedCustomChannels.isEmpty()) {
            try {
                cachedCustomChannels = getCustomChannels(getContext())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load custom channels: ${e.message}")
            }
        }

        // 1. Search specifically in custom channels (works even if not joined, if public)
        for (chan in cachedCustomChannels) {
            val chatId = getChatId(chan) ?: continue
            for (filter in filters) {
                try {
                    var currentFromMessageId = 0L
                    var fetched = 0
                    while (fetched < limit) {
                        val fetchLimit = minOf(100, limit - fetched)
                        val historyResult = TelegramClient.sendRequest(TdApi.SearchChatMessages().also { req ->
                            req.chatId = chatId
                            req.query = query
                            req.senderId = null
                            req.fromMessageId = currentFromMessageId
                            req.offset = 0
                            req.limit = fetchLimit
                            req.filter = filter
                            req.topicId = null
                        })
                        val found = (historyResult as? TdApi.FoundChatMessages) ?: break
                        if (found.messages.isEmpty()) break
                        
                        for (msg in found.messages) {
                            extractMediaMessage(msg, seen, results)
                        }
                        
                        fetched += found.messages.size
                        if (found.messages.size < fetchLimit) break
                        
                        val lastId = found.messages.last().id
                        if (currentFromMessageId == lastId && found.messages.size == 1) break
                        currentFromMessageId = lastId
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SearchChatMessages error for $chan: ${e.message}")
                }
            }
        }

        // 2. Search globally across all joined chats
        for (filter in filters) {
            try {
                var currentOffset = ""
                var fetched = 0
                while (fetched < limit) {
                    val fetchLimit = minOf(100, limit - fetched)
                    val result = TelegramClient.sendRequest(TdApi.SearchMessages().also { req ->
                        req.chatList = null  // null = search all chats
                        req.query = query
                        req.offset = currentOffset
                        req.limit = fetchLimit
                        req.filter = filter
                    })
                    val found = (result as? TdApi.FoundMessages) ?: break
                    if (found.messages.isEmpty()) break
                    
                    for (msg in found.messages) {
                        extractMediaMessage(msg, seen, results)
                    }
                    
                    fetched += found.messages.size
                    currentOffset = found.nextOffset
                    if (currentOffset.isEmpty()) break
                }
            } catch (e: Exception) {
                Log.e(TAG, "SearchMessages error: ${e.message}")
            }
        }

        results.sortByDescending { it.messageId }
        return results
    }

    private fun extractMediaMessage(msg: TdApi.Message, seen: MutableSet<Pair<String, Long>>, results: MutableList<TelegramVideoMessage>) {
        when (val content = msg.content) {
            is TdApi.MessageDocument -> {
                val mime = content.document.mimeType?.lowercase() ?: ""
                val filename = content.document.fileName ?: "Default_Name.mkv"
                val ext = filename.substringAfterLast('.', "").lowercase().trim()
                val filenameLower = filename.lowercase()
                
                // Generous video format detection
                val hasVideoExt = ext in listOf("mkv", "mp4", "avi", "mov", "flv", "wmv", "webm", "m4v", "3gp", "ts", "m2ts", "vob")
                val hasVideoMime = mime.startsWith("video/") || mime.contains("matroska")
                val hasVideoKeywords = listOf("mkv", "mp4", "1080p", "720p", "480p", "4k", "hevc", "x265", "x264", "web-dl", "webrip", "bluray").any { filenameLower.contains(it) }
                
                // Audio format detection for documents sent as files
                val hasAudioExt = ext in listOf("mp3", "flac", "aac", "ogg", "opus", "wav", "wma", "m4a", "alac", "aiff", "ape", "wv")
                val hasAudioMime = mime.startsWith("audio/")

                val isArchiveOrSplit = ext in listOf("rar", "7z", "tar", "gz", "bz2")
                val isSplitFile = ext.matches(Regex("^\\d+$")) || filename.lowercase().matches(Regex(""".*\.part\d+$"""))
                
                val isNonMediaZipName = listOf("sub", "subs", "subtitle", "subtitles", "srt", "txt", "nfo", "poster", "font", "apk", "pdf", "doc").any {
                    filenameLower.startsWith(it) || filenameLower.contains("$it.zip") || filenameLower.contains("${it}_") || filenameLower.contains("-$it")
                }
                val isZipFile = ext == "zip" && !isNonMediaZipName && (
                    content.document.document.size >= 10_000_000L || // ZIP >= 10MB
                    hasVideoKeywords || 
                    listOf("movie", "series", "season", "complete", "pack", "mkv", "mp4", "hd", "720", "1080", "4k").any { filenameLower.contains(it) }
                )
                
                val isVideo = !isArchiveOrSplit && !isSplitFile && !isZipFile && (hasVideoExt || hasVideoMime || hasVideoKeywords)
                val isAudio = !isArchiveOrSplit && !isSplitFile && !isZipFile && (hasAudioExt || hasAudioMime)
                
                if (!isVideo && !isAudio && !isSplitFile && !isZipFile) return

                val key = filename to content.document.document.size
                if (seen.add(key)) {
                    results.add(TelegramVideoMessage(
                        messageId = msg.id,
                        chatId = msg.chatId,
                        fileName = filename,
                        fileId = content.document.document.id,
                        fileSize = content.document.document.size,
                        duration = 0,
                        mimeType = mime,
                        caption = content.caption?.text ?: "",
                        thumbnailFileId = content.document.thumbnail?.file?.id
                    ))
                }
            }
            is TdApi.MessageVideo -> {
                val filename = content.video.fileName ?: "Default_Name.mp4"
                val key = filename to content.video.video.size
                if (seen.add(key)) {
                    results.add(TelegramVideoMessage(
                        messageId = msg.id,
                        chatId = msg.chatId,
                        fileName = filename,
                        fileId = content.video.video.id,
                        fileSize = content.video.video.size,
                        duration = content.video.duration,
                        mimeType = content.video.mimeType ?: "video/mp4",
                        caption = content.caption?.text ?: "",
                        thumbnailFileId = content.video.thumbnail?.file?.id
                    ))
                }
            }
            is TdApi.MessageAudio -> {
                val audio = content.audio
                val title = audio.title?.takeIf { it.isNotBlank() }
                val performer = audio.performer?.takeIf { it.isNotBlank() }
                val filename = when {
                    title != null && performer != null -> "$performer - $title"
                    title != null -> title
                    audio.fileName?.isNotBlank() == true -> audio.fileName
                    else -> "Audio_${msg.id}"
                }
                // Add extension if missing
                val displayName = if (filename.contains('.')) filename else {
                    val ext = audio.mimeType?.substringAfter('/')?.let { 
                        when (it) { "mpeg" -> "mp3"; "x-flac" -> "flac"; else -> it }
                    } ?: "mp3"
                    "$filename.$ext"
                }
                val key = displayName to audio.audio.size
                if (seen.add(key)) {
                    results.add(TelegramVideoMessage(
                        messageId = msg.id,
                        chatId = msg.chatId,
                        fileName = displayName,
                        fileId = audio.audio.id,
                        fileSize = audio.audio.size,
                        duration = audio.duration,
                        mimeType = audio.mimeType ?: "audio/mpeg",
                        caption = content.caption?.text ?: "",
                        thumbnailFileId = audio.albumCoverThumbnail?.file?.id
                    ))
                }
            }
        }
    }

    suspend fun fetchChannelMedia(
        channelUsernameOrId: String,
        limit: Int = 300,
        includeAudio: Boolean = true
    ): List<TelegramVideoMessage> {
        val results = mutableListOf<TelegramVideoMessage>()
        val seen = mutableSetOf<Pair<String, Long>>()
        val chatId = getChatId(channelUsernameOrId) ?: return emptyList()

        val filters = mutableListOf<TdApi.SearchMessagesFilter>(
            TdApi.SearchMessagesFilterDocument(),
            TdApi.SearchMessagesFilterVideo()
        )
        if (includeAudio) {
            filters.add(TdApi.SearchMessagesFilterAudio())
        }

        for (filter in filters) {
            try {
                var currentFromMessageId = 0L
                var fetched = 0
                while (fetched < limit) {
                    val fetchLimit = minOf(100, limit - fetched)
                    val historyResult = TelegramClient.sendRequest(TdApi.SearchChatMessages().also { req ->
                        req.chatId = chatId
                        req.query = ""
                        req.senderId = null
                        req.fromMessageId = currentFromMessageId
                        req.offset = 0
                        req.limit = fetchLimit
                        req.filter = filter
                        req.topicId = null
                    })
                    val found = (historyResult as? TdApi.FoundChatMessages) ?: break
                    if (found.messages.isEmpty()) break

                    for (msg in found.messages) {
                        extractMediaMessage(msg, seen, results)
                    }

                    fetched += found.messages.size
                    if (found.messages.size < fetchLimit) break

                    val lastId = found.messages.last().id
                    if (currentFromMessageId == lastId && found.messages.size == 1) break
                    currentFromMessageId = lastId
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchChannelMedia error for $channelUsernameOrId: ${e.message}")
            }
        }
        return results.sortedByDescending { it.messageId }
    }

    fun getStreamUrl(fileId: Int, fileName: String, expectedSize: Long = 0L): String = TelegramStreamingProxy.getUrl(fileId, fileName, expectedSize)

    fun getThumbnailUrl(chatId: Long, messageId: Long): String = TelegramStreamingProxy.getThumbnailUrl(chatId, messageId)

    suspend fun getFreshFileId(chatId: Long, messageId: Long): Int? {
        if (chatId == 0L || messageId == 0L) return null
        return try {
            val msg = TelegramClient.sendRequest(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message ?: return null
            when (val content = msg.content) {
                is TdApi.MessageVideo -> content.video.video.id
                is TdApi.MessageDocument -> content.document.document.id
                is TdApi.MessageAudio -> content.audio.audio.id
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get fresh file id: ${e.message}")
            null
        }
    }

    /**
     * Detects split file groups from a list of messages.
     * Split files have numeric extensions (.001, .002), patterns like .part1, .z01, or identical names.
     * Returns a pair of (grouped split files, remaining individual files).
     */
    fun groupSplitFiles(messages: List<TelegramVideoMessage>): Pair<List<SplitFileGroup>, List<TelegramVideoMessage>> {
        val splitPattern = Regex("""^(.+?)\.(\d{2,4})$""")  // matches file.001, file.02, file.0001
        val partPattern = Regex("""^(.+?)\.part(\d+)(?:\.[a-zA-Z0-9]+)?$""", RegexOption.IGNORE_CASE)  // matches file.part1.rar, file.part01.zip
        val zPattern = Regex("""^(.+?)\.z(\d{2,3})$""", RegexOption.IGNORE_CASE) // matches file.z01, file.z02
        
        val groups = mutableMapOf<String, MutableList<Pair<Int, TelegramVideoMessage>>>()
        val sameNameGroups = mutableMapOf<String, MutableList<TelegramVideoMessage>>()
        val singles = mutableListOf<TelegramVideoMessage>()
        
        for (msg in messages) {
            val name = msg.fileName
            val splitMatch = splitPattern.find(name)
            val partMatch = partPattern.find(name)
            val zMatch = zPattern.find(name)
            
            when {
                splitMatch != null -> {
                    val baseName = splitMatch.groupValues[1]
                    val partNum = splitMatch.groupValues[2].toIntOrNull() ?: 0
                    groups.getOrPut(baseName) { mutableListOf() }.add(partNum to msg)
                }
                partMatch != null -> {
                    val baseName = partMatch.groupValues[1]
                    val partNum = partMatch.groupValues[2].toIntOrNull() ?: 0
                    groups.getOrPut(baseName) { mutableListOf() }.add(partNum to msg)
                }
                zMatch != null -> {
                    val baseName = zMatch.groupValues[1]
                    val partNum = zMatch.groupValues[2].toIntOrNull() ?: 0
                    groups.getOrPut(baseName) { mutableListOf() }.add(partNum to msg)
                }
                else -> {
                    val key = "${msg.chatId}_${msg.fileName}"
                    sameNameGroups.getOrPut(key) { mutableListOf() }.add(msg)
                }
            }
        }
        
        val splitGroups = mutableListOf<SplitFileGroup>()
        
        for ((baseName, parts) in groups) {
            if (parts.size < 2) {
                singles.addAll(parts.map { it.second })
                continue
            }
            val sorted = parts.sortedBy { it.first }.map { it.second }
            splitGroups.add(SplitFileGroup(
                baseName = baseName,
                parts = sorted,
                totalSize = sorted.sumOf { it.fileSize }
            ))
        }

        for ((_, parts) in sameNameGroups) {
            if (parts.size >= 2) {
                val sorted = parts.sortedBy { it.messageId }
                val baseName = parts.first().fileName
                splitGroups.add(SplitFileGroup(
                    baseName = baseName,
                    parts = sorted,
                    totalSize = sorted.sumOf { it.fileSize }
                ))
            } else {
                singles.addAll(parts)
            }
        }
        
        return splitGroups to singles
    }

    /**
     * Check if a file is a ZIP that could contain streamable media.
     */
    fun isStreamableZip(msg: TelegramVideoMessage): Boolean {
        val ext = msg.fileName.substringAfterLast('.', "").lowercase()
        return ext == "zip" && msg.fileSize > 1_000_000 // Only ZIPs > 1MB likely contain media
    }

    fun getMergedStreamUrl(fileIds: List<Int>, fileName: String, sizes: List<Long>): String =
        TelegramStreamingProxy.getMergedUrl(fileIds, fileName, sizes)

    fun getZipStreamUrl(fileId: Int, innerFileName: String, zipSize: Long): String =
        TelegramStreamingProxy.getZipStreamUrl(fileId, innerFileName, zipSize)

    /**
     * Groups split file parts while preserving exact chronological/message order of the original list.
     */
    fun groupAndPreserveOrder(messages: List<TelegramVideoMessage>): List<DisplayItem> {
        if (messages.isEmpty()) return emptyList()

        val messageIndexMap = messages.withIndex().associate { it.value.messageId to it.index }
        val (splitGroups, individualFiles) = groupSplitFiles(messages)

        val items = mutableListOf<DisplayItem>()

        for (group in splitGroups) {
            val minIndex = group.parts.mapNotNull { messageIndexMap[it.messageId] }.minOrNull() ?: 0
            items.add(DisplayItem.Group(group, minIndex))
        }

        for (msg in individualFiles) {
            val index = messageIndexMap[msg.messageId] ?: 0
            items.add(DisplayItem.Single(msg, index))
        }

        return items.sortedBy { it.sortIndex }
    }
}

sealed class DisplayItem {
    abstract val sortIndex: Int

    data class Single(val message: TelegramVideoMessage, override val sortIndex: Int) : DisplayItem()
    data class Group(val group: SplitFileGroup, override val sortIndex: Int) : DisplayItem()
}
