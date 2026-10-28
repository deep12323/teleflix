package com.teleflix.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SearchActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var clearBtn: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var filterChipsLayout: LinearLayout
    private lateinit var searchRecyclerView: RecyclerView

    private var activeFilter = "all" // "all", "movies", "series", "channels"
    private var lastQuery: String = ""
    private var searchDebounceJob: Job? = null

    private val searchItems = mutableListOf<MediaItem>()
    private var searchAdapter: MediaAdapter? = null

    private var allMovies = listOf<MediaItem>()
    private var allSeries = listOf<MediaItem>()
    private var allChannelMatches = listOf<MediaItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildLayout()

        val initialQuery = intent.getStringExtra("query") ?: ""
        if (initialQuery.isNotBlank()) {
            searchInput.setText(initialQuery)
            searchInput.setSelection(initialQuery.length)
            triggerSearch(initialQuery)
        } else {
            showSearchSuggestions()
        }
    }

    private fun dp(v: Int): Int = UITheme.dpToPx(this, v)

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0F"))
            fitsSystemWindows = true
            val pad = dp(12)
            setPadding(pad, pad, pad, 0)
        }

        // ── 1. Top Search Bar ──
        val searchBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val s = dp(38)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                setMargins(0, 0, dp(8), 0)
            }
            background = UITheme.createCardShape(this@SearchActivity, "#161924", 19, "#252C3D", 1)
            setOnClickListener { finish() }
        }
        searchBarLayout.addView(backBtn)

        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UITheme.createCardShape(this@SearchActivity, "#141722", 12, "#262D3D", 1)
            val pH = dp(12)
            val pV = dp(4)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val searchIcon = TextView(this).apply {
            text = "🔍"
            textSize = 14f
            setPadding(0, 0, dp(6), 0)
            setOnClickListener {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
                val q = searchInput.text.toString().trim()
                if (q.isNotEmpty()) {
                    triggerSearch(q)
                }
            }
        }
        inputContainer.addView(searchIcon)

        searchInput = EditText(this).apply {
            hint = "Search Movies, TV Shows, Channels..."
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = null
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(windowToken, 0)
                    val q = text.toString().trim()
                    if (q.isNotEmpty()) {
                        triggerSearch(q)
                    }
                    true
                } else false
            }

            // Auto-search disabled while typing to prevent typing lag and premature queries
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val q = s?.toString()?.trim() ?: ""
                    clearBtn.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                    if (q.isEmpty()) {
                        searchDebounceJob?.cancel()
                        showSearchSuggestions()
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        inputContainer.addView(searchInput)

        clearBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#9CA3AF"))
            gravity = Gravity.CENTER
            val s = dp(24)
            layoutParams = LinearLayout.LayoutParams(s, s)
            visibility = View.GONE
            setOnClickListener {
                searchInput.setText("")
                showSearchSuggestions()
            }
        }
        inputContainer.addView(clearBtn)
        searchBarLayout.addView(inputContainer)

        val searchActionBtn = TextView(this).apply {
            text = "Search"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@SearchActivity, UITheme.PRIMARY, 10, "#FFFFFF", 0)
            val pH = dp(14)
            val pV = dp(8)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8), 0, 0, 0) }
            setOnClickListener {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
                val q = searchInput.text.toString().trim()
                if (q.isNotEmpty()) {
                    triggerSearch(q)
                }
            }
        }
        searchBarLayout.addView(searchActionBtn)
        root.addView(searchBarLayout)

        // ── 2. Filter Chips Row ──
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(10))
        }
        filterChipsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        filterScroll.addView(filterChipsLayout)
        root.addView(filterScroll)
        renderFilterChips()

        // ── 3. Status & Loading ──
        val statusLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        loadingBar = ProgressBar(this).apply {
            isIndeterminate = true
            val s = dp(16)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                setMargins(0, 0, dp(8), 0)
            }
            visibility = View.GONE
        }
        statusLayout.addView(loadingBar)

        statusText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
            visibility = View.GONE
        }
        statusLayout.addView(statusText)
        root.addView(statusLayout)

        // ── 4. Search Results RecyclerView ──
        searchAdapter = MediaAdapter(
            items = searchItems,
            onClick = { item ->
                handleItemClick(item)
            },
            onLongClick = null,
            onBookmarkToggle = null,
            onDownloadClick = null,
            onSeeAllClick = { _, catalogTarget ->
                if (catalogTarget.contains("type=movie") || catalogTarget.contains("movie/top")) {
                    activeFilter = "movies"
                } else if (catalogTarget.contains("type=series") || catalogTarget.contains("series/top")) {
                    activeFilter = "series"
                } else if (catalogTarget.contains("channels")) {
                    activeFilter = "channels"
                }
                renderFilterChips()
                applyActiveFilter()
            }
        )

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val span = if (isLandscape) 12 else 6

        val gridLayout = GridLayoutManager(this, span).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val item = searchItems.getOrNull(position)
                    val currentSpan = spanCount
                    val isLand = currentSpan > 6
                    return when (item?.type) {
                        "horizontal_row", "channel", "hero_billboard" -> currentSpan
                        "telegram_media", "history_group", "topic" -> if (isLand) 4 else 3
                        else -> 2 // 3-column compact grid
                    }
                }
            }
        }

        searchRecyclerView = RecyclerView(this).apply {
            layoutManager = gridLayout
            adapter = searchAdapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            isVerticalScrollBarEnabled = false
        }
        root.addView(searchRecyclerView)

        setContentView(root)
    }

    private fun renderFilterChips() {
        filterChipsLayout.removeAllViews()
        val filters = listOf(
            "all" to "⚡ All",
            "movies" to "🎬 Movies",
            "series" to "📺 TV Shows",
            "channels" to "📁 Channels"
        )

        for ((key, label) in filters) {
            val isSelected = key == activeFilter
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#9CA3AF"))
                background = if (isSelected) {
                    UITheme.createBadgeDrawable(this@SearchActivity, UITheme.PRIMARY, 12)
                } else {
                    UITheme.createCardShape(this@SearchActivity, "#161922", 12, "#262D3D", 1)
                }
                val pV = dp(6)
                val pH = dp(14)
                setPadding(pH, pV, pH, pV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8), 0) }
                setOnClickListener {
                    activeFilter = key
                    renderFilterChips()
                    applyActiveFilter()
                }
            }
            filterChipsLayout.addView(chip)
        }
    }

    private fun showSearchSuggestions() {
        searchItems.clear()
        searchAdapter?.notifyDataSetChanged()
        statusText.text = "Type to search movies, TV shows, and Telegram channels"
        statusText.visibility = View.VISIBLE
        loadingBar.visibility = View.GONE
    }

    private fun triggerSearch(query: String) {
        if (query.isBlank()) {
            showSearchSuggestions()
            return
        }

        lastQuery = query
        loadingBar.visibility = View.VISIBLE
        statusText.text = "Searching Cinemeta & Telegram channels for \"$query\"..."
        statusText.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")

            val moviesDef = async { fetchCinemetaResults("movie", encoded) }
            val seriesDef = async { fetchCinemetaResults("series", encoded) }

            val telegramDef = async {
                val results = mutableListOf<MediaItem>()
                try {
                    val messages = TelegramRepository.searchVideoMessages(query, limit = 80, includeAudio = true)
                    val grouped = TelegramRepository.groupAndPreserveOrder(messages)
                    for (item in grouped) {
                        when (item) {
                            is DisplayItem.Single -> {
                                val msg = item.message
                                val streamUrl = TelegramRepository.getStreamUrl(msg.fileId, msg.fileName, msg.fileSize, msg.chatId, msg.messageId)
                                val audioTag = TdlibManager.extractAudioTags(msg.fileName, msg.caption ?: "")
                                val parsedQ = TelegramSearchMatcher.parseQuality(msg.fileName)
                                val qTag = if (parsedQ != "Unknown") parsedQ else "HD"
                                val sizeStr = TdlibManager.formatBytes(msg.fileSize)
                                val thumbUrl = TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId, msg.thumbnailFileId)
                                results.add(
                                    MediaItem(
                                        id = "${msg.chatId}_${msg.messageId}",
                                        title = msg.fileName.ifBlank { "Telegram Media" },
                                        posterUrl = thumbUrl,
                                        year = if (audioTag.isNotBlank()) audioTag else qTag,
                                        rating = sizeStr,
                                        overview = msg.caption?.takeIf { it.isNotBlank() } ?: "From Telegram Channel",
                                        type = "telegram_media",
                                        streamUrl = streamUrl,
                                        originalFileName = msg.fileName
                                    )
                                )
                            }
                            is DisplayItem.Group -> {
                                val group = item.group
                                val first = group.parts.firstOrNull() ?: continue
                                val freshIds = group.parts.map { it.fileId }
                                val partSizes = group.parts.map { it.fileSize }
                                val groupChats = group.parts.map { it.chatId }
                                val groupMsgs = group.parts.map { it.messageId }
                                val mergedUrl = TelegramRepository.getMergedStreamUrl(freshIds, group.baseName, partSizes, groupChats, groupMsgs)
                                val totalSize = partSizes.sum()
                                val audioTag = TdlibManager.extractAudioTags(group.baseName)
                                val key = "group_${first.chatId}_${group.baseName}"
                                val thumbUrl = if (first.thumbnailFileId != null || first.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(first.chatId, first.messageId, first.thumbnailFileId)
                                } else ""
                                TelegramRepository.groupPartsCache[key] = group.parts
                                TelegramRepository.groupPartsCache[mergedUrl] = group.parts
                                results.add(
                                    MediaItem(
                                        id = key,
                                        title = group.baseName,
                                        posterUrl = thumbUrl,
                                        year = if (audioTag.isNotBlank()) audioTag else "Split Video",
                                        rating = TdlibManager.formatBytes(totalSize),
                                        overview = "Split into ${group.parts.size} parts",
                                        type = "telegram_media",
                                        streamUrl = mergedUrl,
                                        originalFileName = group.baseName
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SearchActivity", "Telegram search failed: ${e.message}")
                }
                results
            }

            val movies = moviesDef.await()
            val series = seriesDef.await()
            val telegramItems = telegramDef.await()

            val cachedLib = AutoLibraryManager.getCachedLibrary(this@SearchActivity)
            val matchedLib = cachedLib.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.originalFileName.contains(query, ignoreCase = true) ||
                it.overview.contains(query, ignoreCase = true)
            }

            val allChannelItems = (telegramItems + matchedLib).distinctBy {
                if (it.id.isNotBlank()) it.id else it.streamUrl
            }

            withContext(Dispatchers.Main) {
                loadingBar.visibility = View.GONE
                allMovies = movies
                allSeries = series
                allChannelMatches = allChannelItems

                val total = movies.size + series.size + allChannelItems.size
                if (total > 0) {
                    statusText.text = "Found $total results for \"$query\""
                } else {
                    statusText.text = "No results found for \"$query\""
                }

                applyActiveFilter()
            }
        }
    }

    private fun applyActiveFilter() {
        searchItems.clear()

        when (activeFilter) {
            "all" -> {
                // Horizontal scrolling shelves for each category
                if (allChannelMatches.isNotEmpty()) {
                    searchItems.add(
                        MediaItem(
                            id = "row_channels",
                            title = "📁 Telegram Channel Files (${allChannelMatches.size})",
                            posterUrl = "",
                            year = "",
                            rating = "",
                            overview = "",
                            type = "horizontal_row",
                            catalogTarget = "channels",
                            horizontalItems = allChannelMatches
                        )
                    )
                }

                if (allMovies.isNotEmpty()) {
                    searchItems.add(
                        MediaItem(
                            id = "row_movies",
                            title = "🎬 Movies (${allMovies.size})",
                            posterUrl = "",
                            year = "",
                            rating = "",
                            overview = "",
                            type = "horizontal_row",
                            catalogTarget = "type=movie",
                            horizontalItems = allMovies
                        )
                    )
                }

                if (allSeries.isNotEmpty()) {
                    searchItems.add(
                        MediaItem(
                            id = "row_series",
                            title = "📺 TV Shows (${allSeries.size})",
                            posterUrl = "",
                            year = "",
                            rating = "",
                            overview = "",
                            type = "horizontal_row",
                            catalogTarget = "type=series",
                            horizontalItems = allSeries
                        )
                    )
                }
            }

            "movies" -> {
                searchItems.addAll(allMovies)
            }

            "series" -> {
                searchItems.addAll(allSeries)
            }

            "channels" -> {
                searchItems.addAll(allChannelMatches)
            }
        }

        searchAdapter?.notifyDataSetChanged()
    }

    private fun handleItemClick(item: MediaItem) {
        if (item.type == "telegram_media" && item.streamUrl.isNotBlank()) {
            val isZip = item.id.startsWith("zip_") || 
                        TelegramRepository.isZipArchiveFilename(item.originalFileName) || 
                        TelegramRepository.isZipArchiveFilename(item.title) || 
                        item.originalFileName.endsWith(".zip", ignoreCase = true) || 
                        item.title.contains(".zip", ignoreCase = true) ||
                        item.streamUrl.contains(".zip", ignoreCase = true)

            if (!isZip) {
                val cachedParts = TelegramRepository.groupPartsCache[item.id]
                if (cachedParts != null && cachedParts.size > 1) {
                    val hasZipPart = cachedParts.any { TelegramRepository.isZipArchiveFilename(it.fileName) || it.fileName.endsWith(".zip", ignoreCase = true) }
                    if (!hasZipPart) {
                        val cleanBase = item.originalFileName.ifBlank { item.title }.removePrefix("📦 ").removePrefix("🗄️ ").trim()
                        PlayerHelper.showGroupPartsSelectionDialog(
                            this,
                            item.id,
                            item.streamUrl,
                            item.originalFileName.ifBlank { item.title },
                            item.posterUrl,
                            cleanBase,
                            cachedParts
                        )
                        return
                    }
                } else if (item.id.startsWith("group_")) {
                    val rest = item.id.removePrefix("group_")
                    val chatId = rest.substringBefore("_").toLongOrNull()
                    val baseName = rest.substringAfter("_")
                    if (chatId != null && chatId != 0L) {
                        Toast.makeText(this, "Loading parts...", Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch(Dispatchers.IO) {
                            val mediaMessages = TelegramRepository.fetchChannelMedia(chatId.toString(), limit = 200).first
                            val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)
                            val matchGroup = groupedItems.filterIsInstance<DisplayItem.Group>()
                                .find { it.group.baseName.equals(baseName, ignoreCase = true) }
                            withContext(Dispatchers.Main) {
                                val matchParts = matchGroup?.group?.parts
                                val hasZipInGroup = matchParts != null && matchParts.any { TelegramRepository.isZipArchiveFilename(it.fileName) || it.fileName.endsWith(".zip", ignoreCase = true) }
                                if (matchParts != null && matchParts.size > 1 && !hasZipInGroup) {
                                    TelegramRepository.groupPartsCache[item.id] = matchParts
                                    PlayerHelper.showGroupPartsSelectionDialog(
                                        this@SearchActivity,
                                        item.id,
                                        item.streamUrl,
                                        item.originalFileName.ifBlank { item.title },
                                        item.posterUrl,
                                        matchGroup.group.baseName,
                                        matchParts
                                    )
                                } else {
                                    PlayerHelper.checkResumeAndSelectPlayer(
                                        this@SearchActivity,
                                        item.streamUrl,
                                        item.originalFileName.ifBlank { item.title },
                                        item.posterUrl,
                                        item.id,
                                        item.originalFileName,
                                        skipSplitCheck = true
                                    )
                                }
                            }
                        }
                        return
                    }
                }
            }
            PlayerHelper.checkResumeAndSelectPlayer(
                this,
                item.streamUrl,
                item.originalFileName.ifBlank { item.title },
                item.posterUrl,
                item.id,
                item.originalFileName,
                skipSplitCheck = isZip
            )
            return
        }

        // Open ARVIO / Netflix-style Details Page
        val intent = Intent(this, MediaDetailsActivity::class.java).apply {
            putExtra("id", item.id)
            putExtra("title", item.title)
            putExtra("posterUrl", item.posterUrl)
            putExtra("year", item.year)
            putExtra("rating", item.rating)
            putExtra("overview", item.overview)
            putExtra("type", item.type)
        }
        startActivity(intent)
    }

    private fun fetchCinemetaResults(type: String, encodedQuery: String): List<MediaItem> {
        return try {
            val url = URL("https://v3-cinemeta.strem.io/catalog/$type/top/search=$encodedQuery.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            val text = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(text)
            val metas = json.optJSONArray("metas") ?: JSONArray()
            val results = mutableListOf<MediaItem>()
            for (i in 0 until metas.length()) {
                val obj = metas.getJSONObject(i)
                val itemType = obj.optString("type", type)
                results.add(
                    MediaItem(
                        id = obj.optString("id"),
                        title = obj.optString("name"),
                        posterUrl = obj.optString("poster"),
                        year = obj.optString("releaseInfo", obj.optString("year", "")),
                        rating = obj.optString("imdbRating", "—"),
                        overview = obj.optString("description", ""),
                        type = if (itemType == "tv") "series" else itemType
                    )
                )
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }
}
