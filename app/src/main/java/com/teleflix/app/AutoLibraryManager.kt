package com.teleflix.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class AutoEpisode(
    val season: Int,
    val episode: Int,
    val title: String,
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileSize: Long,
    val quality: String,
    val fileName: String,
    val streamUrl: String = ""
)

object AutoLibraryManager {
    private const val TAG = "AutoLibraryManager"
    private const val CACHE_FILE = "auto_library_cache.json"

    // In-memory cache of parsed series season & episode trees
    val seriesEpisodesMap = ConcurrentHashMap<String, Map<Int, List<EpisodeItem>>>()
    val episodePlayablesMap = ConcurrentHashMap<String, AutoEpisode>()

    fun getCachedLibrary(context: Context): List<MediaItem> {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val jsonStr = file.readText()
            val array = JSONArray(jsonStr)
            val list = mutableListOf<MediaItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                val type = obj.optString("type")
                
                // Restore series episodes map if present
                if (type == "auto_series" && obj.has("seasons")) {
                    val seasonsJson = obj.getJSONObject("seasons")
                    val seasonsMap = mutableMapOf<Int, MutableList<EpisodeItem>>()
                    val keys = seasonsJson.keys()
                    while (keys.hasNext()) {
                        val sStr = keys.next()
                        val sNum = sStr.toIntOrNull() ?: 1
                        val epArray = seasonsJson.getJSONArray(sStr)
                        val epList = mutableListOf<EpisodeItem>()
                        for (e in 0 until epArray.length()) {
                            val epObj = epArray.getJSONObject(e)
                            val season = epObj.optInt("season", sNum)
                            val episode = epObj.optInt("episode", e + 1)
                            val epTitle = epObj.optString("title", "Episode $episode")
                            val chatId = epObj.optLong("chatId")
                            val messageId = epObj.optLong("messageId")
                            val fileId = epObj.optInt("fileId")
                            val fileSize = epObj.optLong("fileSize")
                            val quality = epObj.optString("quality")
                            val fileName = epObj.optString("fileName")
                            val streamUrl = epObj.optString("streamUrl")

                            val epKey = "${id}_s${season}_e${episode}"
                            episodePlayablesMap[epKey] = AutoEpisode(
                                season = season,
                                episode = episode,
                                title = epTitle,
                                chatId = chatId,
                                messageId = messageId,
                                fileId = fileId,
                                fileSize = fileSize,
                                quality = quality,
                                fileName = fileName,
                                streamUrl = streamUrl
                            )

                            epList.add(EpisodeItem(
                                season = season,
                                episode = episode,
                                title = if (quality.isNotBlank()) "$epTitle [$quality]" else epTitle,
                                overview = epObj.optString("overview", ""),
                                released = epObj.optString("released", "")
                            ))
                        }
                        seasonsMap[sNum] = epList
                    }
                    seriesEpisodesMap[id] = seasonsMap
                }

                list.add(MediaItem(
                    id = id,
                    title = obj.optString("title"),
                    posterUrl = obj.optString("posterUrl"),
                    year = obj.optString("year"),
                    rating = obj.optString("rating"),
                    overview = obj.optString("overview"),
                    type = type,
                    streamUrl = obj.optString("streamUrl"),
                    originalFileName = obj.optString("originalFileName"),
                    fileSize = obj.optLong("fileSize")
                ))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun scanAndBuildLibrary(
        context: Context,
        onProgress: (String) -> Unit = {}
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        onProgress("Discovering all joined channels and chats...")

        // 1. Gather all candidate channels (Joined Chats + Custom Monitored Channels)
        val joinedChats = try {
            TelegramRepository.getJoinedChatsInfo(forceRefresh = true)
        } catch (_: Exception) {
            emptyList()
        }
        val customChannels = try {
            TelegramRepository.getCustomChannels(context)
        } catch (_: Exception) {
            emptyList()
        }

        val channelIds = mutableSetOf<String>()
        for (c in joinedChats) {
            if (c.isChannel || c.isGroup) {
                channelIds.add(c.chatId.toString())
            }
        }
        for (ch in customChannels) {
            channelIds.add(ch)
        }

        if (channelIds.isEmpty()) {
            return@withContext emptyList()
        }

        onProgress("Scanning ${channelIds.size} channels for videos & series...")

        // 2. Fetch media messages from all channels in parallel batches
        val allVideoMessages = java.util.Collections.synchronizedList(mutableListOf<TelegramVideoMessage>())
        val seenKeys = java.util.Collections.synchronizedSet(mutableSetOf<Pair<String, Long>>())

        coroutineScope {
            channelIds.map { chId ->
                async(Dispatchers.IO) {
                    try {
                        val (messages, _) = TelegramRepository.fetchChannelMedia(
                            channelUsernameOrId = chId,
                            fromMessageId = 0L,
                            limit = 100,
                            includeAudio = false,
                            forceRefresh = true
                        )
                        for (msg in messages) {
                            // Filter: Ignore files < 50MB (drops voice notes, stickers, gifs, short memes)
                            if (msg.fileSize >= 50 * 1024 * 1024L) {
                                val key = msg.fileName to msg.fileSize
                                if (seenKeys.add(key)) {
                                    allVideoMessages.add(msg)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }.awaitAll()
        }

        if (allVideoMessages.isEmpty()) {
            return@withContext emptyList()
        }

        onProgress("Parsed ${allVideoMessages.size} files. Auto-detecting movies & shows...")

        // 3. Classify into Movies vs TV Series Episodes
        val rawMovies = mutableListOf<Pair<TelegramVideoMessage, MediaParser.ParsedMedia>>()
        val rawSeriesMap = mutableMapOf<String, MutableList<Pair<TelegramVideoMessage, MediaParser.ParsedMedia>>>()

        for (msg in allVideoMessages) {
            val parsed = MediaParser.parse(msg.fileName, msg.caption)
            if (parsed.isSeries && parsed.season != null && parsed.episode != null) {
                val seriesKey = parsed.cleanTitle.lowercase().trim()
                rawSeriesMap.getOrPut(seriesKey) { mutableListOf() }.add(Pair(msg, parsed))
            } else {
                rawMovies.add(Pair(msg, parsed))
            }
        }

        // 4. Resolve Metadata & Build MediaItem List
        val finalLibrary = mutableListOf<MediaItem>()
        val diskJsonArray = JSONArray()

        // --- Process Series ---
        onProgress("Cataloging TV Series & grouping episodes...")
        for ((seriesKey, episodePairs) in rawSeriesMap) {
            val firstParsed = episodePairs.first().second
            val showTitle = firstParsed.cleanTitle
            val meta = MetadataResolver.resolve(context, showTitle, isSeries = true)

            val displayTitle = meta?.title ?: showTitle
            val posterUrl = if (meta?.posterUrl?.isNotBlank() == true) {
                meta.posterUrl
            } else {
                val thumbFileId = episodePairs.firstOrNull { it.first.thumbnailFileId != null }?.first?.thumbnailFileId
                if (thumbFileId != null && thumbFileId > 0) TelegramStreamingProxy.getThumbnailUrl(thumbFileId) else ""
            }

            val seriesId = "autoseries_${seriesKey.hashCode()}"
            val seasonsMap = mutableMapOf<Int, MutableList<EpisodeItem>>()
            val seasonsJson = JSONObject()

            // Sort episodes by season then episode
            val sortedEps = episodePairs.sortedWith(compareBy({ it.second.season ?: 1 }, { it.second.episode ?: 1 }))

            for ((msg, parsed) in sortedEps) {
                val s = parsed.season ?: 1
                val e = parsed.episode ?: 1
                val epKey = "${seriesId}_s${s}_e${e}"
                val epTitle = "Episode $e"

                episodePlayablesMap[epKey] = AutoEpisode(
                    season = s,
                    episode = e,
                    title = epTitle,
                    chatId = msg.chatId,
                    messageId = msg.messageId,
                    fileId = msg.fileId,
                    fileSize = msg.fileSize,
                    quality = parsed.quality,
                    fileName = msg.fileName
                )

                val epItem = EpisodeItem(
                    season = s,
                    episode = e,
                    title = if (parsed.quality.isNotBlank()) "$epTitle [${parsed.quality}]" else epTitle,
                    overview = "File: ${msg.fileName}",
                    released = ""
                )
                seasonsMap.getOrPut(s) { mutableListOf() }.add(epItem)

                val epObj = JSONObject().apply {
                    put("season", s)
                    put("episode", e)
                    put("title", epTitle)
                    put("chatId", msg.chatId)
                    put("messageId", msg.messageId)
                    put("fileId", msg.fileId)
                    put("fileSize", msg.fileSize)
                    put("quality", parsed.quality)
                    put("fileName", msg.fileName)
                    put("overview", "File: ${msg.fileName}")
                }
                if (!seasonsJson.has(s.toString())) seasonsJson.put(s.toString(), JSONArray())
                seasonsJson.getJSONArray(s.toString()).put(epObj)
            }

            seriesEpisodesMap[seriesId] = seasonsMap

            val totalEps = sortedEps.size
            val seasonCount = seasonsMap.size
            val yearText = if (meta?.year?.isNotBlank() == true) meta.year else "${seasonCount} Season${if (seasonCount > 1) "s" else ""}"
            val ratingText = if (meta?.rating?.isNotBlank() == true) "⭐ ${meta.rating}" else "📺 $totalEps Eps"

            val seriesItem = MediaItem(
                id = seriesId,
                title = displayTitle,
                posterUrl = posterUrl,
                year = yearText,
                rating = ratingText,
                overview = meta?.overview ?: "Auto-detected TV series containing $totalEps episodes across $seasonCount season(s).",
                type = "auto_series"
            )
            finalLibrary.add(seriesItem)

            val sObj = JSONObject().apply {
                put("id", seriesItem.id)
                put("title", seriesItem.title)
                put("posterUrl", seriesItem.posterUrl)
                put("year", seriesItem.year)
                put("rating", seriesItem.rating)
                put("overview", seriesItem.overview)
                put("type", seriesItem.type)
                put("seasons", seasonsJson)
            }
            diskJsonArray.put(sObj)
        }

        // --- Process Movies ---
        onProgress("Cataloging Movies & pulling posters...")
        // Group movies by title and year to avoid duplicate cards for multi-qualities
        val moviesByTitle = rawMovies.groupBy { it.second.cleanTitle.lowercase().trim() }

        for ((movieKey, moviePairs) in moviesByTitle) {
            val firstParsed = moviePairs.first().second
            val bestMsg = moviePairs.maxByOrNull { it.first.fileSize }?.first ?: moviePairs.first().first
            val titleClean = firstParsed.cleanTitle
            val meta = MetadataResolver.resolve(context, titleClean, firstParsed.year, isSeries = false)

            val displayTitle = meta?.title ?: titleClean
            val posterUrl = if (meta?.posterUrl?.isNotBlank() == true) {
                meta.posterUrl
            } else {
                val thumbFileId = moviePairs.firstOrNull { it.first.thumbnailFileId != null }?.first?.thumbnailFileId
                if (thumbFileId != null && thumbFileId > 0) TelegramStreamingProxy.getThumbnailUrl(thumbFileId) else ""
            }

            val yearText = if (meta?.year?.isNotBlank() == true) meta.year else (firstParsed.year?.toString() ?: "Movie")
            val qualityBadge = if (firstParsed.quality.isNotBlank()) firstParsed.quality else "HD"
            val ratingText = if (meta?.rating?.isNotBlank() == true) "⭐ ${meta.rating}" else qualityBadge

            val movieId = "automovie_${bestMsg.chatId}_${bestMsg.messageId}"
            val movieItem = MediaItem(
                id = movieId,
                title = displayTitle,
                posterUrl = posterUrl,
                year = yearText,
                rating = ratingText,
                overview = meta?.overview ?: "Detected video: ${bestMsg.fileName}",
                type = "telegram_media",
                originalFileName = bestMsg.fileName,
                fileSize = bestMsg.fileSize
            )
            finalLibrary.add(movieItem)

            val mObj = JSONObject().apply {
                put("id", movieItem.id)
                put("title", movieItem.title)
                put("posterUrl", movieItem.posterUrl)
                put("year", movieItem.year)
                put("rating", movieItem.rating)
                put("overview", movieItem.overview)
                put("type", movieItem.type)
                put("originalFileName", movieItem.originalFileName)
                put("fileSize", movieItem.fileSize)
            }
            diskJsonArray.put(mObj)
        }

        // Save to persistent file cache for instant 0ms offline loads
        try {
            File(context.filesDir, CACHE_FILE).writeText(diskJsonArray.toString())
        } catch (_: Exception) {}

        return@withContext finalLibrary
    }
}
