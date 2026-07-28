package com.teleflix.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MediaItem(
    val id: String,
    val title: String,
    val posterUrl: String,
    val year: String,
    val rating: String,
    val overview: String,
    val type: String = "movie",  // "movie" or "series" or "telegram_media"
    val streamUrl: String = ""
)

data class EpisodeItem(
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String,
    val released: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var searchButton: Button
    private lateinit var statusButton: TextView
    private lateinit var categoryLabel: TextView
    private lateinit var loadingText: TextView
    private lateinit var modeToggleButton: TextView
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabRow: LinearLayout
    private var isTelegramCatalogMode = false
    private var currentOpenChannelId: String? = null
    private var currentOpenTopicId: Int = 0

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(java.util.Locale.US, "%.2f GB", gb)
        } else {
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            String.format(java.util.Locale.US, "%.1f MB", mb)
        }
    }
    private var lastTelegramFromMessageId: Long = 0L
    private val telegramStreamCache = mutableMapOf<String, Pair<String, String>>()
    private val telegramGroupCache = mutableMapOf<String, Pair<List<Pair<Long, Long>>, List<Long>>>()
    private var allGenreCache = listOf<MediaItem>()

    private var activeMediaIdForResume: String = ""
    private var activeStreamUrlForResume: String = ""
    private var activeTitleForResume: String = ""

    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val intent = result.data
        if (intent != null) {
            val posLong = intent.getLongExtra("position", -1L)
                .takeIf { it >= 0 } ?: intent.getLongExtra("extra_position", -1L)
                .takeIf { it >= 0 } ?: intent.getLongExtra("position_ms", -1L)
                .takeIf { it >= 0 } ?: intent.getIntExtra("position", -1).toLong()
                .takeIf { it >= 0 } ?: intent.getIntExtra("extra_position", -1).toLong()
                .takeIf { it >= 0 } ?: 0L

            if (posLong > 3000L) {
                val prefsLink = getSharedPreferences("teleflix_resume_points", android.content.Context.MODE_PRIVATE)
                val prefsTitle = getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE)
                val editLink = prefsLink.edit()
                val editTitle = prefsTitle.edit()

                if (activeMediaIdForResume.isNotBlank()) {
                    editLink.putLong("id_$activeMediaIdForResume", posLong)
                    editLink.putLong(activeMediaIdForResume, posLong)
                }
                if (activeStreamUrlForResume.isNotBlank()) {
                    editLink.putLong(activeStreamUrlForResume, posLong)
                }
                if (activeTitleForResume.isNotBlank()) {
                    editTitle.putLong("resume_$activeTitleForResume", posLong)
                }
                editLink.apply()
                editTitle.apply()

                TeleflixLogger.log("MainActivity", "Saved resume position $posLong ms for $activeTitleForResume")
            }
        }
    }

    private val mediaList = mutableListOf<MediaItem>()
    private var mediaAdapter: MediaAdapter? = null

    private var selectedCategory = "movie/top"
    private var selectedLabel = "Top Movies"
    private var currentSkip = 0
    private var isLoadingMore = false
    private var hasMoreItems = true
    private var isInSearchMode = false

    private val categories = listOf(
        "Top Movies" to "movie/top",
        "Top Series" to "series/top",
        "🕒 History" to "history/list",
        "New Movies" to "movie/year",
        "New Series" to "series/year",
        "IMDB Top" to "movie/imdbRating",
        "🎭 Genres" to "genres/picker"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#090A0F"))
            setPadding(24, 24, 24, 24)
            fitsSystemWindows = true
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                24 + insets.left,
                24 + insets.top,
                24 + insets.right,
                24 + insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        // Top App Header
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val titleView = TextView(this).apply {
            text = "TELEFLIX"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#E50914")) // Netflix Red
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusButton = TextView(this).apply {
            text = "⚙️"
            textSize = 24f
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(16, 8, 8, 8)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        modeToggleButton = TextView(this).apply {
            text = "🎬 Cinemeta"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(android.graphics.Color.parseColor("#1F2937"))
            setTextColor(android.graphics.Color.parseColor("#3B82F6"))
            setPadding(28, 12, 28, 12)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                toggleCatalogMode()
            }
        }

        headerLayout.addView(titleView)
        headerLayout.addView(modeToggleButton)
        val headerGap = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(20, 1)
        }
        headerLayout.addView(headerGap)
        headerLayout.addView(statusButton)
        rootView.addView(headerLayout)

        // Category Banner / Current Selection Header
        categoryLabel = TextView(this).apply {
            text = selectedLabel
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 8, 0, 12)
        }
        rootView.addView(categoryLabel)

        // Loading and Search Spinner / Text
        loadingText = TextView(this).apply {
            text = "Loading catalog..."
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
            setPadding(0, 4, 0, 12)
            visibility = android.view.View.GONE
        }
        rootView.addView(loadingText)

        // Search Box (Cinemeta Search)
        val searchLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        searchInput = EditText(this).apply {
            hint = "Search Movies & Series..."
            setHintTextColor(android.graphics.Color.parseColor("#808080"))
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A")) // Netflix Dark Matte
            setPadding(24, 20, 24, 20)
            textSize = 14f
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 12, 0)
            }
        }

        searchButton = Button(this).apply {
            text = "🔍"
            textSize = 16f
            setBackgroundColor(android.graphics.Color.parseColor("#B81D24")) // Netflix Crimson
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                val q = searchInput.text.toString()
                if (q.isNotBlank()) {
                    if (isTelegramCatalogMode) {
                        performTelegramSearch(q)
                    } else {
                        performSearch(q)
                    }
                }
            }
        }

        searchLayout.addView(searchInput)
        searchLayout.addView(searchButton)
        rootView.addView(searchLayout)

        // Category Tabs (Horizontal Scroll)
        tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, 12)
        }

        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        categories.forEach { (label, catalogId) ->
            val tab = Button(this).apply {
                text = label
                textSize = 11f
                val isSelected = catalogId == selectedCategory
                setTextColor(if (isSelected) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#A3A3A3"))
                setBackgroundColor(
                    if (isSelected) android.graphics.Color.parseColor("#B81D24")
                    else android.graphics.Color.parseColor("#1A1A1A")
                )
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 8, 0) }
                layoutParams = lp
                setOnClickListener {
                    if (catalogId == "genres/picker") {
                        showGenreSelectionDialog()
                        return@setOnClickListener
                    }
                    selectedCategory = catalogId
                    selectedLabel = label
                    categoryLabel.text = label
                    categoryLabel.isClickable = false
                    loadInitialCinemeta(catalogId, label)
                    updateTabSelection(catalogId)
                }
            }
            tabRow.addView(tab)
        }

        tabScroll.addView(tabRow)
        rootView.addView(tabScroll)

        // Media Grid
        mediaAdapter = MediaAdapter(mediaList, { item ->
            when (item.type) {
                "channel" -> loadTelegramChannelMedia(item.id, item.title)
                "topic" -> loadTelegramTopicMedia(item.id, item.title)
                "telegram_media" -> {
                    val streamInfo = telegramStreamCache[item.id]
                    val titleToPlay = streamInfo?.second ?: item.title
                    val groupInfo = telegramGroupCache[item.id]

                    if (groupInfo != null) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val cleanTitle = titleToPlay.removePrefix("📦 ")
                            val freshUrl = TelegramRepository.getFreshMergedMediaUrl(groupInfo.first, cleanTitle, groupInfo.second)
                            if (freshUrl != null && freshUrl.isNotBlank()) {
                                checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id)
                            } else {
                                val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                                checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id)
                            }
                        }
                    } else {
                        val cleanId = item.id.removePrefix("single_").removePrefix("stream_")
                        val parts = cleanId.split("_")
                        val chatId = parts.getOrNull(0)?.toLongOrNull()
                        val messageId = parts.getOrNull(1)?.toLongOrNull()

                        if (chatId != null && messageId != null && streamInfo == null) {
                            CoroutineScope(Dispatchers.Main).launch {
                                val freshUrl = TelegramRepository.getFreshMediaUrl(chatId, messageId)
                                if (freshUrl != null && freshUrl.isNotBlank()) {
                                    checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id)
                                } else {
                                    val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                                    if (backupUrl.isNotBlank()) {
                                        checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id)
                                    } else {
                                        Toast.makeText(this@MainActivity, "Media link expired or unavailable", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        } else {
                            val rawUrl = streamInfo?.first ?: item.streamUrl
                            val urlToPlay = TelegramStreamingProxy.refreshUrl(rawUrl)
                            if (urlToPlay.isNotBlank()) {
                                checkResumeAndSelectPlayer(urlToPlay, titleToPlay, item.posterUrl, item.id)
                            } else {
                                Toast.makeText(this@MainActivity, "Media link expired or unavailable", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                "series" -> fetchSeriesEpisodes(item)
                else -> showStreamOptions(item.title, null, null, item.posterUrl)
            }
        }, { item ->
            handleItemLongPress(item)
        })

        val gridLayoutManager = GridLayoutManager(this, 2).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val item = mediaList.getOrNull(position)
                    return if (item?.type == "channel") 2 else 1
                }
            }
        }
        recyclerView = RecyclerView(this).apply {
            layoutManager = gridLayoutManager
            adapter = mediaAdapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        // Attach Endless Scroll Listener
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0) return // Only check on downward scroll

                if (!isLoadingMore && hasMoreItems && !isInSearchMode) {
                    val totalItemCount = gridLayoutManager.itemCount
                    val lastVisibleItemPosition = gridLayoutManager.findLastVisibleItemPosition()

                    if (totalItemCount > 0 && lastVisibleItemPosition + 4 >= totalItemCount) {
                        if (isTelegramCatalogMode && currentOpenChannelId != null) {
                            loadMoreTelegramChannelMedia()
                        } else if (!isTelegramCatalogMode) {
                            loadMoreCinemeta()
                        }
                    }
                }
            }
        })

        rootView.addView(recyclerView)
        setContentView(rootView)

        loadInitialCinemeta("movie/top", "Top Movies")
    }

    override fun onResume() {
        super.onResume()
        TelegramRepository.initialize(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        } else {
            try { TelegramService.start(this) } catch (_: Exception) {}
        }
        updateStatusButton()
    }

    private fun updateStatusButton() {
        // Only display the settings icon in the header
        statusButton.text = "⚙️"
    }

    private fun updateTabSelection(activeCatalogId: String) {
        if (!::tabRow.isInitialized) return
        for (i in 0 until tabRow.childCount) {
            val child = tabRow.getChildAt(i) as? Button ?: continue
            val cat = categories.getOrNull(i)?.second ?: ""
            val isSelected = if (activeCatalogId.contains("genre=")) {
                cat == "genres/picker"
            } else {
                cat == activeCatalogId
            }
            child.setTextColor(if (isSelected) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#A3A3A3"))
            child.setBackgroundColor(
                if (isSelected) android.graphics.Color.parseColor("#B81D24")
                else android.graphics.Color.parseColor("#1A1A1A")
            )
        }
    }

    private fun showGenreSelectionDialog() {
        val genres = listOf(
            "Action", "Adventure", "Animation", "Biography", "Comedy",
            "Crime", "Documentary", "Drama", "Family", "Fantasy",
            "History", "Horror", "Mystery", "Romance", "Sci-Fi",
            "Sport", "Thriller", "War", "Western"
        )
        val genreIcons = arrayOf(
            "💥 Action", "🗺️ Adventure", "🦄 Animation", "📖 Biography", "😂 Comedy",
            "🕵️ Crime", "🎥 Documentary", "🎭 Drama", "👨‍👩‍👧 Family", "✨ Fantasy",
            "📜 History", "👻 Horror", "🔍 Mystery", "❤️ Romance", "🛸 Sci-Fi",
            "⚽ Sport", "🔪 Thriller", "⚔️ War", "🤠 Western"
        )

        AlertDialog.Builder(this)
            .setTitle("🎭 Pick a Genre")
            .setItems(genreIcons) { _, which ->
                val genre = genres[which]
                val formatOptions = arrayOf("🎬 Top $genre Movies", "📺 Top $genre Series")
                AlertDialog.Builder(this)
                    .setTitle("Select Format for $genre")
                    .setItems(formatOptions) { _, fmt ->
                        val catalogId = if (fmt == 0) "movie/top/genre=$genre" else "series/top/genre=$genre"
                        val label = formatOptions[fmt]
                        selectedCategory = catalogId
                        selectedLabel = label
                        categoryLabel.text = label
                        categoryLabel.isClickable = false
                        updateTabSelection(catalogId)
                        loadInitialCinemeta(catalogId, label)
                    }
                    .setNegativeButton("Back") { _, _ -> showGenreSelectionDialog() }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Catalog Loading & Endless Pagination ────────────────────

    private fun loadInitialCinemeta(catalogId: String, label: String) {
        isInSearchMode = false
        currentSkip = 0
        hasMoreItems = true
        isLoadingMore = true

        if (catalogId == "history/list") {
            hasMoreItems = false
            isLoadingMore = false
            mediaList.clear()
            val history = loadWatchHistory()
            mediaList.addAll(history)
            mediaAdapter?.notifyDataSetChanged()
            if (history.isEmpty()) {
                categoryLabel.text = label
                categoryLabel.isClickable = false
                loadingText.text = "Watch history is empty. Movies and series you open will be automatically saved here!"
                loadingText.visibility = android.view.View.VISIBLE
            } else {
                loadingText.visibility = android.view.View.GONE
                categoryLabel.text = "$label  •  [ 🗑️ Clear History ]"
                categoryLabel.isClickable = true
                categoryLabel.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Clear Watch History?")
                        .setMessage("Are you sure you want to permanently delete your entire Watch History and saved playback positions?")
                        .setPositiveButton("🗑️ Clear All") { _, _ ->
                            getSharedPreferences("teleflix_watch_history", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                            getSharedPreferences("teleflix_resume_points", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                            getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                            mediaList.clear()
                            mediaAdapter?.notifyDataSetChanged()
                            categoryLabel.text = label
                            categoryLabel.isClickable = false
                            loadingText.text = "Watch history is empty. Movies and series you open will be automatically saved here!"
                            loadingText.visibility = android.view.View.VISIBLE
                            Toast.makeText(this, "Watch history deleted", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            return
        }

        loadingText.text = "Loading $label from Cinemeta..."
        loadingText.visibility = android.view.View.VISIBLE

        val type = if (catalogId.startsWith("series")) "series" else "movie"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://v3-cinemeta.strem.io/catalog/$catalogId.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val text = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val metas = json.optJSONArray("metas") ?: JSONArray()

                val results = parseMetas(metas, type)

                withContext(Dispatchers.Main) {
                    mediaList.clear()
                    if (catalogId.contains("genre=")) {
                        allGenreCache = results
                        val initialBatch = allGenreCache.take(30)
                        mediaList.addAll(initialBatch)
                        hasMoreItems = allGenreCache.size > 30
                    } else {
                        allGenreCache = emptyList()
                        mediaList.addAll(results)
                        hasMoreItems = results.size >= 20
                    }
                    mediaAdapter?.notifyDataSetChanged()
                    loadingText.visibility = android.view.View.GONE
                    isLoadingMore = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingMore = false
                    loadingText.text = "Failed to load. Showing fallback."
                    loadFallbackCatalog()
                }
            }
        }
    }

    private fun loadMoreCinemeta() {
        if (isLoadingMore || !hasMoreItems || isInSearchMode) return

        if (selectedCategory.contains("genre=")) {
            isLoadingMore = true
            val currentCount = mediaList.size
            val nextBatch = allGenreCache.drop(currentCount).take(30)
            if (nextBatch.isNotEmpty()) {
                mediaList.addAll(nextBatch)
                mediaAdapter?.notifyItemRangeInserted(currentCount, nextBatch.size)
            }
            hasMoreItems = mediaList.size < allGenreCache.size
            isLoadingMore = false
            return
        }

        isLoadingMore = true
        currentSkip += 100  // Cinemeta steps pagination by 100 items

        loadingText.text = "Loading more $selectedLabel..."
        loadingText.visibility = android.view.View.VISIBLE

        val type = if (selectedCategory.startsWith("series")) "series" else "movie"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://v3-cinemeta.strem.io/catalog/$selectedCategory/skip=$currentSkip.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val text = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val metas = json.optJSONArray("metas") ?: JSONArray()

                val newItems = parseMetas(metas, type)

                withContext(Dispatchers.Main) {
                    isLoadingMore = false
                    loadingText.visibility = android.view.View.GONE

                    if (newItems.isNotEmpty()) {
                        val startPos = mediaList.size
                        mediaList.addAll(newItems)
                        mediaAdapter?.notifyItemRangeInserted(startPos, newItems.size)
                    }

                    if (newItems.isEmpty() || newItems.size < 10) {
                        hasMoreItems = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingMore = false
                    loadingText.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun performSearch(query: String) {
        isInSearchMode = true
        categoryLabel.text = "Search: \"$query\""
        loadingText.text = "Searching Cinemeta..."
        loadingText.visibility = android.view.View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val allResults = mutableListOf<MediaItem>()

            for (type in listOf("movie", "series")) {
                try {
                    val url = URL("https://v3-cinemeta.strem.io/catalog/$type/top/search=${java.net.URLEncoder.encode(query, "UTF-8")}.json")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    val text = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(text)
                    val metas = json.optJSONArray("metas") ?: JSONArray()
                    allResults.addAll(parseMetas(metas, type))
                } catch (_: Exception) { }
            }

            withContext(Dispatchers.Main) {
                mediaList.clear()
                if (allResults.isNotEmpty()) {
                    mediaList.addAll(allResults)
                    loadingText.visibility = android.view.View.GONE
                } else {
                    loadingText.text = "No results found for \"$query\""
                }
                mediaAdapter?.notifyDataSetChanged()
            }
        }
    }

    private fun parseMetas(metas: JSONArray, type: String): List<MediaItem> {
        val results = mutableListOf<MediaItem>()
        for (i in 0 until metas.length()) {
            val obj = metas.getJSONObject(i)
            results.add(MediaItem(
                id = obj.optString("id"),
                title = obj.optString("name"),
                posterUrl = obj.optString("poster"),
                year = obj.optString("releaseInfo", obj.optString("year", "")),
                rating = obj.optString("imdbRating", "—"),
                overview = obj.optString("description", ""),
                type = obj.optString("type", type)
            ))
        }
        return results
    }

    private fun toggleCatalogMode() {
        isTelegramCatalogMode = !isTelegramCatalogMode
        if (isTelegramCatalogMode) {
            tabScroll.visibility = android.view.View.GONE
            modeToggleButton.text = "💬 Telegram Channels"
            modeToggleButton.setTextColor(android.graphics.Color.parseColor("#10B981"))
            categoryLabel.text = "Monitored Telegram Channels"
            categoryLabel.isClickable = false
            searchInput.hint = "Default Telegram search (all chats & channels)..."
            loadTelegramChannelsCatalog()
        } else {
            currentOpenChannelId = null
            tabScroll.visibility = android.view.View.VISIBLE
            modeToggleButton.text = "🎬 Cinemeta"
            modeToggleButton.setTextColor(android.graphics.Color.parseColor("#3B82F6"))
            searchInput.hint = "Search Movies & Series..."
            selectedCategory = "movie/top"
            selectedLabel = "Top Movies"
            categoryLabel.text = selectedLabel
            categoryLabel.isClickable = false
            loadInitialCinemeta(selectedCategory, selectedLabel)
        }
    }

    private fun loadTelegramChannelsCatalog() {
        isInSearchMode = false
        currentOpenChannelId = null
        hasMoreItems = false
        isLoadingMore = false
        mediaList.clear()
        mediaAdapter?.notifyDataSetChanged()
        loadingText.visibility = android.view.View.VISIBLE
        loadingText.text = "Loading monitored Telegram channels & names..."
        categoryLabel.text = "Monitored Telegram Channels"
        categoryLabel.isClickable = false
        CoroutineScope(Dispatchers.IO).launch {
            val channels = try {
                TelegramRepository.getCustomChannels(this@MainActivity)
            } catch (e: Exception) {
                emptyList()
            }
            val channelItems = channels.map { ch ->
                val realTitle = TelegramRepository.getChannelTitle(ch)
                MediaItem(
                    id = ch,
                    title = realTitle,
                    posterUrl = "https://cdn-icons-png.flaticon.com/512/2111/2111646.png",
                    year = "Channel",
                    rating = "💬 Telegram",
                    overview = "Tap to view video and audio content in $realTitle.",
                    type = "channel"
                )
            }
            withContext(Dispatchers.Main) {
                loadingText.visibility = android.view.View.GONE
                mediaList.clear()
                if (channelItems.isEmpty()) {
                    loadingText.visibility = android.view.View.VISIBLE
                    loadingText.text = "No Monitored Channels set! Add channels in ⚙️ Settings."
                } else {
                    mediaList.addAll(channelItems)
                    mediaAdapter?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun loadTelegramChannelMedia(channelUsername: String, title: String) {
        isInSearchMode = false
        currentOpenChannelId = channelUsername
        currentOpenTopicId = 0
        lastTelegramFromMessageId = 0L
        hasMoreItems = true
        isLoadingMore = true
        mediaList.clear()
        mediaAdapter?.notifyDataSetChanged()
        loadingText.visibility = android.view.View.VISIBLE
        loadingText.text = "Checking forum topics in $title..."
        categoryLabel.text = "⬅ Back to Channels  •  Browsing: $title"
        categoryLabel.isClickable = true
        categoryLabel.isFocusable = true
        categoryLabel.setOnClickListener {
            loadTelegramChannelsCatalog()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val chatId = TelegramRepository.getChatId(channelUsername)
            val topics = if (chatId != null) {
                try { TelegramRepository.getForumTopics(chatId) } catch (_: Exception) { emptyList() }
            } else emptyList()

            if (topics.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    loadingText.visibility = android.view.View.GONE
                    mediaList.clear()
                    hasMoreItems = false
                    isLoadingMore = false
                    categoryLabel.text = "⬅ Back to Channels  •  Topics in $title"
                    categoryLabel.isClickable = true
                    categoryLabel.setOnClickListener { loadTelegramChannelsCatalog() }

                    topics.forEach { topic ->
                        val thumbUrl = if (topic.thumbnailChatId != 0L && topic.thumbnailMessageId != 0L) {
                            TelegramRepository.getThumbnailUrl(topic.thumbnailChatId, topic.thumbnailMessageId)
                        } else ""

                        mediaList.add(
                            MediaItem(
                                id = "topic_${chatId}_${topic.topicId}_$channelUsername",
                                title = topic.displayName,
                                posterUrl = thumbUrl,
                                year = "Forum Topic",
                                rating = "📋 Topic",
                                overview = "Tap to open topic '${topic.displayName}' in $title and view all media files.",
                                type = "topic"
                            )
                        )
                    }
                    mediaAdapter?.notifyDataSetChanged()
                }
                return@launch
            }

            loadingText.text = "Loading media files from $title..."

            val (mediaMessages, nextFromId) = try {
                TelegramRepository.fetchChannelMedia(channelUsername, fromMessageId = 0L, limit = 100, includeAudio = true)
            } catch (e: Exception) {
                Pair(emptyList<TelegramVideoMessage>(), 0L)
            }

            if (nextFromId > 0L) {
                lastTelegramFromMessageId = nextFromId
            }

            val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)

            withContext(Dispatchers.Main) {
                loadingText.visibility = android.view.View.GONE
                mediaList.clear()
                isLoadingMore = false
                if (groupedItems.isEmpty()) {
                    hasMoreItems = false
                    loadingText.visibility = android.view.View.VISIBLE
                    loadingText.text = "No video or audio files found in $channelUsername."
                } else {
                    hasMoreItems = (nextFromId > 0L)
                    groupedItems.forEach { dItem ->
                        when (dItem) {
                            is DisplayItem.Group -> {
                                val group = dItem.group
                                val firstMsg = group.parts.first()
                                val key = "group_${firstMsg.chatId}_${group.baseName}"
                                val freshIds = group.parts.map { it.fileId }
                                val partSizes = group.parts.map { it.fileSize }
                                val formattedSize = formatFileSize(group.totalSize)
                                val url = TelegramRepository.getMergedStreamUrl(freshIds, group.baseName, partSizes)
                                telegramStreamCache[key] = Pair(url, group.baseName)
                                telegramGroupCache[key] = Pair(group.parts.map { Pair(it.chatId, it.messageId) }, partSizes)
                                val thumbUrl = if (firstMsg.thumbnailFileId != null || firstMsg.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(firstMsg.chatId, firstMsg.messageId, firstMsg.thumbnailFileId)
                                } else ""
                                mediaList.add(
                                    MediaItem(
                                        id = key,
                                        title = "📦 ${group.baseName}",
                                        posterUrl = thumbUrl,
                                        year = formattedSize,
                                        rating = "📦 Split Pack (${group.parts.size} parts)",
                                        overview = "Merged Telegram Split/ZIP Archive (${group.parts.size} split files combined into a single continuous stream). Total size: $formattedSize.",
                                        type = "telegram_media",
                                        streamUrl = url
                                    )
                                )
                            }
                            is DisplayItem.Single -> {
                                val msg = dItem.message
                                val key = "${msg.chatId}_${msg.messageId}"
                                val ext = msg.fileName.substringAfterLast('.', "").lowercase()
                                val formattedSize = formatFileSize(msg.fileSize)
                                val url = if (ext == "zip" && msg.fileSize > 1_000_000) {
                                    TelegramRepository.getZipStreamUrl(msg.fileId, msg.fileName, msg.fileSize)
                                } else {
                                    TelegramRepository.getStreamUrl(msg.fileId, msg.fileName, msg.fileSize)
                                }
                                telegramStreamCache[key] = Pair(url, msg.fileName.ifBlank { "Telegram Media" })
                                val isAudio = msg.mimeType.startsWith("audio/")
                                val badge = when {
                                    ext == "zip" -> "🗄️ ZIP Stream"
                                    isAudio -> "🎵 Audio"
                                    else -> "🎬 Video"
                                }
                                val thumbUrl = if (msg.thumbnailFileId != null || msg.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId, msg.thumbnailFileId)
                                } else ""
                                mediaList.add(
                                    MediaItem(
                                        id = key,
                                        title = if (ext == "zip") "🗄️ ${msg.fileName}" else msg.fileName.ifBlank { "Unnamed Media" },
                                        posterUrl = thumbUrl,
                                        year = formattedSize,
                                        rating = badge,
                                        overview = msg.caption.ifBlank { "Telegram File: ${msg.fileName}\nSize: $formattedSize" },
                                        type = "telegram_media",
                                        streamUrl = url
                                    )
                                )
                            }
                        }
                    }
                    mediaAdapter?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun loadTelegramTopicMedia(topicKey: String, topicTitle: String) {
        val parts = topicKey.split("_")
        val topicId = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val channelUsername = parts.getOrNull(3) ?: currentOpenChannelId ?: ""

        isInSearchMode = false
        currentOpenTopicId = topicId
        lastTelegramFromMessageId = 0L
        hasMoreItems = true
        isLoadingMore = true
        mediaList.clear()
        mediaAdapter?.notifyDataSetChanged()
        loadingText.visibility = android.view.View.VISIBLE
        loadingText.text = "Loading media files from topic: $topicTitle..."
        categoryLabel.text = "⬅ Back to Topics  •  Topic: $topicTitle"
        categoryLabel.isClickable = true
        categoryLabel.isFocusable = true
        categoryLabel.setOnClickListener {
            if (channelUsername.isNotBlank()) {
                loadTelegramChannelMedia(channelUsername, channelUsername)
            } else {
                loadTelegramChannelsCatalog()
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val (mediaMessages, nextFromId) = try {
                TelegramRepository.fetchChannelMedia(channelUsername, fromMessageId = 0L, topicId = topicId, limit = 100, includeAudio = true)
            } catch (e: Exception) {
                Pair(emptyList<TelegramVideoMessage>(), 0L)
            }

            if (nextFromId > 0L) {
                lastTelegramFromMessageId = nextFromId
            }

            val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)

            withContext(Dispatchers.Main) {
                loadingText.visibility = android.view.View.GONE
                mediaList.clear()
                isLoadingMore = false
                if (groupedItems.isEmpty()) {
                    hasMoreItems = false
                    loadingText.visibility = android.view.View.VISIBLE
                    loadingText.text = "No video or audio files found in topic '$topicTitle'."
                } else {
                    hasMoreItems = (nextFromId > 0L)
                    groupedItems.forEach { dItem ->
                        when (dItem) {
                            is DisplayItem.Group -> {
                                val group = dItem.group
                                val firstMsg = group.parts.first()
                                val key = "group_${firstMsg.chatId}_${group.baseName}"
                                val freshIds = group.parts.map { it.fileId }
                                val partSizes = group.parts.map { it.fileSize }
                                val formattedSize = formatFileSize(group.totalSize)
                                val url = TelegramRepository.getMergedStreamUrl(freshIds, group.baseName, partSizes)
                                telegramStreamCache[key] = Pair(url, group.baseName)
                                telegramGroupCache[key] = Pair(group.parts.map { Pair(it.chatId, it.messageId) }, partSizes)
                                val thumbUrl = if (firstMsg.thumbnailFileId != null || firstMsg.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(firstMsg.chatId, firstMsg.messageId, firstMsg.thumbnailFileId)
                                } else ""
                                mediaList.add(
                                    MediaItem(
                                        id = key,
                                        title = "📦 ${group.baseName}",
                                        posterUrl = thumbUrl,
                                        year = formattedSize,
                                        rating = "📦 Split Pack (${group.parts.size} parts)",
                                        overview = "Merged Telegram Split/ZIP Archive (${group.parts.size} split files combined into a single continuous stream). Total size: $formattedSize.",
                                        type = "telegram_media",
                                        streamUrl = url
                                    )
                                )
                            }
                            is DisplayItem.Single -> {
                                val msg = dItem.message
                                val key = "${msg.chatId}_${msg.messageId}"
                                val ext = msg.fileName.substringAfterLast('.', "").lowercase()
                                val formattedSize = formatFileSize(msg.fileSize)
                                val url = if (ext == "zip" && msg.fileSize > 1_000_000) {
                                    TelegramRepository.getZipStreamUrl(msg.fileId, msg.fileName, msg.fileSize)
                                } else {
                                    TelegramRepository.getStreamUrl(msg.fileId, msg.fileName, msg.fileSize)
                                }
                                telegramStreamCache[key] = Pair(url, msg.fileName.ifBlank { "Telegram Media" })
                                val isAudio = msg.mimeType.startsWith("audio/")
                                val badge = when {
                                    ext == "zip" -> "🗄️ ZIP Stream"
                                    isAudio -> "🎵 Audio"
                                    else -> "🎬 Video"
                                }
                                val thumbUrl = if (msg.thumbnailFileId != null || msg.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId, msg.thumbnailFileId)
                                } else ""
                                mediaList.add(
                                    MediaItem(
                                        id = key,
                                        title = if (ext == "zip") "🗄️ ${msg.fileName}" else msg.fileName.ifBlank { "Unnamed Media" },
                                        posterUrl = thumbUrl,
                                        year = formattedSize,
                                        rating = badge,
                                        overview = msg.caption.ifBlank { "Telegram File: ${msg.fileName}\nSize: $formattedSize" },
                                        type = "telegram_media",
                                        streamUrl = url
                                    )
                                )
                            }
                        }
                    }
                    mediaAdapter?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun loadMoreTelegramChannelMedia() {
        val channelId = currentOpenChannelId ?: return
        if (isLoadingMore || !hasMoreItems || isInSearchMode) return

        isLoadingMore = true
        loadingText.text = "Loading more media files..."
        loadingText.visibility = android.view.View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val (mediaMessages, nextFromId) = try {
                TelegramRepository.fetchChannelMedia(channelId, fromMessageId = lastTelegramFromMessageId, topicId = currentOpenTopicId, limit = 100, includeAudio = true)
            } catch (e: Exception) {
                Pair(emptyList<TelegramVideoMessage>(), 0L)
            }

            if (nextFromId > 0L) {
                lastTelegramFromMessageId = nextFromId
            }

            val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)

            withContext(Dispatchers.Main) {
                isLoadingMore = false
                loadingText.visibility = android.view.View.GONE

                if (groupedItems.isNotEmpty()) {
                    val startPos = mediaList.size
                    val newMediaItems = mutableListOf<MediaItem>()
                    groupedItems.forEach { dItem ->
                        when (dItem) {
                            is DisplayItem.Group -> {
                                val group = dItem.group
                                val firstMsg = group.parts.first()
                                val key = "group_${firstMsg.chatId}_${group.baseName}"
                                val freshIds = group.parts.map { it.fileId }
                                val partSizes = group.parts.map { it.fileSize }
                                val formattedSize = formatFileSize(group.totalSize)
                                val url = TelegramRepository.getMergedStreamUrl(freshIds, group.baseName, partSizes)
                                telegramStreamCache[key] = Pair(url, group.baseName)
                                telegramGroupCache[key] = Pair(group.parts.map { Pair(it.chatId, it.messageId) }, partSizes)
                                val thumbUrl = if (firstMsg.thumbnailFileId != null || firstMsg.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(firstMsg.chatId, firstMsg.messageId, firstMsg.thumbnailFileId)
                                } else ""
                                newMediaItems.add(
                                    MediaItem(
                                        id = key,
                                        title = "📦 ${group.baseName}",
                                        posterUrl = thumbUrl,
                                        year = formattedSize,
                                        rating = "📦 Split Pack (${group.parts.size} parts)",
                                        overview = "Merged Telegram Split/ZIP Archive (${group.parts.size} split files combined into a single continuous stream). Total size: $formattedSize.",
                                        type = "telegram_media",
                                        streamUrl = url
                                    )
                                )
                            }
                            is DisplayItem.Single -> {
                                val msg = dItem.message
                                val key = "${msg.chatId}_${msg.messageId}"
                                val ext = msg.fileName.substringAfterLast('.', "").lowercase()
                                val formattedSize = formatFileSize(msg.fileSize)
                                val url = if (ext == "zip" && msg.fileSize > 1_000_000) {
                                    TelegramRepository.getZipStreamUrl(msg.fileId, msg.fileName, msg.fileSize)
                                } else {
                                    TelegramRepository.getStreamUrl(msg.fileId, msg.fileName, msg.fileSize)
                                }
                                telegramStreamCache[key] = Pair(url, msg.fileName.ifBlank { "Telegram Media" })
                                val isAudio = msg.mimeType.startsWith("audio/")
                                val badge = when {
                                    ext == "zip" -> "🗄️ ZIP Stream"
                                    isAudio -> "🎵 Audio"
                                    else -> "🎬 Video"
                                }
                                val thumbUrl = if (msg.thumbnailFileId != null || msg.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId, msg.thumbnailFileId)
                                } else ""
                                newMediaItems.add(
                                    MediaItem(
                                        id = key,
                                        title = if (ext == "zip") "🗄️ ${msg.fileName}" else msg.fileName.ifBlank { "Unnamed Media" },
                                        posterUrl = thumbUrl,
                                        year = formattedSize,
                                        rating = badge,
                                        overview = msg.caption.ifBlank { "Telegram File: ${msg.fileName}\nSize: $formattedSize" },
                                        type = "telegram_media",
                                        streamUrl = url
                                    )
                                )
                            }
                        }
                    }
                    if (newMediaItems.isNotEmpty()) {
                        mediaList.addAll(newMediaItems)
                        mediaAdapter?.notifyItemRangeInserted(startPos, newMediaItems.size)
                    }
                }

                if (mediaMessages.isEmpty() || nextFromId == 0L) {
                    hasMoreItems = false
                }
            }
        }
    }

    private fun performTelegramSearch(query: String) {
        isInSearchMode = true
        categoryLabel.text = "Telegram Default Search: \"$query\""
        loadingText.text = "Searching across all Telegram chats, groups & channels..."
        loadingText.visibility = android.view.View.VISIBLE
        mediaList.clear()
        mediaAdapter?.notifyDataSetChanged()

        CoroutineScope(Dispatchers.IO).launch {
            val mediaMessages = try {
                TelegramRepository.searchVideoMessages(query, limit = 200, includeAudio = true)
            } catch (e: Exception) {
                emptyList()
            }

            val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)

            withContext(Dispatchers.Main) {
                loadingText.visibility = android.view.View.GONE
                mediaList.clear()
                if (groupedItems.isEmpty()) {
                    loadingText.visibility = android.view.View.VISIBLE
                    loadingText.text = "No video or audio files matched \"$query\" across your Telegram account."
                } else {
                    groupedItems.forEach { dItem ->
                        when (dItem) {
                            is DisplayItem.Group -> {
                                val group = dItem.group
                                val firstMsg = group.parts.first()
                                val key = "group_${firstMsg.chatId}_${group.baseName}"
                                val freshIds = group.parts.map { it.fileId }
                                val partSizes = group.parts.map { it.fileSize }
                                val totalSizeMb = group.totalSize / (1024 * 1024)
                                val url = TelegramRepository.getMergedStreamUrl(freshIds, group.baseName, partSizes)
                                telegramStreamCache[key] = Pair(url, group.baseName)
                                telegramGroupCache[key] = Pair(group.parts.map { Pair(it.chatId, it.messageId) }, partSizes)
                                val thumbUrl = if (firstMsg.thumbnailFileId != null || firstMsg.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(firstMsg.chatId, firstMsg.messageId, firstMsg.thumbnailFileId)
                                } else ""
                                mediaList.add(
                                    MediaItem(
                                        id = key,
                                        title = "📦 ${group.baseName}",
                                        posterUrl = thumbUrl,
                                        year = "${totalSizeMb} MB",
                                        rating = "📦 Split Pack (${group.parts.size} parts)",
                                        overview = "Merged Telegram Split/ZIP Archive (${group.parts.size} split files combined into a single continuous stream).",
                                        type = "telegram_media",
                                        streamUrl = url
                                    )
                                )
                            }
                            is DisplayItem.Single -> {
                                val msg = dItem.message
                                val key = "${msg.chatId}_${msg.messageId}"
                                val ext = msg.fileName.substringAfterLast('.', "").lowercase()
                                val sizeMb = msg.fileSize / (1024 * 1024)
                                val url = if (ext == "zip" && msg.fileSize > 1_000_000) {
                                    TelegramRepository.getZipStreamUrl(msg.fileId, msg.fileName, msg.fileSize)
                                } else {
                                    TelegramRepository.getStreamUrl(msg.fileId, msg.fileName, msg.fileSize)
                                }
                                telegramStreamCache[key] = Pair(url, msg.fileName.ifBlank { "Telegram Media" })
                                val isAudio = msg.mimeType.startsWith("audio/")
                                val badge = when {
                                    ext == "zip" -> "🗄️ ZIP Stream"
                                    isAudio -> "🎵 Audio"
                                    else -> "🎬 Video"
                                }
                                val thumbUrl = if (msg.thumbnailFileId != null || msg.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId, msg.thumbnailFileId)
                                } else ""
                                mediaList.add(
                                    MediaItem(
                                        id = key,
                                        title = if (ext == "zip") "🗄️ ${msg.fileName}" else msg.fileName.ifBlank { "Unnamed Media" },
                                        posterUrl = thumbUrl,
                                        year = "${sizeMb} MB",
                                        rating = badge,
                                        overview = msg.caption.ifBlank { "Telegram Search Match: ${msg.fileName}\nSize: $sizeMb MB" },
                                        type = "telegram_media",
                                        streamUrl = url
                                    )
                                )
                            }
                        }
                    }
                    mediaAdapter?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun loadFallbackCatalog() {
        mediaList.clear()
        mediaList.add(MediaItem("tt1375666", "Inception", "", "2010", "8.8", "A thief who steals corporate secrets through dream-sharing technology.", "movie"))
        mediaList.add(MediaItem("tt0944947", "Game of Thrones", "", "2011", "9.2", "Nine noble families fight for control over Westeros.", "series"))
        mediaList.add(MediaItem("tt4574334", "Stranger Things", "", "2016", "8.7", "When a young boy vanishes, a small town uncovers a mystery.", "series"))
        mediaList.add(MediaItem("tt0816692", "Interstellar", "", "2014", "8.7", "A team of researchers travels through a wormhole in space.", "movie"))
        mediaAdapter?.notifyDataSetChanged()
    }

    // ── Series Episode Browser ──────────────────────────────────

    private fun fetchSeriesEpisodes(item: MediaItem) {
        Toast.makeText(this, "Loading episodes for ${item.title}...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://v3-cinemeta.strem.io/meta/series/${item.id}.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val text = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val meta = json.optJSONObject("meta") ?: JSONObject()
                val videos = meta.optJSONArray("videos") ?: JSONArray()

                val episodes = mutableListOf<EpisodeItem>()
                for (i in 0 until videos.length()) {
                    val v = videos.getJSONObject(i)
                    val season = v.optInt("season", 0)
                    val episode = v.optInt("episode", 0)
                    if (season > 0 && episode > 0) {
                        episodes.add(EpisodeItem(
                            season = season,
                            episode = episode,
                            title = v.optString("name", "Episode $episode"),
                            overview = v.optString("overview", ""),
                            released = v.optString("released", "")
                        ))
                    }
                }

                val seasons = episodes.groupBy { it.season }.toSortedMap()

                withContext(Dispatchers.Main) {
                    if (seasons.isEmpty()) {
                        Toast.makeText(this@MainActivity, "No episodes found", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    showSeasonPicker(item.title, seasons, item.posterUrl)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to load episodes: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSeasonPicker(seriesTitle: String, seasons: Map<Int, List<EpisodeItem>>, posterUrl: String = "") {
        val seasonLabels = seasons.keys.map { "Season $it (${seasons[it]?.size ?: 0} episodes)" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("$seriesTitle — Pick Season")
            .setItems(seasonLabels) { _, which ->
                val seasonNum = seasons.keys.toList()[which]
                val episodes = seasons[seasonNum] ?: return@setItems
                showEpisodePicker(seriesTitle, seasonNum, episodes, posterUrl)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEpisodePicker(seriesTitle: String, season: Int, episodes: List<EpisodeItem>, posterUrl: String = "") {
        val episodeLabels = episodes.map { "E${String.format("%02d", it.episode)} — ${it.title}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("$seriesTitle — Season $season")
            .setItems(episodeLabels) { _, which ->
                val ep = episodes[which]
                showStreamOptions(seriesTitle, season, ep.episode, posterUrl)
            }
            .setNegativeButton("Back", null)
            .show()
    }

    // ── Stream Selection ────────────────────────────────────────

    private fun showStreamOptions(title: String, season: Int? = null, episode: Int? = null, posterUrl: String = "") {
        val displayTitle = if (season != null && episode != null) {
            "$title S${String.format("%02d", season)}E${String.format("%02d", episode)}"
        } else {
            title
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Searching Telegram Streams")
            .setMessage("Querying ALL groups, channels & chats for:\n'$displayTitle'...")
            .setCancelable(false)
            .show()

        CoroutineScope(Dispatchers.IO).launch {
            val streams = try {
                TdlibManager.resolveStreams(title, season, episode)
            } catch (e: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                try { progressDialog.dismiss() } catch (_: Exception) {}

                if (streams.isEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No Streams Found")
                        .setMessage("Could not find video files matching '$displayTitle' across your connected Telegram account or monitored channels.\n\nMake sure your account is connected in Settings and that your monitored channels contain video streams.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@withContext
                }

                val scrollView = android.widget.ScrollView(this@MainActivity).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#090A0F"))
                }
                val cardList = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 24, 24, 24)
                }

                val headerText = TextView(this@MainActivity).apply {
                    text = "Streams Found for $displayTitle (${streams.size})"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#E2E8F0"))
                    setPadding(8, 8, 8, 24)
                }
                cardList.addView(headerText)

                val streamDialog = AlertDialog.Builder(this@MainActivity)
                    .setView(scrollView)
                    .setNegativeButton("Close", null)
                    .create()

                for (stream in streams) {
                    val card = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(android.graphics.Color.parseColor("#1E293B"))
                        setPadding(32, 28, 32, 28)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, 20) }
                        layoutParams = lp
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            streamDialog.dismiss()
                            checkResumeAndSelectPlayer(stream.url, displayTitle, posterUrl, stream.id)
                        }
                    }

                    val titleText = TextView(this@MainActivity).apply {
                        text = stream.fileName
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(android.graphics.Color.WHITE)
                        setPadding(0, 0, 0, 12)
                    }
                    card.addView(titleText)

                    val infoRow = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }

                    val qualityBadge = TextView(this@MainActivity).apply {
                        text = "🎬 ${stream.quality}"
                        textSize = 12f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(android.graphics.Color.parseColor("#10B981"))
                        val badgeLp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 24, 0) }
                        layoutParams = badgeLp
                    }
                    infoRow.addView(qualityBadge)

                    val sizeBadge = TextView(this@MainActivity).apply {
                        text = "💾 ${stream.size}"
                        textSize = 12f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(android.graphics.Color.parseColor("#38BDF8"))
                    }
                    infoRow.addView(sizeBadge)

                    val spacer = android.view.View(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    }
                    infoRow.addView(spacer)

                    val playAction = TextView(this@MainActivity).apply {
                        text = "▶ PLAY"
                        textSize = 13f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(android.graphics.Color.parseColor("#E50914"))
                    }
                    infoRow.addView(playAction)

                    card.addView(infoRow)
                    cardList.addView(card)
                }

                scrollView.addView(cardList)
                streamDialog.show()
            }
        }
    }

    private fun saveLinkToWatchHistory(streamUrl: String, title: String, posterUrl: String, mediaId: String) {
        if (streamUrl.isBlank() && mediaId.isBlank()) return
        val effectiveId = if (mediaId.isNotBlank()) mediaId else "link_" + streamUrl.hashCode()
        val item = MediaItem(
            id = effectiveId,
            title = title,
            posterUrl = posterUrl,
            year = "Watched",
            rating = "▶",
            overview = "Playing stream: $title",
            type = "telegram_media",
            streamUrl = streamUrl
        )
        saveToHistory(item)
    }

    private fun checkResumeAndSelectPlayer(streamUrl: String, title: String, posterUrl: String = "", mediaId: String = "") {
        saveLinkToWatchHistory(streamUrl, title, posterUrl, mediaId)
        val prefsLink = getSharedPreferences("teleflix_resume_points", android.content.Context.MODE_PRIVATE)
        val prefsTitle = getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE)
        var savedPositionMs = 0L
        if (mediaId.isNotBlank()) {
            savedPositionMs = prefsLink.getLong("id_$mediaId", 0L)
            if (savedPositionMs <= 3_000L) {
                savedPositionMs = prefsLink.getLong(mediaId, 0L)
            }
        }
        if (savedPositionMs <= 3_000L) {
            savedPositionMs = prefsLink.getLong(streamUrl, 0L)
        }
        if (savedPositionMs <= 3_000L) {
            savedPositionMs = prefsTitle.getLong("resume_$title", 0L)
        }

        if (savedPositionMs > 3_000L) {
            val formattedTime = formatMillisToTime(savedPositionMs)
            AlertDialog.Builder(this)
                .setTitle("Resume Playback")
                .setMessage("You previously watched '$title' up to $formattedTime.\n\nDo you want to resume where you left off or start from the beginning?")
                .setPositiveButton("▶ Resume ($formattedTime)") { _, _ ->
                    handlePlayerLaunch(streamUrl, title, savedPositionMs, mediaId)
                }
                .setNegativeButton("🔄 Start Over") { _, _ ->
                    handlePlayerLaunch(streamUrl, title, 0L, mediaId)
                }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            handlePlayerLaunch(streamUrl, title, 0L, mediaId)
        }
    }

    private fun handlePlayerLaunch(streamUrl: String, title: String, resumeMs: Long, mediaId: String = "") {
        val prefPlayer = getSharedPreferences("teleflix_preferences", android.content.Context.MODE_PRIVATE)
            .getString("default_player", "ask") ?: "ask"
        if (prefPlayer == "ask") {
            showPlayerActionDialog(streamUrl, title, resumeMs, mediaId)
        } else {
            openStreamInPlayer(prefPlayer, streamUrl, title, resumeMs, mediaId)
        }
    }

    private fun formatMillisToTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun showPlayerActionDialog(streamUrl: String, title: String, resumeMs: Long = 0L, mediaId: String = "") {
        val options = arrayOf(
            "⚡ ExoPlayer (External App / Just Player)",
            "🔵 MPV Player (External App)",
            "🧡 VLC Player",
            "📱 Choose From All Installed Players..."
        )
        AlertDialog.Builder(this)
            .setTitle("Select Video Player")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openStreamInPlayer("exo", streamUrl, title, resumeMs, mediaId)
                    1 -> openStreamInPlayer("mpv", streamUrl, title, resumeMs, mediaId)
                    2 -> openStreamInPlayer("vlc", streamUrl, title, resumeMs, mediaId)
                    3 -> openStreamInPlayer("chooser", streamUrl, title, resumeMs, mediaId)
                }
            }
            .show()
    }

    private fun openStreamInPlayer(playerType: String, streamUrl: String, title: String, resumeMs: Long, mediaId: String = "") {
        activeMediaIdForResume = mediaId
        activeStreamUrlForResume = streamUrl
        activeTitleForResume = title

        // Immediately pre-warm TDLib download for offset 0 so first chunk is available in 0ms to external players
        val fileId = streamUrl.substringAfter("/file/", "").substringBefore("/").substringBefore("?").toIntOrNull()
        if (fileId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                        req.fileId = fileId
                        req.priority = 32
                        req.offset = 0
                        req.limit = 1048576
                        req.synchronous = false
                    })
                }
            }
        }

        val isMkv = title.endsWith(".mkv", ignoreCase = true) || streamUrl.lowercase().contains(".mkv")
        val mimeType = if (isMkv) "video/x-matroska" else "video/*"

        val baseIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(streamUrl), mimeType)
            putExtra("title", title)
            putExtra("filename", title)
            if (resumeMs > 0) {
                putExtra("position", resumeMs.toInt())
                putExtra("extra_position", resumeMs)
                putExtra("resume_position", resumeMs)
                putExtra("position_ms", resumeMs)
                putExtra("start_position", resumeMs)
                putExtra("from_start", false)
            }
        }

        when (playerType) {
            "exo", "mpvex" -> {
                val packagesToTry = listOf("com.brouken.player", "dev.anilbeesetti.nextplayer", "com.nextplayer.app", "com.google.android.exoplayer", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro")

                var launched = false
                for (pkg in packagesToTry) {
                    try {
                        val intent = Intent(baseIntent).apply { setPackage(pkg) }
                        playerLauncher.launch(intent)
                        launched = true
                        break
                    } catch (_: Exception) {}
                }

                if (!launched) {
                    try {
                        val resolveInfo = packageManager.queryIntentActivities(baseIntent, 0)
                        val exoMatch = resolveInfo.firstOrNull { 
                            val pkgName = it.activityInfo.packageName.lowercase()
                            val label = it.loadLabel(packageManager).toString().lowercase()
                            pkgName.contains("brouken") || pkgName.contains("nextplayer") || label.contains("just player") || label.contains("next player") || label.contains("exo")
                        }
                        if (exoMatch != null) {
                            val intent = Intent(baseIntent).apply { setPackage(exoMatch.activityInfo.packageName) }
                            playerLauncher.launch(intent)
                            launched = true
                        }
                    } catch (_: Exception) {}
                }

                if (!launched) {
                    AlertDialog.Builder(this)
                        .setTitle("⚡ ExoPlayer App Not Found")
                        .setMessage("An ExoPlayer-based app (like Just Player or Next Player) was not detected on your phone.\n\nWould you like to select from your installed players or download Just Player (ExoPlayer) from GitHub?")
                        .setPositiveButton("Choose Installed Player") { _, _ ->
                            val chooser = Intent.createChooser(baseIntent, "Select Video Player")
                            try { playerLauncher.launch(chooser) } catch (_: Exception) {
                                Toast.makeText(this, "No video player found on device!", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNeutralButton("Download Just Player") { _, _ ->
                            try {
                                val dlIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/brouken/just-player/releases"))
                                startActivity(dlIntent)
                            } catch (_: Exception) {
                                Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            "mpv" -> {
                val packagesToTry = listOf("is.xyz.mpv", "is.xyz.mpv.debug", "id.nzxm.mpv")
                var launched = false
                for (pkg in packagesToTry) {
                    try {
                        val intent = Intent(baseIntent).apply { setPackage(pkg) }
                        playerLauncher.launch(intent)
                        launched = true
                        break
                    } catch (_: Exception) {}
                }
                if (!launched) {
                    val chooser = Intent.createChooser(baseIntent, "Select MPV Player")
                    try { playerLauncher.launch(chooser) } catch (_: Exception) {}
                }
            }
            "vlc" -> {
                val vlcIntent = Intent(baseIntent).apply { setPackage("org.videolan.vlc") }
                try { playerLauncher.launch(vlcIntent) } catch (e: Exception) {
                    Toast.makeText(this, "VLC Player is not installed on your device", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                val chooser = Intent.createChooser(baseIntent, "Select Video Player")
                try { playerLauncher.launch(chooser) } catch (e: Exception) {
                    Toast.makeText(this, "No video player found on phone!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveToHistory(item: MediaItem) {
        if (item.type == "channel" || item.id == "watch_history") return
        try {
            val prefs = getSharedPreferences("teleflix_watch_history", android.content.Context.MODE_PRIVATE)
            val currentList = loadWatchHistory().toMutableList()
            currentList.removeAll { it.id == item.id || it.title == item.title || it.type == "channel" }
            currentList.add(0, item)
            val trimmed = if (currentList.size > 60) currentList.subList(0, 60) else currentList
            val jsonArray = JSONArray()
            for (m in trimmed) {
                val obj = JSONObject().apply {
                    put("id", m.id)
                    put("title", m.title)
                    put("posterUrl", m.posterUrl)
                    put("year", m.year)
                    put("rating", m.rating)
                    put("overview", m.overview)
                    put("type", m.type)
                    put("streamUrl", m.streamUrl)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString("history_items", jsonArray.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error saving watch history: ${e.message}")
        }
    }

    private fun loadWatchHistory(): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        try {
            val prefs = getSharedPreferences("teleflix_watch_history", android.content.Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("history_items", null) ?: return list
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val itemType = obj.optString("type", "movie")
                if (itemType == "channel") continue
                list.add(
                    MediaItem(
                        id = obj.optString("id", ""),
                        title = obj.optString("title", "Unknown"),
                        posterUrl = TelegramStreamingProxy.refreshUrl(obj.optString("posterUrl", "")),
                        year = obj.optString("year", ""),
                        rating = obj.optString("rating", ""),
                        overview = obj.optString("overview", ""),
                        type = itemType,
                        streamUrl = TelegramStreamingProxy.refreshUrl(obj.optString("streamUrl", ""))
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error loading watch history: ${e.message}")
        }
        return list
    }

    private fun handleItemLongPress(item: MediaItem): Boolean {
        if (item.type == "channel" || item.id == "watch_history" || item.id == "settings") return false
        
        val isHistoryTab = selectedCategory == "history/list"
        val options = if (isHistoryTab) {
            arrayOf("🗑️ Delete from Watch History", "🧹 Clear Entire Watch History", "Cancel")
        } else {
            arrayOf("🗑️ Remove from Watch History & Reset Resume Progress", "Cancel")
        }

        AlertDialog.Builder(this)
            .setTitle("Select: ${item.title}")
            .setItems(options) { _, which ->
                when {
                    which == 0 -> {
                        val currentList = loadWatchHistory().toMutableList()
                        currentList.removeAll { it.id == item.id || it.title == item.title }
                        val jsonArray = JSONArray()
                        for (m in currentList) {
                            val obj = JSONObject().apply {
                                put("id", m.id)
                                put("title", m.title)
                                put("posterUrl", m.posterUrl)
                                put("year", m.year)
                                put("rating", m.rating)
                                put("overview", m.overview)
                                put("type", m.type)
                                put("streamUrl", m.streamUrl)
                            }
                            jsonArray.put(obj)
                        }
                        getSharedPreferences("teleflix_watch_history", android.content.Context.MODE_PRIVATE)
                            .edit().putString("history_items", jsonArray.toString()).apply()

                        getSharedPreferences("teleflix_resume_points", android.content.Context.MODE_PRIVATE)
                            .edit().remove(item.streamUrl).apply()
                        getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE)
                            .edit().remove("resume_${item.title}").apply()

                        if (isHistoryTab) {
                            mediaList.removeAll { it.id == item.id || it.title == item.title }
                            mediaAdapter?.notifyDataSetChanged()
                            if (mediaList.isEmpty()) {
                                categoryLabel.text = selectedLabel
                                categoryLabel.isClickable = false
                                loadingText.text = "Watch history is empty. Movies and series you open will be automatically saved here!"
                                loadingText.visibility = android.view.View.VISIBLE
                            }
                        }
                        Toast.makeText(this, "Removed from Watch History", Toast.LENGTH_SHORT).show()
                    }
                    isHistoryTab && which == 1 -> {
                        getSharedPreferences("teleflix_watch_history", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                        getSharedPreferences("teleflix_resume_points", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                        getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                        mediaList.clear()
                        mediaAdapter?.notifyDataSetChanged()
                        categoryLabel.text = selectedLabel
                        categoryLabel.isClickable = false
                        loadingText.text = "Watch history is empty. Movies and series you open will be automatically saved here!"
                        loadingText.visibility = android.view.View.VISIBLE
                        Toast.makeText(this, "Watch history deleted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
        return true
    }

    override fun onBackPressed() {
        if (currentOpenChannelId != null) {
            loadTelegramChannelsCatalog()
            return
        }
        if (isInSearchMode) {
            isInSearchMode = false
            searchInput.setText("")
            if (isTelegramCatalogMode) {
                loadTelegramChannelsCatalog()
            } else {
                categoryLabel.text = selectedLabel
                categoryLabel.isClickable = false
                loadInitialCinemeta(selectedCategory, selectedLabel)
            }
            return
        }
        super.onBackPressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { TelegramService.start(this) } catch (_: Exception) {}
        }
    }
}
