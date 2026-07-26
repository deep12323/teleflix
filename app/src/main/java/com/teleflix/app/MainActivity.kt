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
    val type: String = "movie"  // "movie" or "series"
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
    private lateinit var statusButton: Button
    private lateinit var categoryLabel: TextView
    private lateinit var loadingText: TextView

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
        "New Movies" to "movie/year",
        "New Series" to "series/year",
        "IMDB Top" to "movie/imdbRating"
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

        // Header Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }

        val titleView = TextView(this).apply {
            text = "TELEFLIX"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#E50914"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusButton = Button(this).apply {
            text = "⚙️"
            textSize = 20f
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(android.graphics.Color.parseColor("#93C5FD"))
            setPadding(12, 0, 12, 0)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        header.addView(titleView)
        header.addView(statusButton)
        rootView.addView(header)

        // Search Section
        val searchLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }

        searchInput = EditText(this).apply {
            hint = "Search movies, series..."
            setHintTextColor(android.graphics.Color.parseColor("#6B7280"))
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#161B28"))
            setPadding(24, 14, 24, 14)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        searchButton = Button(this).apply {
            text = "🔍"
            textSize = 16f
            setBackgroundColor(android.graphics.Color.parseColor("#E50914"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                val q = searchInput.text.toString()
                if (q.isNotBlank()) {
                    performSearch(q)
                }
            }
        }

        searchLayout.addView(searchInput)
        searchLayout.addView(searchButton)
        rootView.addView(searchLayout)

        // Category Tabs (Horizontal Scroll)
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, 12)
        }

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        categories.forEach { (label, catalogId) ->
            val tab = Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(android.graphics.Color.WHITE)
                val isSelected = catalogId == selectedCategory
                setBackgroundColor(
                    if (isSelected) android.graphics.Color.parseColor("#E50914")
                    else android.graphics.Color.parseColor("#1F2937")
                )
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 8, 0) }
                layoutParams = lp
                setOnClickListener {
                    selectedCategory = catalogId
                    selectedLabel = label
                    categoryLabel.text = label
                    loadInitialCinemeta(catalogId, label)
                    for (i in 0 until tabRow.childCount) {
                        val child = tabRow.getChildAt(i) as Button
                        val cat = categories[i].second
                        child.setBackgroundColor(
                            if (cat == selectedCategory) android.graphics.Color.parseColor("#E50914")
                            else android.graphics.Color.parseColor("#1F2937")
                        )
                    }
                }
            }
            tabRow.addView(tab)
        }

        tabScroll.addView(tabRow)
        rootView.addView(tabScroll)

        // Category Label
        categoryLabel = TextView(this).apply {
            text = "Top Movies"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, 8)
        }
        rootView.addView(categoryLabel)

        // Loading indicator
        loadingText = TextView(this).apply {
            text = "Loading from Cinemeta..."
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#6B7280"))
            setPadding(0, 0, 0, 8)
        }
        rootView.addView(loadingText)

        // Media Grid
        mediaAdapter = MediaAdapter(mediaList) { item ->
            if (item.type == "series") {
                fetchSeriesEpisodes(item)
            } else {
                showStreamOptions(item.title)
            }
        }

        val gridLayoutManager = GridLayoutManager(this, 2)
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
                        loadMoreCinemeta()
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
        if (TdlibManager.isSessionActive(this)) {
            try { TelegramService.start(this) } catch (_: Exception) {}
        }
        updateStatusButton()
    }

    private fun updateStatusButton() {
        // Only display the settings icon in the header
        statusButton.text = "⚙️"
    }

    // ── Catalog Loading & Endless Pagination ────────────────────

    private fun loadInitialCinemeta(catalogId: String, label: String) {
        isInSearchMode = false
        currentSkip = 0
        hasMoreItems = true
        isLoadingMore = true

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
                    mediaList.addAll(results)
                    mediaAdapter?.notifyDataSetChanged()
                    loadingText.visibility = android.view.View.GONE
                    isLoadingMore = false

                    if (results.size < 20) {
                        hasMoreItems = false
                    }
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
                    showSeasonPicker(item.title, seasons)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to load episodes: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSeasonPicker(seriesTitle: String, seasons: Map<Int, List<EpisodeItem>>) {
        val seasonLabels = seasons.keys.map { "Season $it (${seasons[it]?.size ?: 0} episodes)" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("$seriesTitle — Pick Season")
            .setItems(seasonLabels) { _, which ->
                val seasonNum = seasons.keys.toList()[which]
                val episodes = seasons[seasonNum] ?: return@setItems
                showEpisodePicker(seriesTitle, seasonNum, episodes)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEpisodePicker(seriesTitle: String, season: Int, episodes: List<EpisodeItem>) {
        val episodeLabels = episodes.map { "E${String.format("%02d", it.episode)} — ${it.title}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("$seriesTitle — Season $season")
            .setItems(episodeLabels) { _, which ->
                val ep = episodes[which]
                showStreamOptions(seriesTitle, season, ep.episode)
            }
            .setNegativeButton("Back", null)
            .show()
    }

    // ── Stream Selection ────────────────────────────────────────

    private fun showStreamOptions(title: String, season: Int? = null, episode: Int? = null) {
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

                val streamTitles = streams.map { "${it.fileName}\nQuality: ${it.quality} (${it.size})" }.toTypedArray()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("$displayTitle — Select Stream")
                    .setItems(streamTitles) { _, which ->
                        val selectedStream = streams[which]
                        showPlayerActionDialog(selectedStream.url, displayTitle)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun showPlayerActionDialog(streamUrl: String, title: String) {
        val options = arrayOf("Play in Internal ExoPlayer", "Open in VLC Player", "Open in MPV Player")
        AlertDialog.Builder(this)
            .setTitle("Select Player")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(this, PlayerActivity::class.java).apply {
                            putExtra("VIDEO_URL", streamUrl)
                            putExtra("VIDEO_TITLE", title)
                        }
                        startActivity(intent)
                    }
                    1 -> {
                        val vlcIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(streamUrl), "video/*")
                            setPackage("org.videolan.vlc")
                        }
                        try { startActivity(vlcIntent) } catch (e: Exception) {
                            Toast.makeText(this, "VLC Player not installed", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> {
                        val mpvIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(streamUrl), "video/*")
                            setPackage("is.xyz.mpv")
                        }
                        try { startActivity(mpvIntent) } catch (e: Exception) {
                            Toast.makeText(this, "MPV Player not installed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }
}
