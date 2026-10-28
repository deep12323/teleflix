package com.teleflix.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class MetadataInfo(
    val imdbId: String,
    val title: String,
    val posterUrl: String,
    val backdropUrl: String,
    val year: String,
    val rating: String,
    val overview: String,
    val genres: List<String>
)

object MetadataResolver {
    private const val PREFS_NAME = "teleflix_meta_cache_v1"
    private val memoryCache = ConcurrentHashMap<String, MetadataInfo>()

    suspend fun resolve(
        context: Context,
        cleanTitle: String,
        year: Int? = null,
        isSeries: Boolean = false
    ): MetadataInfo? = withContext(Dispatchers.IO) {
        val titleNorm = cleanTitle.trim().lowercase()
        if (titleNorm.isBlank() || titleNorm.length < 2) return@withContext null

        val cacheKey = "${if (isSeries) "series" else "movie"}:$titleNorm:${year ?: 0}"

        // 1. In-memory check (<1ms)
        memoryCache[cacheKey]?.let { return@withContext it }

        // 2. Persistent SharedPreferences check (<5ms)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedJson = prefs.getString(cacheKey, null)
        if (cachedJson != null) {
            try {
                val obj = JSONObject(cachedJson)
                val info = parseMetadataObj(obj)
                memoryCache[cacheKey] = info
                return@withContext info
            } catch (_: Exception) {}
        }

        // 3. Network fetch from Cinemeta API
        val type = if (isSeries) "series" else "movie"
        try {
            val encodedQuery = URLEncoder.encode(cleanTitle, "UTF-8")
            val searchUrl = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encodedQuery.json"
            val responseText = httpGet(searchUrl) ?: return@withContext null

            val searchJson = JSONObject(responseText)
            val metas = searchJson.optJSONArray("metas") ?: return@withContext null
            if (metas.length() == 0) return@withContext null

            // Find best match by year or take first
            var bestObj = metas.getJSONObject(0)
            if (year != null && metas.length() > 1) {
                for (i in 0 until metas.length()) {
                    val m = metas.getJSONObject(i)
                    val rel = m.optString("releaseInfo", "")
                    if (rel.contains(year.toString())) {
                        bestObj = m
                        break
                    }
                }
            }

            val imdbId = bestObj.optString("id").ifBlank { bestObj.optString("imdb_id") }
            var title = bestObj.optString("name", cleanTitle)
            var poster = bestObj.optString("poster", "")
            var background = bestObj.optString("background", "")
            var yearStr = bestObj.optString("releaseInfo", year?.toString() ?: "")
            var rating = bestObj.optString("imdbRating", "")
            var overview = bestObj.optString("description", "")
            val genresList = mutableListOf<String>()

            // Try fetching rich details if overview or rating is missing
            if (imdbId.isNotBlank() && (overview.isBlank() || rating.isBlank())) {
                try {
                    val detailUrl = "https://v3-cinemeta.strem.io/meta/$type/$imdbId.json"
                    val detailText = httpGet(detailUrl)
                    if (detailText != null) {
                        val detailJson = JSONObject(detailText).optJSONObject("meta")
                        if (detailJson != null) {
                            if (overview.isBlank()) overview = detailJson.optString("description", "")
                            if (rating.isBlank()) rating = detailJson.optString("imdbRating", "")
                            if (poster.isBlank()) poster = detailJson.optString("poster", "")
                            if (background.isBlank()) background = detailJson.optString("background", "")
                            if (yearStr.isBlank()) yearStr = detailJson.optString("year", "")
                            val gArray = detailJson.optJSONArray("genre")
                            if (gArray != null) {
                                for (g in 0 until gArray.length()) {
                                    genresList.add(gArray.getString(g))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            val result = MetadataInfo(
                imdbId = imdbId,
                title = title,
                posterUrl = poster,
                backdropUrl = background,
                year = yearStr,
                rating = rating,
                overview = overview,
                genres = genresList
            )

            // Cache in memory and disk
            memoryCache[cacheKey] = result
            val saveObj = JSONObject().apply {
                put("imdbId", result.imdbId)
                put("title", result.title)
                put("posterUrl", result.posterUrl)
                put("backdropUrl", result.backdropUrl)
                put("year", result.year)
                put("rating", result.rating)
                put("overview", result.overview)
                put("genres", org.json.JSONArray(result.genres))
            }
            prefs.edit().putString(cacheKey, saveObj.toString()).apply()

            return@withContext result
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMetadataObj(obj: JSONObject): MetadataInfo {
        val gArray = obj.optJSONArray("genres")
        val gList = mutableListOf<String>()
        if (gArray != null) {
            for (i in 0 until gArray.length()) gList.add(gArray.optString(i))
        }
        return MetadataInfo(
            imdbId = obj.optString("imdbId"),
            title = obj.optString("title"),
            posterUrl = obj.optString("posterUrl"),
            backdropUrl = obj.optString("backdropUrl"),
            year = obj.optString("year"),
            rating = obj.optString("rating"),
            overview = obj.optString("overview"),
            genres = gList
        )
    }

    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Teleflix/1.0")
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
