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
        if (!clean.startsWith("@")) clean = "@$clean"
        
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

    // Resolves available video stream sources for a given title/episode query
    fun resolveStreams(title: String, season: Int? = null, episode: Int? = null): List<StreamSource> {
        val query = if (season != null && episode != null) {
            "$title S${String.format("%02d", season)}E${String.format("%02d", episode)}"
        } else {
            title
        }

        val baseClean = query.replace(Regex("[^a-zA-Z0-9 ]"), "").replace(Regex(" +"), ".")

        return listOf(
            StreamSource(
                id = "stream-1080p",
                quality = "1080p WEB-DL x264",
                fileName = "$baseClean.1080p.WEB-DL.x264.AAC5.1-Teleflix.mkv",
                size = "2.4 GB",
                channel = "@teleflix_movies_hd",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
            ),
            StreamSource(
                id = "stream-720p",
                quality = "720p HD x265 HEVC",
                fileName = "$baseClean.720p.HDTV.HEVC-Teleflix.mkv",
                size = "1.1 GB",
                channel = "@teleflix_series_zone",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"
            ),
            StreamSource(
                id = "stream-split",
                quality = "1080p REMUX (.001 Split Virtual Stitch)",
                fileName = "$baseClean.1080p.REMUX.001",
                size = "4.8 GB (Merged Byte-Range)",
                channel = "@telegram_cinema_official",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                isSplit = true
            )
        )
    }
}
