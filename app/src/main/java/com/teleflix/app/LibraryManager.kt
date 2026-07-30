package com.teleflix.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object LibraryManager {
    private const val PREF_NAME = "teleflix_library"
    private const val KEY_ITEMS = "bookmarked_items"

    fun isBookmarked(context: Context, id: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("id") == id) return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    fun toggleBookmark(context: Context, item: MediaItem): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        val array = try { JSONArray(jsonStr) } catch (e: Exception) { JSONArray() }
        val newArray = JSONArray()
        var found = false

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.optString("id") == item.id) {
                found = true
            } else {
                newArray.put(obj)
            }
        }

        if (!found) {
            val itemObj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("posterUrl", item.posterUrl)
                put("year", item.year)
                put("rating", item.rating)
                put("overview", item.overview)
                put("type", item.type)
                put("streamUrl", item.streamUrl)
                put("originalFileName", item.originalFileName)
            }
            newArray.put(itemObj)
        }

        prefs.edit().putString(KEY_ITEMS, newArray.toString()).apply()
        return !found
    }

    fun getBookmarkedItems(context: Context): List<MediaItem> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        val list = mutableListOf<MediaItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in (array.length() - 1) downTo 0) {
                val obj = array.getJSONObject(i)
                list.add(
                    MediaItem(
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        posterUrl = obj.optString("posterUrl"),
                        year = obj.optString("year"),
                        rating = obj.optString("rating"),
                        overview = obj.optString("overview"),
                        type = obj.optString("type", "movie"),
                        streamUrl = obj.optString("streamUrl", ""),
                        originalFileName = obj.optString("originalFileName", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun clearLibrary(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
