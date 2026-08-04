package com.teleflix.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class TelegramChannel(
    val username: String,
    val title: String,
    val subscriberCount: String
)

data class StreamSource(
    val id: String,
    val quality: String,
    val fileName: String,
    val size: String,
    val channel: String,
    val url: String,
    val isSplit: Boolean = false,
    val isZip: Boolean = false,
    val chatId: Long = 0L
)

object TdlibManager {
    private const val TAG = "TdlibManager"
    private const val PREFS_NAME = "teleflix_tdlib_prefs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getApiId(context: Context): Int {
        return getPrefs(context).getInt("api_id", 0)
    }

    fun saveApiId(context: Context, apiId: Int) {
        getPrefs(context).edit().putInt("api_id", apiId).apply()
    }

    fun getApiHash(context: Context): String {
        return getPrefs(context).getString("api_hash", "") ?: ""
    }

    fun saveApiHash(context: Context, apiHash: String) {
        getPrefs(context).edit().putString("api_hash", apiHash.trim()).apply()
    }

    fun isApiCredentialsConfigured(context: Context): Boolean {
        return getApiId(context) > 0 && getApiHash(context).isNotBlank()
    }

    fun getUserPhone(context: Context): String {
        return getPrefs(context).getString("user_phone", "") ?: ""
    }

    fun isSessionActive(context: Context): Boolean {
        return getPrefs(context).getBoolean("session_active", false)
    }

    fun setSessionActive(context: Context, active: Boolean, phone: String = "") {
        getPrefs(context).edit()
            .putBoolean("session_active", active)
            .putString("user_phone", phone)
            .apply()
    }

    fun getChannels(context: Context): List<TelegramChannel> {
        val raw = getPrefs(context).getString("custom_channels", "") ?: ""
        if (raw.isBlank()) return emptyList()

        val list = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return emptyList()

        return list.map { u -> TelegramChannel(u, u, "") }
    }

    fun addChannel(context: Context, username: String) {
        var clean = username.trim()
        if (!clean.startsWith("@") && !clean.startsWith("-") && !clean.all { it.isDigit() }) {
            clean = "@$clean"
        }
        
        val current = getChannels(context).map { it.username }.toMutableList()
        if (!current.contains(clean)) {
            current.add(clean)
            getPrefs(context).edit().putString("custom_channels", current.joinToString(",")).apply()
        }
    }

    fun removeChannel(context: Context, username: String) {
        val current = getChannels(context).map { it.username }.filter { it != username }
        getPrefs(context).edit().putString("custom_channels", current.joinToString(",")).apply()
    }

    fun setChannels(context: Context, channels: List<String>) {
        val cleanList = channels.map { ch ->
            var clean = ch.trim()
            if (!clean.startsWith("@") && !clean.startsWith("-") && !clean.all { it.isDigit() }) {
                clean = "@$clean"
            }
            clean
        }.distinct()
        getPrefs(context).edit().putString("custom_channels", cleanList.joinToString(",")).apply()
    }

    private const val STREAM_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    private val streamResolutionCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<StreamSource>>>()

    fun clearStreamResolutionCache() {
        streamResolutionCache.clear()
    }

    // Resolves video streams across ALL joined Telegram channels, groups, and chats exactly like the Cloudstream extension
    suspend fun resolveStreams(title: String, season: Int? = null, episode: Int? = null, forceRefresh: Boolean = false): List<StreamSource> {
        val cacheKey = "$title-$season-$episode"
        val now = System.currentTimeMillis()
        if (!forceRefresh && streamResolutionCache.containsKey(cacheKey)) {
            val entry = streamResolutionCache[cacheKey]
            if (entry != null) {
                val (timestamp, cached) = entry
                if (!cached.isNullOrEmpty() && (now - timestamp) < STREAM_CACHE_TTL_MS) {
                    return cached
                }
            }
        }

        val rawTitle = title.trim()
        val cleanTitle = rawTitle.replace(Regex("[^a-zA-Z0-9 ]"), " ").replace(Regex(" +"), " ").trim()
        val compactTitle = cleanTitle.replace(" ", "")

        val queries = LinkedHashSet<String>()
        if (season != null && episode != null) {
            val sStr = String.format("%02d", season)
            val eStr = String.format("%02d", episode)

            // 1. Clean title with standard S01E01 (Highest match rate on Telegram!)
            queries.add("$cleanTitle S${sStr}E${eStr}")
            if (rawTitle != cleanTitle) {
                queries.add("$rawTitle S${sStr}E${eStr}")
            }

            // 2. Season packs
            queries.add("$cleanTitle S${sStr}")
            queries.add("$cleanTitle Season $season")

            // 3. Compact title
            if (compactTitle != cleanTitle && compactTitle.length >= 3) {
                queries.add("$compactTitle S${sStr}E${eStr}")
            }

            // 4. Alternative episode numbering
            queries.add("$cleanTitle ${season}x${eStr}")
            queries.add("$cleanTitle S${season}E${episode}")
        } else {
            queries.add(cleanTitle)
            if (cleanTitle != rawTitle) {
                queries.add(rawTitle)
            }
            if (compactTitle != cleanTitle && compactTitle.length >= 3) {
                queries.add(compactTitle)
            }
        }

        val queryResults = coroutineScope {
            queries.map { q ->
                async(Dispatchers.IO) {
                    try {
                        TelegramRepository.searchVideoMessages(q, limit = 100, includeAudio = false)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }

        val rawResults = mutableListOf<TelegramVideoMessage>()
        val seenIds = mutableSetOf<String>()
        for (res in queryResults) {
            for (msg in res) {
                val key = "${msg.chatId}_${msg.messageId}"
                if (seenIds.add(key)) {
                    rawResults.add(msg)
                }
            }
        }

        val filteredResults = if (season != null && episode != null) {
            rawResults.filter { msg ->
                isMatchingEpisode(msg.fileName, msg.caption, season, episode)
            }
        } else {
            rawResults
        }

        val items = TelegramRepository.groupAndPreserveOrder(filteredResults).sortedByDescending { item ->
            when (item) {
                is DisplayItem.Group -> item.group.totalSize
                is DisplayItem.Single -> item.message.fileSize
            }
        }

        val resultSources = mutableListOf<StreamSource>()
        for (item in items) {
            when (item) {
                is DisplayItem.Group -> {
                    val group = item.group
                    val totalSize = group.parts.sumOf { it.fileSize }
                    val firstPart = group.parts.first()
                    val isZipGroup = group.baseName.lowercase().contains(".zip") || group.parts.any { it.fileName.lowercase().contains(".zip") }

                    if (isZipGroup) {
                        val freshIds = group.parts.map { it.fileId }
                        val partSizes = group.parts.map { it.fileSize }
                        val groupChats = group.parts.map { it.chatId }
                        val groupMsgs = group.parts.map { it.messageId }
                        val zipUrl = TelegramRepository.getMergedStreamUrl(freshIds, group.baseName, partSizes, groupChats, groupMsgs)
                        resultSources.add(
                            StreamSource(
                                id = "zip_${firstPart.chatId}_${firstPart.messageId}",
                                quality = "🗄️ ZIP",
                                fileName = "🗄️ ${group.baseName}",
                                size = formatBytes(totalSize),
                                channel = "Telegram Stream",
                                url = zipUrl,
                                isZip = true,
                                isSplit = false,
                                chatId = firstPart.chatId
                            )
                        )
                    } else {
                        val groupId = "group_${firstPart.chatId}_${group.baseName}"
                        TelegramRepository.groupPartsCache[groupId] = group.parts
                        val firstStreamUrl = TelegramRepository.getStreamUrl(firstPart.fileId, firstPart.fileName, firstPart.fileSize)
                        resultSources.add(
                            StreamSource(
                                id = groupId,
                                quality = "SPLIT PACK (${group.parts.size} Parts)",
                                fileName = "📦 ${group.baseName}",
                                size = formatBytes(totalSize),
                                channel = "Telegram Multi-Part",
                                url = firstStreamUrl,
                                isSplit = true,
                                chatId = firstPart.chatId
                            )
                        )
                    }
                }
                is DisplayItem.Single -> {
                    val msg = item.message
                    val ext = msg.fileName.substringAfterLast('.', "").lowercase()
                    val isZip = TelegramRepository.isZipArchiveFilename(msg.fileName)
                    val sizeStr = formatBytes(msg.fileSize)
                    val streamUrl = if (isZip && msg.fileSize > 1_000_000) {
                        TelegramRepository.getZipStreamUrl(msg.fileId, msg.fileName, msg.fileSize)
                    } else {
                        TelegramRepository.getStreamUrl(msg.fileId, msg.fileName, msg.fileSize)
                    }
                    val qualityTag = extractQualityTag(msg.fileName)
                    val prefix = if (isZip) "🗄️ " else "📺 "
                    resultSources.add(
                        StreamSource(
                            id = "${msg.chatId}_${msg.messageId}",
                            quality = if (qualityTag.isNotBlank()) qualityTag else ext.uppercase().ifBlank { "VIDEO" },
                            fileName = prefix + msg.fileName.ifBlank { "telegram_video.$ext" },
                            size = sizeStr,
                            channel = "Telegram Stream",
                            url = streamUrl,
                            isZip = isZip
                        )
                    )
                }
            }
        }

        val (highQuality, lowQuality) = resultSources.partition { !isLowQuality(it.fileName) }
        val finalSources = highQuality + lowQuality
        if (finalSources.isNotEmpty()) {
            streamResolutionCache[cacheKey] = Pair(System.currentTimeMillis(), finalSources)
        }
        return finalSources
    }

    private fun extractQualityTag(name: String): String {
        val upper = name.uppercase()
        val tags = mutableListOf<String>()

        if (upper.contains("CAMRIP") || upper.contains("CAM-RIP") || upper.contains("HDCAM")) tags.add("⚠️ CAM")
        else if (upper.contains("HDTS") || upper.contains("HD-TS") || upper.contains("TELESYNC")) tags.add("⚠️ HDTS")
        else if (upper.contains("SCREENER") || upper.contains("DVDSCR")) tags.add("⚠️ SCR")

        if (upper.contains("2160P") || upper.contains("4K")) tags.add("4K")
        else if (upper.contains("1080P")) tags.add("1080p")
        else if (upper.contains("720P")) tags.add("720p")
        else if (upper.contains("480P")) tags.add("480p")

        if (upper.contains("WEB-DL") || upper.contains("WEBDL")) tags.add("WEB-DL")
        else if (upper.contains("BLURAY") || upper.contains("BLU-RAY") || upper.contains("BRRIP")) tags.add("BluRay")
        else if (upper.contains("HDRIP") || upper.contains("HD-RIP") || upper.contains("HDTV")) tags.add("HDTV")
        else if (upper.contains("REMUX")) tags.add("REMUX")

        if (upper.contains("X265") || upper.contains("HEVC") || upper.contains("H265")) tags.add("x265")
        else if (upper.contains("X264") || upper.contains("H264")) tags.add("x264")
        else if (upper.contains("AV1") || upper.contains("AV-1")) tags.add("AV1")

        return tags.joinToString(" ")
    }

    private fun isLowQuality(name: String): Boolean {
        val upper = name.uppercase()
        if (upper.contains("CAMRIP") || upper.contains("CAM-RIP") || upper.contains("HDCAM") ||
            upper.contains("HDTS") || upper.contains("HD-TS") || upper.contains("TELESYNC") ||
            upper.contains("SCREENER") || upper.contains("DVDSCR") || upper.contains("PRE-DVD")) {
            return true
        }
        return Regex("""\b(CAM|HDCAM|HDTS|TS|SCR|DVDSCR|TELESYNC)\b""").containsMatchIn(upper)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun isMatchingEpisode(fileName: String, caption: String, targetSeason: Int, targetEpisode: Int): Boolean {
        val text = "$fileName $caption".lowercase()
        val sNum = targetSeason
        val eNum = targetEpisode
        val sStr = String.format("%02d", sNum)
        val eStr = String.format("%02d", eNum)
        val patterns = listOf(
            "s${sStr}e${eStr}", "s${sNum}e${eNum}", "s${sStr}.e${eStr}",
            "${sNum}x${eStr}", "${sNum}x${eNum}", "ep${eStr}", "episode ${eNum}", "ep ${eNum}"
        )
        if (patterns.any { text.contains(it) }) return true
        val fullSeasonRegex = Regex("(?i)(?:s0*$sNum|season\\s*0*$sNum)[._\\s-]*(?:complete|full|pack|all)")
        return fullSeasonRegex.containsMatchIn(text)
    }
}
