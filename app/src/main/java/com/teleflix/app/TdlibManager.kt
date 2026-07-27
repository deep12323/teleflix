package com.teleflix.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

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
    val isZip: Boolean = false
)

object TdlibManager {
    private const val TAG = "TdlibManager"
    private const val PREFS_NAME = "teleflix_tdlib_prefs"

    const val DEFAULT_API_ID = 2040012
    const val DEFAULT_API_HASH = "b18441a1ed609c10b277028c11e4f4fb"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getApiId(context: Context): Int {
        val id = getPrefs(context).getInt("api_id", 0)
        return if (id != 0) id else DEFAULT_API_ID
    }

    fun saveApiId(context: Context, apiId: Int) {
        getPrefs(context).edit().putInt("api_id", apiId).apply()
    }

    fun getApiHash(context: Context): String {
        val hash = getPrefs(context).getString("api_hash", "") ?: ""
        return if (hash.isNotBlank()) hash else DEFAULT_API_HASH
    }

    fun saveApiHash(context: Context, apiHash: String) {
        getPrefs(context).edit().putString("api_hash", apiHash).apply()
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

        return list.map { u -> TelegramChannel(u, "$u Channel", "Monitored") }
    }

    fun addChannel(context: Context, username: String) {
        var clean = username.trim()
        if (!clean.startsWith("@") && clean.toLongOrNull() == null) {
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

    // Resolves video streams across ALL joined Telegram channels, groups, and chats exactly like the Cloudstream extension
    suspend fun resolveStreams(title: String, season: Int? = null, episode: Int? = null): List<StreamSource> {
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

        val rawResults = mutableListOf<TelegramVideoMessage>()
        val seenIds = mutableSetOf<String>()
        for (q in queries) {
            val res = try {
                // Searches monitored channels AND globally across ALL joined channels, groups, and chats
                TelegramRepository.searchVideoMessages(q, limit = 150, includeAudio = false)
            } catch (e: Exception) {
                emptyList()
            }
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

        return items.mapNotNull { item ->
            when (item) {
                is DisplayItem.Group -> {
                    val group = item.group
                    val freshIds = group.parts.map { it.fileId }
                    val partSizes = group.parts.map { it.fileSize }
                    val totalSize = partSizes.sum()
                    val streamUrl = TelegramRepository.getMergedStreamUrl(freshIds, group.baseName, partSizes)
                    StreamSource(
                        id = "group_${group.baseName}",
                        quality = "SPLIT ARCHIVE (${group.parts.size} parts)",
                        fileName = "🔗 ${group.baseName}",
                        size = formatBytes(totalSize),
                        channel = "Telegram Split Pack",
                        url = streamUrl,
                        isSplit = true
                    )
                }
                is DisplayItem.Single -> {
                    val msg = item.message
                    val ext = msg.fileName.substringAfterLast('.', "").lowercase()
                    val sizeStr = formatBytes(msg.fileSize)
                    val streamUrl = if (ext == "zip" && msg.fileSize > 1_000_000) {
                        TelegramRepository.getZipStreamUrl(msg.fileId, msg.fileName, msg.fileSize)
                    } else {
                        TelegramRepository.getStreamUrl(msg.fileId, msg.fileName, msg.fileSize)
                    }
                    val qualityTag = extractQualityTag(msg.fileName)
                    val prefix = if (ext == "zip") "🗄️ " else "📺 "
                    StreamSource(
                        id = "${msg.chatId}_${msg.messageId}",
                        quality = if (qualityTag.isNotBlank()) qualityTag else ext.uppercase().ifBlank { "VIDEO" },
                        fileName = prefix + msg.fileName.ifBlank { "telegram_video.$ext" },
                        size = sizeStr,
                        channel = "Telegram Stream",
                        url = streamUrl,
                        isZip = (ext == "zip")
                    )
                }
            }
        }
    }

    private fun extractQualityTag(name: String): String {
        val upper = name.uppercase()
        val tags = mutableListOf<String>()
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
