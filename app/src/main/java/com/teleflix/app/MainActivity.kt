package com.teleflix.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    val type: String = "movie",  // "movie" or "series" or "telegram_media" or "history_group" or "hero_billboard" or "horizontal_row"
    val streamUrl: String = "",
    val originalFileName: String = "",
    val fileSize: Long = 0L,
    val groupedFiles: List<MediaItem> = emptyList(),
    val horizontalItems: List<MediaItem> = emptyList(),
    val isTop10Row: Boolean = false,
    val catalogTarget: String = ""
)

data class EpisodeItem(
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String,
    val released: String,
    val thumbnail: String = ""
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
    private lateinit var fabSelectChats: ImageButton
    private lateinit var searchContainer: LinearLayout
    private lateinit var categoryHeaderLayout: LinearLayout
    private lateinit var channelRefreshButton: TextView
    private lateinit var channelMenuButton: TextView
    private var isTelegramCatalogMode = false
    private var currentOpenChannelId: String? = null
    private var currentOpenTopicId: Int = 0
    private var savedChannelsCatalogScrollState: android.os.Parcelable? = null
    private var savedChannelsCatalogPos: Int = -1
    private var savedChannelsCatalogOffset: Int = 0

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
    private var isBrowsingFromBeginning: Boolean = false
    private val telegramStreamCache = mutableMapOf<String, Pair<String, String>>()
    private val telegramGroupCache get() = TelegramRepository.groupCache
    private val telegramGroupPartsCache get() = TelegramRepository.groupPartsCache

    private var activeMediaIdForResume: String = ""
    private var activeStreamUrlForResume: String = ""
    private var activeTitleForResume: String = ""

    private fun extractFileIdsFromUrl(url: String): List<Int> {
        if (url.isBlank()) return emptyList()
        val patterns = listOf("/file/", "/stream/", "/zip/", "/thumbnail/", "/merged/", "/playlist/")
        for (pattern in patterns) {
            if (url.contains(pattern)) {
                val segment = url.substringAfter(pattern).substringBefore("/").substringBefore("?")
                val ids = segment.split(",").mapNotNull { it.toIntOrNull() }.filter { it != 0 }
                if (ids.isNotEmpty()) return ids
            }
        }
        if (url.contains("fileId=")) {
            val idStr = url.substringAfter("fileId=").substringBefore("&")
            val parsed = idStr.toIntOrNull()
            if (parsed != null && parsed != 0) return listOf(parsed)
        }
        if (url.contains("ids=")) {
            val idsStr = url.substringAfter("ids=").substringBefore("&")
            val ids = idsStr.split(",").mapNotNull { it.toIntOrNull() }.filter { it != 0 }
            if (ids.isNotEmpty()) return ids
        }
        return emptyList()
    }

    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val intent = result.data
        if (intent != null) {
            val posLong = intent.getLongExtra("position", -1L)
                .takeIf { it >= 0 } ?: intent.getLongExtra("extra_position", -1L)
                .takeIf { it >= 0 } ?: intent.getLongExtra("position_ms", -1L)
                .takeIf { it >= 0 } ?: intent.getIntExtra("position", -1).takeIf { it >= 0 }?.toLong()
                .takeIf { it != null && it >= 0 } ?: intent.getIntExtra("extra_position", -1).takeIf { it >= 0 }?.toLong()
                .takeIf { it != null && it >= 0 } ?: intent.getIntExtra("position_ms", -1).takeIf { it >= 0 }?.toLong()
                ?: 0L

            val durLong = intent.getLongExtra("duration", -1L)
                .takeIf { it > 0 } ?: intent.getLongExtra("extra_duration", -1L)
                .takeIf { it > 0 } ?: intent.getLongExtra("duration_ms", -1L)
                .takeIf { it > 0 } ?: intent.getIntExtra("duration", -1).takeIf { it > 0 }?.toLong()
                .takeIf { it != null && it > 0 } ?: intent.getIntExtra("extra_duration", -1).takeIf { it > 0 }?.toLong()
                .takeIf { it != null && it > 0 } ?: intent.getIntExtra("duration_ms", -1).takeIf { it > 0 }?.toLong()
                ?: 0L

            val endBy = (intent.getStringExtra("end_by") ?: intent.getStringExtra("endBy") ?: "").trim()
            val isExplicitCompleted = endBy.equals("playback_completion", ignoreCase = true) ||
                endBy.equals("completion", ignoreCase = true) ||
                endBy.equals("completed", ignoreCase = true) ||
                intent.getBooleanExtra("completed", false) ||
                intent.getBooleanExtra("playback_completed", false) ||
                intent.getBooleanExtra("is_completed", false)

            val prefsLink = getSharedPreferences("teleflix_resume_points", android.content.Context.MODE_PRIVATE)
            val prefsTitle = getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE)
            val editLink = prefsLink.edit()
            val editTitle = prefsTitle.edit()

            val existingDur = (if (activeMediaIdForResume.isNotBlank()) prefsLink.getLong("dur_id_$activeMediaIdForResume", 0L) else 0L)
                .takeIf { it > 0L }
                ?: (if (activeStreamUrlForResume.isNotBlank()) prefsLink.getLong("dur_$activeStreamUrlForResume", 0L) else 0L)
                .takeIf { it > 0L }
                ?: (if (activeTitleForResume.isNotBlank()) prefsTitle.getLong("dur_$activeTitleForResume", 0L) else 0L)
                .takeIf { it > 0L }
                ?: 0L
            val effectiveDur = if (durLong > 0L) durLong else existingDur

            val isPositionCompleted = if (effectiveDur > 0L && posLong > 0L) {
                posLong >= (effectiveDur * 0.90).toLong() || (posLong >= effectiveDur - 15_000L && effectiveDur > 20_000L) || (posLong >= effectiveDur - 3_000L)
            } else false

            val isCompleted = isExplicitCompleted || isPositionCompleted

            if (isCompleted || posLong <= 3000L) {
                // Video finished completely or stopped right at start - clear resume points
                if (activeMediaIdForResume.isNotBlank()) {
                    editLink.remove("id_$activeMediaIdForResume")
                    editLink.remove(activeMediaIdForResume)
                    editLink.remove("dur_id_$activeMediaIdForResume")
                    editLink.remove("dur_$activeMediaIdForResume")
                }
                if (activeStreamUrlForResume.isNotBlank()) {
                    editLink.remove(activeStreamUrlForResume)
                    editLink.remove("dur_$activeStreamUrlForResume")
                }
                if (activeTitleForResume.isNotBlank()) {
                    editTitle.remove("resume_$activeTitleForResume")
                    editTitle.remove("dur_$activeTitleForResume")
                }
                editLink.apply()
                editTitle.apply()

                if (isCompleted) {
                    TeleflixLogger.log("MainActivity", "Playback completed for '$activeTitleForResume' (pos=$posLong ms, dur=$durLong ms). Cleared resume position.")
                }
            } else {
                // Partially watched - save resume position and duration
                if (activeMediaIdForResume.isNotBlank()) {
                    editLink.putLong("id_$activeMediaIdForResume", posLong)
                    editLink.putLong(activeMediaIdForResume, posLong)
                    if (durLong > 0L) {
                        editLink.putLong("dur_id_$activeMediaIdForResume", durLong)
                        editLink.putLong("dur_$activeMediaIdForResume", durLong)
                    }
                }
                if (activeStreamUrlForResume.isNotBlank()) {
                    editLink.putLong(activeStreamUrlForResume, posLong)
                    if (durLong > 0L) {
                        editLink.putLong("dur_$activeStreamUrlForResume", durLong)
                    }
                }
                if (activeTitleForResume.isNotBlank()) {
                    editTitle.putLong("resume_$activeTitleForResume", posLong)
                    if (durLong > 0L) {
                        editTitle.putLong("dur_$activeTitleForResume", durLong)
                    }
                }
                editLink.apply()
                editTitle.apply()

                TeleflixLogger.log("MainActivity", "Saved resume position $posLong ms (dur=$durLong ms) for $activeTitleForResume")
            }
        }

        // Auto-remove video/audio stream cache on player exit (unless saved for offline download)
        if (activeStreamUrlForResume.isNotBlank()) {
            val fileIds = extractFileIdsFromUrl(activeStreamUrlForResume)
            fileIds.forEach { fId ->
                if (!DownloadManager.isFileIdActive(fId)) {
                    TelegramStreamingProxy.clearStreamCache(fId)
                }
            }
        }
    }

    private val mediaList = mutableListOf<MediaItem>()
    private var mediaAdapter: MediaAdapter? = null

    private val cinemetaCatalogCache = java.util.concurrent.ConcurrentHashMap<String, List<MediaItem>>()
    private val cinemetaSeriesCache = java.util.concurrent.ConcurrentHashMap<String, Map<Int, List<EpisodeItem>>>()

    private var selectedCategory = "home/netflix"
    private var selectedLabel = "🏠 Home"
    private var currentSkip = 0
    private var isLoadingMore = false
    private var hasMoreItems = true
    private var isInSearchMode = false

    private val categories = listOf(
        "🏠 Home" to "home/netflix",
        "🎬 Movies" to "movies/netflix",
        "📺 TV Shows" to "series/netflix",
        "📚 My List" to "library/list",
        "🕒 History" to "history/list",
        "🎭 Genres" to "genres/picker",
        "📁 Channels" to "channels/list"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        TelegramRepository.initialize(this)
        if (savedInstanceState == null) {
            TeleflixLogger.clearLogsOnExit()
        }

        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
            val padH = UITheme.dpToPx(this@MainActivity, 6)
            val padV = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(padH, padV, padH, padV)
            fitsSystemWindows = true
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val padH = UITheme.dpToPx(this@MainActivity, 6)
            val padV = UITheme.dpToPx(this@MainActivity, 16)
            view.setPadding(
                padH + insets.left,
                padV + insets.top,
                padH + insets.right,
                padV + insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        // Top App Header
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pH = UITheme.dpToPx(this@MainActivity, 10)
            val pB = UITheme.dpToPx(this@MainActivity, 14)
            setPadding(pH, 0, pH, pB)
        }

        val titleView = TextView(this).apply {
            text = "TELEFLIX"
            UITheme.applyLargeTitleStyle(this)
            letterSpacing = 0.12f
            setTextColor(Color.parseColor(UITheme.PRIMARY)) // Netflix Red
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(titleView)

        statusButton = TextView(this).apply {
            text = "⚙️"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            background = UITheme.createCardShape(this@MainActivity, UITheme.SURFACE, 14, UITheme.STROKE_COLOR, 1)
            val p = UITheme.dpToPx(this@MainActivity, 10)
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        modeToggleButton = TextView(this).apply {
            text = "💬 Channels"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.NORMAL)
            background = UITheme.createCardShape(this@MainActivity, UITheme.SECONDARY, 14, UITheme.SUCCESS, 1)
            setTextColor(Color.parseColor(UITheme.SUCCESS))
            val pV = UITheme.dpToPx(this@MainActivity, 8)
            val pH = UITheme.dpToPx(this@MainActivity, 14)
            setPadding(pH, pV, pH, pV)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                toggleCatalogMode()
            }
        }

        DownloadManager.init(this)

        val downloadsButton = TextView(this).apply {
            text = "📥"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            background = UITheme.createCardShape(this@MainActivity, UITheme.SURFACE, 14, UITheme.STROKE_COLOR, 1)
            val p = UITheme.dpToPx(this@MainActivity, 10)
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showDownloadsDialog()
            }
        }

        headerLayout.addView(modeToggleButton)
        val headerGap = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(UITheme.dpToPx(this@MainActivity, 8), 1)
        }
        val headerGap2 = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(UITheme.dpToPx(this@MainActivity, 8), 1)
        }
        headerLayout.addView(headerGap)
        headerLayout.addView(downloadsButton)
        headerLayout.addView(headerGap2)
        headerLayout.addView(statusButton)
        rootView.addView(headerLayout)

        // Category Banner / Current Selection Header Layout (Compact & auto-hidden for standard categories)
        categoryHeaderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pH = UITheme.dpToPx(this@MainActivity, 8)
            val pT = UITheme.dpToPx(this@MainActivity, 2)
            val pB = UITheme.dpToPx(this@MainActivity, 4)
            setPadding(pH, pT, pH, pB)
            visibility = android.view.View.GONE
        }

        categoryLabel = TextView(this).apply {
            text = selectedLabel
            UITheme.applySectionTitleStyle(this)
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        categoryHeaderLayout.addView(categoryLabel)

        channelRefreshButton = TextView(this).apply {
            text = "🔄"
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            background = UITheme.createCardShape(this@MainActivity, UITheme.SURFACE, 10, UITheme.STROKE_COLOR, 1)
            val pV = UITheme.dpToPx(this@MainActivity, 2)
            val pH = UITheme.dpToPx(this@MainActivity, 8)
            setPadding(pH, pV, pH, pV)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, UITheme.dpToPx(this@MainActivity, 6), 0)
            }
            layoutParams = lp
            visibility = android.view.View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val banner = categoryLabel.text.toString()
                val title = if (banner.contains("Browsing: ")) banner.substringAfter("Browsing: ")
                            else if (banner.contains("Topic: ")) banner.substringAfter("Topic: ")
                            else currentOpenChannelId ?: "Channel"
                if (currentOpenTopicId > 0 && currentOpenChannelId != null) {
                    loadTelegramTopicMedia("topic_0_${currentOpenTopicId}_${currentOpenChannelId}", title)
                } else if (currentOpenChannelId != null) {
                    loadTelegramChannelMedia(currentOpenChannelId!!, title)
                }
            }
        }
        categoryHeaderLayout.addView(channelRefreshButton)

        channelMenuButton = TextView(this).apply {
            text = "⋮"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            background = UITheme.createCardShape(this@MainActivity, UITheme.SURFACE, 10, UITheme.STROKE_COLOR, 1)
            val pV = UITheme.dpToPx(this@MainActivity, 2)
            val pH = UITheme.dpToPx(this@MainActivity, 10)
            setPadding(pH, pV, pH, pV)
            visibility = android.view.View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showChannelOptionsMenu()
            }
        }
        categoryHeaderLayout.addView(channelMenuButton)
        rootView.addView(categoryHeaderLayout)

        // Loading and Search Spinner / Text
        loadingText = TextView(this).apply {
            text = "Loading catalog..."
            UITheme.applyMetadataStyle(this)
            background = UITheme.createCardShape(this@MainActivity, UITheme.SURFACE, 12, UITheme.STROKE_COLOR, 1)
            val pV = UITheme.dpToPx(this@MainActivity, 8)
            val pH = UITheme.dpToPx(this@MainActivity, 12)
            setPadding(pH, pV, pH, pV)
            visibility = android.view.View.GONE
        }
        rootView.addView(loadingText)

        // Search Box (Cinemeta Search) - Compact Design
        searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = UITheme.createInputBackground(this@MainActivity)
            val pH = UITheme.dpToPx(this@MainActivity, 6)
            val pV = UITheme.dpToPx(this@MainActivity, 2)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 8))
            }
        }

        searchInput = EditText(this).apply {
            hint = "Search Movies, TV Series, Channels..."
            setHintTextColor(Color.parseColor(UITheme.TEXT_SECONDARY))
            setTextColor(Color.WHITE)
            background = null
            val pV = UITheme.dpToPx(this@MainActivity, 6)
            val pH = UITheme.dpToPx(this@MainActivity, 10)
            setPadding(pH, pV, pH, pV)
            textSize = 13.5f
            maxLines = 1
            isFocusable = false
            isClickable = true
            setOnClickListener {
                val intent = Intent(this@MainActivity, SearchActivity::class.java)
                if (text.isNotBlank()) {
                    intent.putExtra("query", text.toString())
                }
                startActivity(intent)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        searchButton = Button(this).apply {
            text = "🔍"
            textSize = 14f
            minHeight = 0
            minimumHeight = 0
            val pV = UITheme.dpToPx(this@MainActivity, 6)
            val pH = UITheme.dpToPx(this@MainActivity, 12)
            setPadding(pH, pV, pH, pV)
            background = UITheme.createBadgeDrawable(this@MainActivity, UITheme.PRIMARY, 10)
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@MainActivity, SearchActivity::class.java)
                val q = searchInput.text.toString()
                if (q.isNotBlank()) {
                    intent.putExtra("query", q)
                }
                startActivity(intent)
            }
        }

        searchContainer.addView(searchInput)
        searchContainer.addView(searchButton)
        rootView.addView(searchContainer)

        // Category Tabs (Horizontal Scroll) - Compact Design
        tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, UITheme.dpToPx(this@MainActivity, 8))
        }

        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        categories.forEach { (label, catalogId) ->
            val tab = Button(this).apply {
                text = label
                textSize = 11.5f
                minHeight = 0
                minimumHeight = 0
                val pV = UITheme.dpToPx(this@MainActivity, 5)
                val pH = UITheme.dpToPx(this@MainActivity, 11)
                setPadding(pH, pV, pH, pV)
                setTypeface(null, android.graphics.Typeface.NORMAL)
                val isSelected = catalogId == selectedCategory
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor(UITheme.TEXT_SECONDARY))
                background = UITheme.createPillDrawable(this@MainActivity, isSelected, UITheme.PRIMARY, UITheme.SURFACE)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, UITheme.dpToPx(this@MainActivity, 6), 0) }
                layoutParams = lp
                setOnClickListener {
                    if (catalogId == "home/netflix") {
                        loadNetflixHome("home")
                        updateTabSelection(catalogId)
                        return@setOnClickListener
                    }
                    if (catalogId == "movies/netflix") {
                        loadNetflixHome("movies")
                        updateTabSelection(catalogId)
                        return@setOnClickListener
                    }
                    if (catalogId == "series/netflix") {
                        loadNetflixHome("series")
                        updateTabSelection(catalogId)
                        return@setOnClickListener
                    }
                    if (catalogId == "genres/picker") {
                        showGenreSelectionDialog()
                        return@setOnClickListener
                    }
                    if (catalogId == "library/list") {
                        loadLibraryCatalog()
                        return@setOnClickListener
                    }
                    if (catalogId == "channels/list") {
                        isTelegramCatalogMode = true
                        modeToggleButton.text = "🎬 ARVIO Stream"
                        modeToggleButton.setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
                        modeToggleButton.background = UITheme.createCardShape(this@MainActivity, UITheme.SECONDARY, 14, UITheme.ACCENT_BLUE, 1)
                        updateCategoryBanner("Monitored Telegram Channels")
                        searchInput.hint = "Search chats & channels..."
                        loadTelegramChannelsCatalog()
                        updateTabSelection(catalogId)
                        return@setOnClickListener
                    }
                    if (isTelegramCatalogMode) {
                        isTelegramCatalogMode = false
                        modeToggleButton.text = "💬 Channels"
                        modeToggleButton.setTextColor(Color.parseColor(UITheme.SUCCESS))
                        modeToggleButton.background = UITheme.createCardShape(this@MainActivity, UITheme.SECONDARY, 14, UITheme.SUCCESS, 1)
                        searchInput.hint = "Search Movies & Series..."
                    }
                    selectedCategory = catalogId
                    selectedLabel = label
                    updateCategoryBanner(label)
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
                "channel" -> {
                    val gridManager = recyclerView.layoutManager as? GridLayoutManager
                    val pos = gridManager?.findFirstVisibleItemPosition() ?: 0
                    val topOffset = gridManager?.findViewByPosition(pos)?.top ?: 0
                    savedChannelsCatalogPos = pos
                    savedChannelsCatalogOffset = topOffset
                    loadTelegramChannelMedia(item.id, item.title)
                }
                "topic" -> loadTelegramTopicMedia(item.id, item.title)
                "history_group" -> showHistoryGroupFilesPicker(item)
                "telegram_media" -> {
                    val streamInfo = telegramStreamCache[item.id]
                    val titleToPlay = streamInfo?.second ?: item.title
                    val fileName = item.originalFileName.ifBlank { titleToPlay }
                    val groupParts = telegramGroupPartsCache[item.id]
                    val groupInfo = telegramGroupCache[item.id]
                    val isZipFile = item.id.startsWith("zip_") || 
                                    TelegramRepository.isZipArchiveFilename(titleToPlay) || 
                                    TelegramRepository.isZipArchiveFilename(fileName) || 
                                    (groupParts != null && groupParts.any { TelegramRepository.isZipArchiveFilename(it.fileName, it.mimeType) })

                    if (isZipFile) {
                        if (groupInfo != null) {
                            CoroutineScope(Dispatchers.Main).launch {
                                val cleanTitle = titleToPlay.removePrefix("📦 ").removePrefix("🗄️ ")
                                val freshUrl = TelegramRepository.getFreshMergedMediaUrl(groupInfo.first, cleanTitle, groupInfo.second)
                                if (freshUrl != null && freshUrl.isNotBlank()) {
                                    checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                } else {
                                    val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                                    checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                }
                            }
                        } else if (item.id.startsWith("group_")) {
                            val rest = item.id.removePrefix("group_")
                            val chatId = rest.substringBefore("_").toLongOrNull()
                            val baseName = rest.substringAfter("_")
                            if (chatId != null && chatId != 0L) {
                                Toast.makeText(this@MainActivity, "Loading archive stream...", Toast.LENGTH_SHORT).show()
                                CoroutineScope(Dispatchers.Main).launch {
                                    val mediaMessages = withContext(Dispatchers.IO) {
                                        TelegramRepository.fetchChannelMedia(chatId.toString(), limit = 1000).first
                                    }
                                    val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)
                                    val matchGroup = groupedItems.filterIsInstance<DisplayItem.Group>()
                                        .find { it.group.baseName.equals(baseName, ignoreCase = true) }
                                    if (matchGroup != null && matchGroup.group.parts.isNotEmpty()) {
                                        val parts = matchGroup.group.parts.map { Pair(it.chatId, it.messageId) }
                                        val sizes = matchGroup.group.parts.map { it.fileSize }
                                        telegramGroupCache[item.id] = Pair(parts, sizes)
                                        telegramGroupPartsCache[item.id] = matchGroup.group.parts
                                        val freshUrl = TelegramRepository.getFreshMergedMediaUrl(parts, baseName, sizes)
                                        if (freshUrl != null && freshUrl.isNotBlank()) {
                                            checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                        } else {
                                            val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                                            if (backupUrl.isNotBlank()) {
                                                checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                            }
                                        }
                                    } else {
                                        val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                                        if (backupUrl.isNotBlank()) {
                                            checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                        }
                                    }
                                }
                            } else {
                                val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                                if (backupUrl.isNotBlank()) {
                                    checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                }
                            }
                        } else {
                            val cleanId = item.id.removePrefix("single_").removePrefix("stream_").removePrefix("automovie_").removePrefix("zip_")
                            val parts = cleanId.split("_")
                            val chatId = parts.getOrNull(0)?.toLongOrNull()
                            val messageId = parts.getOrNull(1)?.toLongOrNull()

                            val isSplitFilename = Regex("(?i)\\.(zip\\.\\d+|z\\d+|part\\d+|7z\\.\\d+|r\\d+|\\d{3,4}|mkv\\.\\d+|mp4\\.\\d+)$").containsMatchIn(fileName)

                            if (chatId != null && messageId != null && (streamInfo == null || isSplitFilename)) {
                                CoroutineScope(Dispatchers.Main).launch {
                                    if (isSplitFilename) {
                                        val mediaMessages = withContext(Dispatchers.IO) {
                                            TelegramRepository.fetchChannelMedia(chatId.toString(), limit = 1000).first
                                        }
                                        val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)
                                        val matchGroup = groupedItems.filterIsInstance<DisplayItem.Group>()
                                            .find { g -> g.group.parts.any { it.messageId == messageId } }
                                        if (matchGroup != null && matchGroup.group.parts.size > 1) {
                                            val gParts = matchGroup.group.parts.map { Pair(it.chatId, it.messageId) }
                                            val gSizes = matchGroup.group.parts.map { it.fileSize }
                                            telegramGroupCache[item.id] = Pair(gParts, gSizes)
                                            telegramGroupPartsCache[item.id] = matchGroup.group.parts
                                            val freshUrl = TelegramRepository.getFreshMergedMediaUrl(gParts, matchGroup.group.baseName, gSizes)
                                            if (freshUrl != null && freshUrl.isNotBlank()) {
                                                checkResumeAndSelectPlayer(freshUrl, matchGroup.group.baseName, item.posterUrl, item.id, fileName)
                                                return@launch
                                            }
                                        }
                                    }
                                    val freshUrl = TelegramRepository.getFreshMediaUrl(chatId, messageId)
                                    if (freshUrl != null && freshUrl.isNotBlank()) {
                                        checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                    } else {
                                        val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                                        if (backupUrl.isNotBlank()) {
                                            checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                        } else {
                                            Toast.makeText(this@MainActivity, "Media link expired or unavailable", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            } else {
                                val rawUrl = streamInfo?.first ?: item.streamUrl
                                val urlToPlay = TelegramStreamingProxy.refreshUrl(rawUrl)
                                if (urlToPlay.isNotBlank()) {
                                    checkResumeAndSelectPlayer(urlToPlay, titleToPlay, item.posterUrl, item.id, fileName)
                                } else {
                                    Toast.makeText(this@MainActivity, "Media link expired or unavailable", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        // Multi-part video file handling (non-ZIP)
                        if (groupParts != null && groupParts.size > 1) {
                            showGroupPartsSelectionDialog(item, groupParts, titleToPlay)
                        } else if (item.id.startsWith("group_")) {
                            val rest = item.id.removePrefix("group_")
                            val chatId = rest.substringBefore("_").toLongOrNull()
                            val baseName = rest.substringAfter("_")
                            if (chatId != null && chatId != 0L) {
                                Toast.makeText(this@MainActivity, "Loading group parts...", Toast.LENGTH_SHORT).show()
                                CoroutineScope(Dispatchers.Main).launch {
                                    val mediaMessages = withContext(Dispatchers.IO) {
                                        TelegramRepository.fetchChannelMedia(chatId.toString(), limit = 1000).first
                                    }
                                    val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)
                                    val matchGroup = groupedItems.filterIsInstance<DisplayItem.Group>()
                                        .find { it.group.baseName.equals(baseName, ignoreCase = true) }
                                    if (matchGroup != null && matchGroup.group.parts.isNotEmpty()) {
                                        val parts = matchGroup.group.parts.map { Pair(it.chatId, it.messageId) }
                                        val sizes = matchGroup.group.parts.map { it.fileSize }
                                        telegramGroupCache[item.id] = Pair(parts, sizes)
                                        telegramGroupPartsCache[item.id] = matchGroup.group.parts
                                        if (matchGroup.group.parts.size > 1) {
                                            showGroupPartsSelectionDialog(item, matchGroup.group.parts, titleToPlay)
                                        } else {
                                            val freshUrl = TelegramRepository.getFreshMergedMediaUrl(parts, baseName, sizes)
                                            if (freshUrl != null && freshUrl.isNotBlank()) {
                                                checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                            } else {
                                                val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                                                if (backupUrl.isNotBlank()) {
                                                    checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                                }
                                            }
                                        }
                                    } else {
                                        val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                                        if (backupUrl.isNotBlank()) {
                                            checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                        }
                                    }
                                }
                            } else {
                                val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                                if (backupUrl.isNotBlank()) {
                                    checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                }
                            }
                        } else if (groupInfo != null) {
                            CoroutineScope(Dispatchers.Main).launch {
                                val cleanTitle = titleToPlay.removePrefix("📦 ")
                                val freshUrl = TelegramRepository.getFreshMergedMediaUrl(groupInfo.first, cleanTitle, groupInfo.second)
                                if (freshUrl != null && freshUrl.isNotBlank()) {
                                    checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                } else {
                                    val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                                    checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                }
                            }
                        } else {
                            val cleanId = item.id.removePrefix("single_").removePrefix("stream_").removePrefix("automovie_")
                            val parts = cleanId.split("_")
                            val chatId = parts.getOrNull(0)?.toLongOrNull()
                            val messageId = parts.getOrNull(1)?.toLongOrNull()

                            if (chatId != null && messageId != null && (groupParts == null || groupParts.isEmpty())) {
                                CoroutineScope(Dispatchers.Main).launch {
                                    val mediaMessages = withContext(Dispatchers.IO) {
                                        val around = TelegramRepository.fetchChannelMedia(chatId.toString(), fromMessageId = maxOf(0L, messageId + 50), limit = 200).first
                                        if (around.any { it.messageId == messageId }) {
                                            around
                                        } else {
                                            val latest = TelegramRepository.fetchChannelMedia(chatId.toString(), fromMessageId = 0L, limit = 200).first
                                            (around + latest).distinctBy { it.messageId }
                                        }
                                    }
                                    val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)
                                    val matchGroup = groupedItems.filterIsInstance<DisplayItem.Group>()
                                        .find { g -> g.group.parts.any { it.messageId == messageId } || (item.originalFileName.isNotBlank() && g.group.baseName.equals(item.originalFileName.substringBeforeLast("."), ignoreCase = true)) }

                                    if (matchGroup != null && matchGroup.group.parts.size > 1) {
                                        telegramGroupPartsCache[item.id] = matchGroup.group.parts
                                        showGroupPartsSelectionDialog(item, matchGroup.group.parts, matchGroup.group.baseName)
                                    } else {
                                        val freshUrl = TelegramRepository.getFreshMediaUrl(chatId, messageId)
                                        if (freshUrl != null && freshUrl.isNotBlank()) {
                                            checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                        } else {
                                            val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                                            if (backupUrl.isNotBlank()) {
                                                checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                                            } else {
                                                Toast.makeText(this@MainActivity, "Media link expired or unavailable", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            } else {
                                val rawUrl = streamInfo?.first ?: item.streamUrl
                                val urlToPlay = TelegramStreamingProxy.refreshUrl(rawUrl)
                                if (urlToPlay.isNotBlank()) {
                                    checkResumeAndSelectPlayer(urlToPlay, titleToPlay, item.posterUrl, item.id, fileName)
                                } else {
                                    Toast.makeText(this@MainActivity, "Media link expired or unavailable", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
                "series" -> {
                    val intent = Intent(this@MainActivity, MediaDetailsActivity::class.java).apply {
                        putExtra("id", item.id)
                        putExtra("title", item.title)
                        putExtra("posterUrl", item.posterUrl)
                        putExtra("year", item.year)
                        putExtra("rating", item.rating)
                        putExtra("overview", item.overview)
                        putExtra("type", "series")
                    }
                    startActivity(intent)
                }
                "hero_billboard" -> {
                    val intent = Intent(this@MainActivity, MediaDetailsActivity::class.java).apply {
                        putExtra("id", item.id)
                        putExtra("title", item.title)
                        putExtra("posterUrl", item.posterUrl)
                        putExtra("year", item.year)
                        putExtra("rating", item.rating)
                        putExtra("overview", item.overview)
                        putExtra("type", if (selectedCategory.startsWith("series")) "series" else "movie")
                    }
                    startActivity(intent)
                }
                "auto_series" -> showAutoSeriesSeasons(item)
                else -> {
                    val intent = Intent(this@MainActivity, MediaDetailsActivity::class.java).apply {
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
            }
        }, { item ->
            handleItemLongPress(item)
        }, { item, nowBookmarked ->
            if (selectedCategory == "library/list" && !nowBookmarked) {
                loadLibraryCatalog()
            }
        }, { item ->
            handleDownloadItem(item)
        }, { categoryTitle, catalogTarget ->
            if (catalogTarget.isNotBlank()) {
                selectedCategory = catalogTarget
                selectedLabel = categoryTitle
                updateCategoryBanner("$categoryTitle  •  [ ⬅ Back to Home ]", isClickable = true) {
                    loadNetflixHome("home")
                }
                loadInitialCinemeta(catalogTarget, categoryTitle)
            }
        })

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val initialSpan = if (isLandscape) 12 else 6
        val gridLayoutManager = GridLayoutManager(this, initialSpan).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val item = mediaList.getOrNull(position)
                    val currentSpan = spanCount
                    val isLandscapeMode = currentSpan > 6
                    return when (item?.type) {
                        "channel", "hero_billboard", "horizontal_row" -> currentSpan
                        "telegram_media", "history_group", "topic" -> if (isLandscapeMode) 4 else 3
                        else -> 2 // 3-column Netflix compact poster grid in portrait (6 / 2 = 3), 6-column in landscape (12 / 2 = 6)
                    }
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

        val mainContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        mainContainer.addView(rootView)

        fabSelectChats = ImageButton(this).apply {
            val sz = UITheme.dpToPx(this@MainActivity, 56)
            val lp = FrameLayout.LayoutParams(sz, sz).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                val marginEnd = UITheme.dpToPx(this@MainActivity, 20)
                val marginBottom = UITheme.dpToPx(this@MainActivity, 24)
                setMargins(0, 0, marginEnd, marginBottom)
            }
            layoutParams = lp
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            background = UITheme.createBadgeDrawable(this@MainActivity, UITheme.PRIMARY, sz / 2)
            elevation = UITheme.dpToPx(this@MainActivity, 8).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showTelegramChatPicker()
            }
        }
        mainContainer.addView(fabSelectChats)

        setContentView(mainContainer)

        val prefs = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
        val defaultMonitored = prefs.getBoolean("default_opening_monitored", false)
        if (defaultMonitored) {
            isTelegramCatalogMode = true
            tabScroll.visibility = android.view.View.VISIBLE
            modeToggleButton.text = "🎬 ARVIO Stream"
            modeToggleButton.setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
            modeToggleButton.background = UITheme.createCardShape(this, UITheme.SECONDARY, 14, UITheme.ACCENT_BLUE, 1)
            updateCategoryBanner("Monitored Telegram Channels")
            searchInput.hint = "Search chats & channels..."
            loadTelegramChannelsCatalog()
        } else {
            isTelegramCatalogMode = false
            tabScroll.visibility = android.view.View.VISIBLE
            modeToggleButton.text = "💬 Channels"
            modeToggleButton.setTextColor(Color.parseColor(UITheme.SUCCESS))
            modeToggleButton.background = UITheme.createCardShape(this, UITheme.SECONDARY, 14, UITheme.SUCCESS, 1)
            searchInput.hint = "Search Movies & Series..."
            selectedCategory = "home/netflix"
            selectedLabel = "🏠 Home"
            updateCategoryBanner(selectedLabel)
            loadNetflixHome("home")
            updateTabSelection(selectedCategory)
        }
    }

    private fun updateCategoryBanner(text: String, isClickable: Boolean = false, onClick: (() -> Unit)? = null) {
        categoryLabel.text = text
        categoryLabel.isClickable = isClickable
        categoryLabel.isFocusable = isClickable
        if (onClick != null) {
            categoryLabel.setOnClickListener { onClick() }
        } else {
            categoryLabel.setOnClickListener(null)
        }
        val isBackAction = text.contains("⬅ Back")
        val isClearAction = text.contains("Clear History") || text.contains("Clear Library") || text.contains("Clear List")
        val isSearchAction = text.startsWith("Search:") || text.startsWith("Telegram Default Search:")
        val isGenreAction = text.contains("Top ") && (text.contains("Movies") || text.contains("Series"))
        val isTelegramMode = isTelegramCatalogMode || (::channelMenuButton.isInitialized && channelMenuButton.visibility == android.view.View.VISIBLE)
        if (::categoryHeaderLayout.isInitialized) {
            categoryHeaderLayout.visibility = if (isBackAction || isClearAction || isSearchAction || isGenreAction || isTelegramMode) android.view.View.VISIBLE else android.view.View.GONE
        }
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
            child.setTextColor(if (isSelected) Color.WHITE else Color.parseColor(UITheme.TEXT_SECONDARY))
            child.background = UITheme.createPillDrawable(this, isSelected, UITheme.PRIMARY, UITheme.SURFACE)
        }
    }

    // ── Netflix-Style Categories & Genres Dashboard ────────────
    private var isGenreMoviesMode = true

    private fun showGenreSelectionDialog() {
        showNetflixGenreDashboard()
    }

    private fun showNetflixGenreDashboard() {
        val genres = listOf(
            "Action" to ("💥" to Pair("#DC2626", "#EA580C")),
            "Adventure" to ("🗺️" to Pair("#047857", "#059669")),
            "Animation" to ("🦄" to Pair("#4F46E5", "#7C3AED")),
            "Comedy" to ("😂" to Pair("#D97706", "#CA8A04")),
            "Crime" to ("🕵️" to Pair("#1E293B", "#334155")),
            "Documentary" to ("🎥" to Pair("#0F766E", "#0D9488")),
            "Drama" to ("🎭" to Pair("#1D4ED8", "#2563EB")),
            "Family" to ("👨‍👩‍👧" to Pair("#EA580C", "#F59E0B")),
            "Fantasy" to ("✨" to Pair("#701A75", "#A21CAF")),
            "History" to ("📜" to Pair("#78350F", "#92400E")),
            "Horror" to ("👻" to Pair("#581C87", "#18181B")),
            "Mystery" to ("🔍" to Pair("#312E81", "#1E1B4B")),
            "Romance" to ("❤️" to Pair("#BE185D", "#E11D48")),
            "Sci-Fi" to ("🛸" to Pair("#4338CA", "#6366F1")),
            "Sport" to ("⚽" to Pair("#15803D", "#16A34A")),
            "Thriller" to ("🔪" to Pair("#881337", "#4C0519")),
            "War" to ("⚔️" to Pair("#3F3F46", "#27272A")),
            "Western" to ("🤠" to Pair("#713F12", "#854D0E"))
        )

        var dialog: AlertDialog? = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        // Top Header Row (Title & Close Button)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pB = UITheme.dpToPx(this@MainActivity, 10)
            setPadding(0, 0, 0, pB)
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = "Explore Genres"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        titleContainer.addView(title)

        val subtitle = TextView(this).apply {
            text = "Browse titles by your favorite category"
            textSize = 12f
            setTextColor(Color.parseColor("#9CA3AF"))
        }
        titleContainer.addView(subtitle)
        headerRow.addView(titleContainer)

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val s = UITheme.dpToPx(this@MainActivity, 36)
            layoutParams = LinearLayout.LayoutParams(s, s)
            background = UITheme.createCardShape(this@MainActivity, "#26FFFFFF", 18, "#20FFFFFF", 1)
            setOnClickListener { dialog?.dismiss() }
        }
        headerRow.addView(closeBtn)
        root.addView(headerRow)

        // Format Toggle Selector: [ 🎬 Movies ]   [ 📺 TV Shows ]
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pV = UITheme.dpToPx(this@MainActivity, 6)
            setPadding(0, pV, 0, pV * 2)
        }

        var updateToggleUI: (() -> Unit)? = null

        val moviesPill = TextView(this).apply {
            text = "🎬 Movies"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            val pH = UITheme.dpToPx(this@MainActivity, 20)
            val pV = UITheme.dpToPx(this@MainActivity, 8)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, UITheme.dpToPx(this@MainActivity, 6), 0)
            }
            setOnClickListener {
                isGenreMoviesMode = true
                updateToggleUI?.invoke()
            }
        }
        toggleRow.addView(moviesPill)

        val seriesPill = TextView(this).apply {
            text = "📺 TV Shows"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            val pH = UITheme.dpToPx(this@MainActivity, 20)
            val pV = UITheme.dpToPx(this@MainActivity, 8)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(UITheme.dpToPx(this@MainActivity, 6), 0, 0, 0)
            }
            setOnClickListener {
                isGenreMoviesMode = false
                updateToggleUI?.invoke()
            }
        }
        toggleRow.addView(seriesPill)

        updateToggleUI = {
            if (isGenreMoviesMode) {
                moviesPill.setTextColor(Color.WHITE)
                moviesPill.background = UITheme.createCardShape(this@MainActivity, "#E50914", 16, "#FFFFFF", 0)
                seriesPill.setTextColor(Color.parseColor("#9CA3AF"))
                seriesPill.background = UITheme.createCardShape(this@MainActivity, "#1E222D", 16, "#33FFFFFF", 1)
            } else {
                seriesPill.setTextColor(Color.WHITE)
                seriesPill.background = UITheme.createCardShape(this@MainActivity, "#E50914", 16, "#FFFFFF", 0)
                moviesPill.setTextColor(Color.parseColor("#9CA3AF"))
                moviesPill.background = UITheme.createCardShape(this@MainActivity, "#1E222D", 16, "#33FFFFFF", 1)
            }
        }
        updateToggleUI.invoke()
        root.addView(toggleRow)

        // 2-Column Responsive Grid of Gradient Genre Cards
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UITheme.dpToPx(this@MainActivity, 460)
            )
            isVerticalScrollBarEnabled = false
        }

        val gridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val cardHeight = UITheme.dpToPx(this@MainActivity, 74)
        val gap = UITheme.dpToPx(this@MainActivity, 8)

        for (i in genres.indices step 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, gap) }
            }

            // Left Card
            val (name1, pair1) = genres[i]
            val (emoji1, colors1) = pair1
            val card1 = createGenreCard(name1, emoji1, colors1.first, colors1.second, cardHeight) {
                dialog?.dismiss()
                onGenreSelected(name1)
            }
            val lp1 = LinearLayout.LayoutParams(0, cardHeight, 1f).apply {
                setMargins(0, 0, gap / 2, 0)
            }
            card1.layoutParams = lp1
            row.addView(card1)

            // Right Card
            if (i + 1 < genres.size) {
                val (name2, pair2) = genres[i + 1]
                val (emoji2, colors2) = pair2
                val card2 = createGenreCard(name2, emoji2, colors2.first, colors2.second, cardHeight) {
                    dialog?.dismiss()
                    onGenreSelected(name2)
                }
                val lp2 = LinearLayout.LayoutParams(0, cardHeight, 1f).apply {
                    setMargins(gap / 2, 0, 0, 0)
                }
                card2.layoutParams = lp2
                row.addView(card2)
            } else {
                val emptySpace = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, cardHeight, 1f)
                }
                row.addView(emptySpace)
            }

            gridContainer.addView(row)
        }

        scrollView.addView(gridContainer)
        root.addView(scrollView)

        dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun createGenreCard(
        name: String,
        emoji: String,
        colorStart: String,
        colorEnd: String,
        height: Int,
        onClick: () -> Unit
    ): View {
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(colorStart), Color.parseColor(colorEnd))
        ).apply {
            cornerRadius = UITheme.dpToPx(this@MainActivity, 14).toFloat()
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradient
            val pH = UITheme.dpToPx(this@MainActivity, 12)
            setPadding(pH, 0, pH, 0)
            isClickable = true
            isFocusable = true

            val emojiView = TextView(this@MainActivity).apply {
                text = emoji
                textSize = 22f
                val pR = UITheme.dpToPx(this@MainActivity, 10)
                setPadding(0, 0, pR, 0)
            }
            addView(emojiView)

            val textContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleView = TextView(this@MainActivity).apply {
                text = name
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            textContainer.addView(titleView)

            val subView = TextView(this@MainActivity).apply {
                text = "Explore ›"
                textSize = 11f
                setTextColor(Color.parseColor("#E5E7EB"))
            }
            textContainer.addView(subView)

            addView(textContainer)
            setOnClickListener { onClick() }
        }
    }

    private fun onGenreSelected(genre: String) {
        val targetCatalog = if (isGenreMoviesMode) "movie/top/genre=$genre" else "series/top/genre=$genre"
        val label = if (isGenreMoviesMode) "🎬 Top $genre Movies" else "📺 Top $genre Series"
        selectedCategory = targetCatalog
        selectedLabel = label
        updateCategoryBanner("$label  •  [ 🎭 Genres ]  •  [ 🏠 Home ]", isClickable = true) {
            showNetflixGenreDashboard()
        }
        updateTabSelection("genres/picker")
        loadInitialCinemeta(targetCatalog, label)
    }

    // ── Catalog Loading & Endless Pagination ────────────────────

    private fun loadLibraryCatalog(label: String = "📚 My List") {
        isInSearchMode = false
        hasMoreItems = false
        isLoadingMore = false
        selectedCategory = "library/list"
        selectedLabel = label
        updateCategoryBanner(label)
        updateTabSelection("library/list")

        val libraryItems = LibraryManager.getBookmarkedItems(this)
        mediaList.clear()
        mediaList.addAll(libraryItems)
        mediaAdapter?.notifyDataSetChanged()

        if (libraryItems.isEmpty()) {
            loadingText.text = "Your List is empty. Tap the 🔖 star icon on any movie or TV series poster to add it to My List!"
            loadingText.visibility = android.view.View.VISIBLE
        } else {
            loadingText.visibility = android.view.View.GONE
            updateCategoryBanner("$label (${libraryItems.size} titles)  •  [ 🗑️ Clear List ]  •  [ ⬅ Back ]", isClickable = true) {
                val opts = arrayOf("🗑️ Clear My List", "🏠 Back to Home")
                AlertDialog.Builder(this)
                    .setTitle("📚 My List Options")
                    .setItems(opts) { _, which ->
                        if (which == 0) {
                            LibraryManager.clearLibrary(this)
                            mediaList.clear()
                            mediaAdapter?.notifyDataSetChanged()
                            updateCategoryBanner(label)
                            loadingText.text = "Your List is empty."
                            loadingText.visibility = android.view.View.VISIBLE
                            Toast.makeText(this, "My List cleared", Toast.LENGTH_SHORT).show()
                        } else {
                            loadNetflixHome("home")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun loadAutoLibrary(filter: String = "auto/all", label: String = "All Media", forceScan: Boolean = false) {
        isInSearchMode = false
        currentOpenChannelId = null
        if (::searchContainer.isInitialized) searchContainer.visibility = android.view.View.VISIBLE
        if (::channelRefreshButton.isInitialized) channelRefreshButton.visibility = android.view.View.GONE
        if (::channelMenuButton.isInitialized) channelMenuButton.visibility = android.view.View.GONE
        hasMoreItems = false
        isLoadingMore = false

        if (filter == "library/list") {
            loadLibraryCatalog(label)
            return
        }

        if (filter == "history/list") {
            hasMoreItems = false
            isLoadingMore = false
            mediaList.clear()
            val history = loadWatchHistory()
            mediaList.addAll(history)
            mediaAdapter?.notifyDataSetChanged()
            if (history.isEmpty()) {
                updateCategoryBanner(label)
                loadingText.text = "Watch history is empty. Movies and series you open will be automatically saved here!"
                loadingText.visibility = android.view.View.VISIBLE
            } else {
                loadingText.visibility = android.view.View.GONE
                updateCategoryBanner("$label  •  [ 🗑️ Clear History ]", isClickable = true) {
                    AlertDialog.Builder(this)
                        .setTitle("Clear Watch History?")
                        .setMessage("Are you sure you want to permanently delete your entire Watch History and saved playback positions?")
                        .setPositiveButton("Clear All") { _, _ ->
                            clearWatchHistory()
                            mediaList.clear()
                            mediaAdapter?.notifyDataSetChanged()
                            loadingText.text = "Watch history cleared."
                            loadingText.visibility = android.view.View.VISIBLE
                            updateCategoryBanner(label)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            return
        }

        val cached = AutoLibraryManager.getCachedLibrary(this)
        if (cached.isNotEmpty() && !forceScan) {
            val filtered = when (filter) {
                "auto/movies" -> cached.filter { it.type != "auto_series" }
                "auto/series" -> cached.filter { it.type == "auto_series" }
                else -> cached
            }
            mediaList.clear()
            mediaList.addAll(filtered)
            mediaAdapter?.notifyDataSetChanged()
            loadingText.visibility = android.view.View.GONE
            updateCategoryBanner("$label (${filtered.size} items)")
            return
        }

        // Cache empty or manual scan requested
        scanAllChannelsLibrary(filter)
    }

    private fun scanAllChannelsLibrary(filter: String = "auto/all") {
        mediaList.clear()
        mediaAdapter?.notifyDataSetChanged()
        loadingText.visibility = android.view.View.VISIBLE
        loadingText.text = "Initializing auto-scanner across all joined channels..."

        lifecycleScope.launch(Dispatchers.Main) {
            val library = AutoLibraryManager.scanAndBuildLibrary(this@MainActivity) { progress ->
                lifecycleScope.launch(Dispatchers.Main) {
                    loadingText.text = progress
                }
            }

            loadingText.visibility = android.view.View.GONE
            if (library.isEmpty()) {
                loadingText.visibility = android.view.View.VISIBLE
                loadingText.text = "No video files (>= 50MB) detected in your joined channels."
            } else {
                val filtered = when (filter) {
                    "auto/movies" -> library.filter { it.type != "auto_series" }
                    "auto/series" -> library.filter { it.type == "auto_series" }
                    else -> library
                }
                mediaList.clear()
                mediaList.addAll(filtered)
                mediaAdapter?.notifyDataSetChanged()
                updateCategoryBanner("$selectedLabel (${filtered.size} items)")
                Toast.makeText(this@MainActivity, "Auto-detected ${library.size} movies & TV series!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadNetflixHome(mode: String = "home") {
        isInSearchMode = false
        currentOpenChannelId = null
        if (::searchContainer.isInitialized) searchContainer.visibility = android.view.View.VISIBLE
        if (::channelRefreshButton.isInitialized) channelRefreshButton.visibility = android.view.View.GONE
        if (::channelMenuButton.isInitialized) channelMenuButton.visibility = android.view.View.GONE
        currentSkip = 0
        hasMoreItems = false
        isLoadingMore = false

        selectedCategory = when (mode) {
            "movies" -> "movies/netflix"
            "series" -> "series/netflix"
            else -> "home/netflix"
        }
        selectedLabel = when (mode) {
            "movies" -> "🎬 Movies"
            "series" -> "📺 TV Shows"
            else -> "🏠 Home"
        }
        updateTabSelection(selectedCategory)
        updateCategoryBanner(selectedLabel)

        val cachedHome = cinemetaCatalogCache["netflix_home_$mode"]
        if (!cachedHome.isNullOrEmpty()) {
            mediaList.clear()
            mediaList.addAll(cachedHome)
            mediaAdapter?.notifyDataSetChanged()
            loadingText.visibility = android.view.View.GONE
            return
        }

        loadingText.text = "Loading Netflix Experience from Cinemeta..."
        loadingText.visibility = android.view.View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rows = mutableListOf<MediaItem>()

                // Concurrently fetch catalog rows from Cinemeta
                val topMoviesDeferred = async { fetchCinemetaRow("movie/top") }
                val topSeriesDeferred = async { fetchCinemetaRow("series/top") }
                val actionDeferred = async { fetchCinemetaRow("movie/top/genre=Action") }
                val comedyDeferred = async { fetchCinemetaRow("movie/top/genre=Comedy") }
                val scifiDeferred = async { fetchCinemetaRow("movie/top/genre=Sci-Fi") }
                val thrillerDeferred = async { fetchCinemetaRow("movie/top/genre=Thriller") }
                val newMoviesDeferred = async { fetchCinemetaRow("movie/year") }
                val imdbTopDeferred = async { fetchCinemetaRow("movie/imdbRating") }
                val dramaSeriesDeferred = async { fetchCinemetaRow("series/top/genre=Drama") }
                val animationDeferred = async { fetchCinemetaRow("series/top/genre=Animation") }

                val topMovies = topMoviesDeferred.await()
                val topSeries = topSeriesDeferred.await()

                // Hero Featured Billboard (Top #1 Title)
                val featured = if (mode == "series") topSeries.firstOrNull() else topMovies.firstOrNull()
                if (featured != null) {
                    rows.add(featured.copy(type = "hero_billboard"))
                }

                if (mode == "home" || mode == "movies") {
                    if (topMovies.isNotEmpty()) {
                        rows.add(
                            MediaItem(
                                id = "row_top_movies",
                                title = "🔥 Top 10 Movies Today",
                                posterUrl = "",
                                year = "",
                                rating = "",
                                overview = "",
                                type = "horizontal_row",
                                isTop10Row = true,
                                catalogTarget = "movie/top",
                                horizontalItems = topMovies.take(10)
                            )
                        )
                    }
                }

                if (mode == "home" || mode == "series") {
                    if (topSeries.isNotEmpty()) {
                        rows.add(
                            MediaItem(
                                id = "row_top_series",
                                title = "📺 Top 10 TV Shows Today",
                                posterUrl = "",
                                year = "",
                                rating = "",
                                overview = "",
                                type = "horizontal_row",
                                isTop10Row = true,
                                catalogTarget = "series/top",
                                horizontalItems = topSeries.take(10)
                            )
                        )
                    }
                }

                val actionMovies = actionDeferred.await()
                if (actionMovies.isNotEmpty()) {
                    rows.add(
                        MediaItem(
                            id = "row_action",
                            title = "💥 Action & Adventure",
                            posterUrl = "",
                            year = "",
                            rating = "",
                            overview = "",
                            type = "horizontal_row",
                            catalogTarget = "movie/top/genre=Action",
                            horizontalItems = actionMovies
                        )
                    )
                }

                val newMovies = newMoviesDeferred.await()
                if (newMovies.isNotEmpty()) {
                    rows.add(
                        MediaItem(
                            id = "row_new_movies",
                            title = "🍿 New Releases",
                            posterUrl = "",
                            year = "",
                            rating = "",
                            overview = "",
                            type = "horizontal_row",
                            catalogTarget = "movie/year",
                            horizontalItems = newMovies
                        )
                    )
                }

                val comedyMovies = comedyDeferred.await()
                if (comedyMovies.isNotEmpty()) {
                    rows.add(
                        MediaItem(
                            id = "row_comedy",
                            title = "😂 Laugh-Out-Loud Comedies",
                            posterUrl = "",
                            year = "",
                            rating = "",
                            overview = "",
                            type = "horizontal_row",
                            catalogTarget = "movie/top/genre=Comedy",
                            horizontalItems = comedyMovies
                        )
                    )
                }

                val scifiMovies = scifiDeferred.await()
                if (scifiMovies.isNotEmpty()) {
                    rows.add(
                        MediaItem(
                            id = "row_scifi",
                            title = "🔮 Sci-Fi & Fantasy",
                            posterUrl = "",
                            year = "",
                            rating = "",
                            overview = "",
                            type = "horizontal_row",
                            catalogTarget = "movie/top/genre=Sci-Fi",
                            horizontalItems = scifiMovies
                        )
                    )
                }

                val thrillerMovies = thrillerDeferred.await()
                if (thrillerMovies.isNotEmpty()) {
                    rows.add(
                        MediaItem(
                            id = "row_thriller",
                            title = "😱 Edge-of-Your-Seat Thrillers",
                            posterUrl = "",
                            year = "",
                            rating = "",
                            overview = "",
                            type = "horizontal_row",
                            catalogTarget = "movie/top/genre=Thriller",
                            horizontalItems = thrillerMovies
                        )
                    )
                }

                val imdbTop = imdbTopDeferred.await()
                if (imdbTop.isNotEmpty()) {
                    rows.add(
                        MediaItem(
                            id = "row_imdb_top",
                            title = "⭐ Critically Acclaimed (Top IMDb)",
                            posterUrl = "",
                            year = "",
                            rating = "",
                            overview = "",
                            type = "horizontal_row",
                            catalogTarget = "movie/imdbRating",
                            horizontalItems = imdbTop
                        )
                    )
                }

                val dramaSeries = dramaSeriesDeferred.await()
                if (dramaSeries.isNotEmpty()) {
                    rows.add(
                        MediaItem(
                            id = "row_drama_series",
                            title = "🎭 Bingeworthy TV Dramas",
                            posterUrl = "",
                            year = "",
                            rating = "",
                            overview = "",
                            type = "horizontal_row",
                            catalogTarget = "series/top/genre=Drama",
                            horizontalItems = dramaSeries
                        )
                    )
                }

                val animation = animationDeferred.await()
                if (animation.isNotEmpty()) {
                    rows.add(
                        MediaItem(
                            id = "row_animation",
                            title = "👶 Anime & Animation",
                            posterUrl = "",
                            year = "",
                            rating = "",
                            overview = "",
                            type = "horizontal_row",
                            catalogTarget = "series/top/genre=Animation",
                            horizontalItems = animation
                        )
                    )
                }

                if (rows.isNotEmpty()) {
                    cinemetaCatalogCache["netflix_home_$mode"] = rows
                }

                withContext(Dispatchers.Main) {
                    mediaList.clear()
                    mediaList.addAll(rows)
                    mediaAdapter?.notifyDataSetChanged()
                    loadingText.visibility = android.view.View.GONE
                    recyclerView.scrollToPosition(0)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingText.text = "Failed to load rows. Showing fallback."
                    loadFallbackCatalog()
                }
            }
        }
    }

    private suspend fun fetchCinemetaRow(catalogId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        if (cinemetaCatalogCache.containsKey(catalogId)) {
            val cached = cinemetaCatalogCache[catalogId]
            if (!cached.isNullOrEmpty()) return@withContext cached
        }
        try {
            val urlString = if (catalogId.contains("genre=")) {
                "https://v3-cinemeta.strem.io/catalog/$catalogId&skip=0.json"
            } else {
                "https://v3-cinemeta.strem.io/catalog/$catalogId.json"
            }
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val text = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(text)
            val metas = json.optJSONArray("metas") ?: JSONArray()
            val type = if (catalogId.startsWith("series")) "series" else "movie"
            val results = parseMetas(metas, type)
            if (results.isNotEmpty()) {
                cinemetaCatalogCache[catalogId] = results
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadInitialCinemeta(catalogId: String, label: String) {
        isInSearchMode = false
        currentOpenChannelId = null
        if (::searchContainer.isInitialized) searchContainer.visibility = android.view.View.VISIBLE
        if (::channelRefreshButton.isInitialized) channelRefreshButton.visibility = android.view.View.GONE
        if (::channelMenuButton.isInitialized) channelMenuButton.visibility = android.view.View.GONE
        currentSkip = 0
        hasMoreItems = true
        isLoadingMore = true

        if (catalogId == "home/netflix") {
            loadNetflixHome("home")
            return
        }

        if (catalogId == "movies/netflix") {
            loadNetflixHome("movies")
            return
        }

        if (catalogId == "series/netflix") {
            loadNetflixHome("series")
            return
        }

        if (catalogId == "library/list") {
            loadLibraryCatalog(label)
            return
        }

        if (catalogId == "history/list") {
            hasMoreItems = false
            isLoadingMore = false
            mediaList.clear()
            val history = loadWatchHistory()
            mediaList.addAll(history)
            mediaAdapter?.notifyDataSetChanged()
            if (history.isEmpty()) {
                updateCategoryBanner(label)
                loadingText.text = "Watch history is empty. Movies and series you open will be automatically saved here!"
                loadingText.visibility = android.view.View.VISIBLE
            } else {
                loadingText.visibility = android.view.View.GONE
                updateCategoryBanner("$label  •  [ 🗑️ Clear History ]", isClickable = true) {
                    AlertDialog.Builder(this)
                        .setTitle("Clear Watch History?")
                        .setMessage("Are you sure you want to permanently delete your entire Watch History and saved playback positions?")
                        .setPositiveButton("🗑️ Clear All") { _, _ ->
                            getSharedPreferences("teleflix_watch_history", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                            getSharedPreferences("teleflix_resume_points", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                            getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                            mediaList.clear()
                            mediaAdapter?.notifyDataSetChanged()
                            updateCategoryBanner(label)
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

        val hasCached = cinemetaCatalogCache.containsKey(catalogId)
        if (hasCached) {
            val cached = cinemetaCatalogCache[catalogId]
            if (!cached.isNullOrEmpty()) {
                mediaList.clear()
                mediaList.addAll(cached)
                hasMoreItems = true
                mediaAdapter?.notifyDataSetChanged()
                loadingText.visibility = android.view.View.GONE
                isLoadingMore = false
            }
        } else {
            loadingText.text = "Loading $label from Cinemeta..."
            loadingText.visibility = android.view.View.VISIBLE
        }

        val type = if (catalogId.startsWith("series")) "series" else "movie"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlString = if (catalogId.contains("genre=")) {
                    "https://v3-cinemeta.strem.io/catalog/$catalogId&skip=0.json"
                } else {
                    "https://v3-cinemeta.strem.io/catalog/$catalogId.json"
                }
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val text = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val metas = json.optJSONArray("metas") ?: JSONArray()

                val results = parseMetas(metas, type)
                val finalItems = if (results.isNotEmpty() && !catalogId.contains("genre=") && !catalogId.contains("search=") && catalogId != "history/list" && catalogId != "library/list") {
                    val featured = results.first().copy(type = "hero_billboard")
                    listOf(featured) + results.drop(1)
                } else {
                    results
                }
                if (finalItems.isNotEmpty()) {
                    cinemetaCatalogCache[catalogId] = finalItems
                }

                withContext(Dispatchers.Main) {
                    mediaList.clear()
                    mediaList.addAll(finalItems)
                    hasMoreItems = results.size >= 10
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
        if (isLoadingMore || !hasMoreItems || isInSearchMode || selectedCategory.endsWith("/netflix")) return

        isLoadingMore = true
        currentSkip += if (selectedCategory.contains("genre=")) 50 else 100

        loadingText.text = "Loading more $selectedLabel..."
        loadingText.visibility = android.view.View.VISIBLE

        val type = if (selectedCategory.startsWith("series")) "series" else "movie"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlString = if (selectedCategory.contains("genre=")) {
                    "https://v3-cinemeta.strem.io/catalog/$selectedCategory&skip=$currentSkip.json"
                } else {
                    "https://v3-cinemeta.strem.io/catalog/$selectedCategory/skip=$currentSkip.json"
                }
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        isInSearchMode = true
        updateCategoryBanner("Search: \"$trimmed\"  •  [ ⬅ Back to Home ]", isClickable = true) {
            isInSearchMode = false
            searchInput.setText("")
            loadNetflixHome("home")
        }

        loadingText.text = "Searching online metadata & channels for \"$trimmed\"..."
        loadingText.visibility = android.view.View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val encodedQuery = java.net.URLEncoder.encode(trimmed, "UTF-8")

            // Concurrently query movies and series from Cinemeta
            val moviesDeferred = async(Dispatchers.IO) {
                fetchCinemetaSearchRow("movie", encodedQuery)
            }
            val seriesDeferred = async(Dispatchers.IO) {
                fetchCinemetaSearchRow("series", encodedQuery)
            }

            // Concurrently query Telegram channels using native search
            val telegramDeferred = async(Dispatchers.IO) {
                val results = mutableListOf<MediaItem>()
                try {
                    val messages = TelegramRepository.searchVideoMessages(trimmed, limit = 50, includeAudio = true)
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
                                results.add(
                                    MediaItem(
                                        id = "group_${first.chatId}_${group.baseName}",
                                        title = group.baseName,
                                        posterUrl = "",
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
                    android.util.Log.e("MainActivity", "Search telegram error: ${e.message}")
                }
                results
            }

            // Also search local channel library cache
            val cachedLib = AutoLibraryManager.getCachedLibrary(this@MainActivity)
            val matchedLib = cachedLib.filter { 
                it.title.contains(trimmed, ignoreCase = true) || 
                it.originalFileName.contains(trimmed, ignoreCase = true) ||
                it.overview.contains(trimmed, ignoreCase = true)
            }

            val movies = moviesDeferred.await()
            val series = seriesDeferred.await()
            val telegramItems = telegramDeferred.await()

            val combinedChannels = (telegramItems + matchedLib).distinctBy {
                if (it.id.isNotBlank()) it.id else it.streamUrl
            }

            val rows = mutableListOf<MediaItem>()

            // 1. Matched Telegram Channel Files (if any)
            if (combinedChannels.isNotEmpty()) {
                rows.add(
                    MediaItem(
                        id = "search_row_channels",
                        title = "📁 Telegram Channels (${combinedChannels.size})",
                        posterUrl = "",
                        year = "",
                        rating = "",
                        overview = "",
                        type = "horizontal_row",
                        catalogTarget = "",
                        horizontalItems = combinedChannels
                    )
                )
            }

            // 2. Movies Matching Query (Horizontal Scrolling Shelf)
            if (movies.isNotEmpty()) {
                rows.add(
                    MediaItem(
                        id = "search_row_movies",
                        title = "🎬 Movies (${movies.size})",
                        posterUrl = "",
                        year = "",
                        rating = "",
                        overview = "",
                        type = "horizontal_row",
                        catalogTarget = "movie/top/search=$encodedQuery",
                        horizontalItems = movies
                    )
                )
            }

            // 3. TV Series Matching Query (Horizontal Scrolling Shelf)
            if (series.isNotEmpty()) {
                rows.add(
                    MediaItem(
                        id = "search_row_series",
                        title = "📺 TV Shows (${series.size})",
                        posterUrl = "",
                        year = "",
                        rating = "",
                        overview = "",
                        type = "horizontal_row",
                        catalogTarget = "series/top/search=$encodedQuery",
                        horizontalItems = series
                    )
                )
            }

            withContext(Dispatchers.Main) {
                mediaList.clear()
                if (rows.isNotEmpty()) {
                    mediaList.addAll(rows)
                    loadingText.visibility = android.view.View.GONE
                } else {
                    loadingText.text = "No results found for \"$trimmed\""
                    loadingText.visibility = android.view.View.VISIBLE
                }
                mediaAdapter?.notifyDataSetChanged()
            }
        }
    }

    private fun fetchCinemetaSearchRow(type: String, encodedQuery: String): List<MediaItem> {
        return try {
            val url = URL("https://v3-cinemeta.strem.io/catalog/$type/top/search=$encodedQuery.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            val text = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(text)
            val metas = json.optJSONArray("metas") ?: JSONArray()
            parseMetas(metas, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseMetas(metas: JSONArray, type: String): List<MediaItem> {
        val results = mutableListOf<MediaItem>()
        for (i in 0 until metas.length()) {
            val obj = metas.getJSONObject(i)
            val itemType = obj.optString("type", type)
            results.add(MediaItem(
                id = obj.optString("id"),
                title = obj.optString("name"),
                posterUrl = obj.optString("poster"),
                year = obj.optString("releaseInfo", obj.optString("year", "")),
                rating = obj.optString("imdbRating", "—"),
                overview = obj.optString("description", ""),
                type = if (itemType == "tv") "series" else itemType
            ))
        }
        return results
    }

    private fun toggleCatalogMode() {
        isTelegramCatalogMode = !isTelegramCatalogMode
        if (isTelegramCatalogMode) {
            tabScroll.visibility = android.view.View.VISIBLE
            modeToggleButton.text = "🎬 ARVIO Stream"
            modeToggleButton.setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
            modeToggleButton.background = UITheme.createCardShape(this, UITheme.SECONDARY, 14, UITheme.ACCENT_BLUE, 1)
            updateCategoryBanner("Monitored Telegram Channels")
            searchInput.hint = "Search chats & channels..."
            if (currentOpenChannelId != null) {
                if (::searchContainer.isInitialized) searchContainer.visibility = android.view.View.GONE
                if (::channelRefreshButton.isInitialized) channelRefreshButton.visibility = android.view.View.VISIBLE
                if (::channelMenuButton.isInitialized) channelMenuButton.visibility = android.view.View.VISIBLE
            } else {
                if (::searchContainer.isInitialized) searchContainer.visibility = android.view.View.VISIBLE
                if (::channelRefreshButton.isInitialized) channelRefreshButton.visibility = android.view.View.GONE
                if (::channelMenuButton.isInitialized) channelMenuButton.visibility = android.view.View.GONE
                loadTelegramChannelsCatalog()
            }
        } else {
            currentOpenChannelId = null
            if (::searchContainer.isInitialized) searchContainer.visibility = android.view.View.VISIBLE
            if (::channelRefreshButton.isInitialized) channelRefreshButton.visibility = android.view.View.GONE
            if (::channelMenuButton.isInitialized) channelMenuButton.visibility = android.view.View.GONE
            tabScroll.visibility = android.view.View.VISIBLE
            modeToggleButton.text = "💬 Channels"
            modeToggleButton.setTextColor(Color.parseColor(UITheme.SUCCESS))
            modeToggleButton.background = UITheme.createCardShape(this, UITheme.SECONDARY, 14, UITheme.SUCCESS, 1)
            searchInput.hint = "Search Movies & Series..."
            selectedCategory = "home/netflix"
            selectedLabel = "🏠 Home"
            updateCategoryBanner(selectedLabel)
            loadNetflixHome("home")
            updateTabSelection(selectedCategory)
        }
    }

    private fun loadTelegramChannelsCatalog() {
        val prevChannel = currentOpenChannelId
        isInSearchMode = false
        isBrowsingFromBeginning = false
        currentOpenChannelId = null
        hasMoreItems = false
        isLoadingMore = false
        if (::searchContainer.isInitialized) searchContainer.visibility = android.view.View.VISIBLE
        if (::channelRefreshButton.isInitialized) channelRefreshButton.visibility = android.view.View.GONE
        if (::channelMenuButton.isInitialized) channelMenuButton.visibility = android.view.View.GONE
        updateCategoryBanner("Monitored Telegram Channels")

        if (prevChannel != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val cid = TelegramRepository.getChatId(prevChannel)
                    if (cid != null) {
                        TelegramClient.sendRequest(TdApi.CloseChat(cid))
                    }
                } catch (_: Exception) {}
            }
        }

        val channels = try {
            TelegramRepository.getCustomChannels(this@MainActivity)
        } catch (_: Exception) {
            emptyList()
        }

        if (channels.isEmpty()) {
            mediaList.clear()
            mediaAdapter?.notifyDataSetChanged()
            loadingText.visibility = android.view.View.VISIBLE
            loadingText.text = "No Monitored Channels set! Add channels in ⚙️ Settings."
            return
        }

        // 1. INSTANT FIRST PAINT FROM CACHE (<10ms)
        val cachedItems = channels.map { ch ->
            val cachedTitle = TelegramRepository.getCachedChannelTitle(ch, this@MainActivity)
            val numericId = ch.toLongOrNull()
            val cachedPhotoId = if (numericId != null) TelegramRepository.getCachedChatPhotoFileId(numericId, this@MainActivity) else null
            val poster = if (cachedPhotoId != null && cachedPhotoId > 0) {
                TelegramStreamingProxy.getThumbnailUrl(cachedPhotoId)
            } else {
                "https://cdn-icons-png.flaticon.com/512/2111/2111646.png"
            }
            MediaItem(
                id = ch,
                title = cachedTitle,
                posterUrl = poster,
                year = "Channel",
                rating = "💬 Telegram",
                overview = "",
                type = "channel"
            )
        }

        mediaList.clear()
        mediaList.addAll(cachedItems)
        mediaAdapter?.notifyDataSetChanged()
        loadingText.visibility = android.view.View.GONE

        // Restore exact scroll position if returning from inside a channel
        if (savedChannelsCatalogScrollState != null || savedChannelsCatalogPos >= 0) {
            recyclerView.post {
                val gridManager = recyclerView.layoutManager as? GridLayoutManager
                if (savedChannelsCatalogScrollState != null) {
                    gridManager?.onRestoreInstanceState(savedChannelsCatalogScrollState)
                } else if (savedChannelsCatalogPos >= 0) {
                    gridManager?.scrollToPositionWithOffset(savedChannelsCatalogPos, savedChannelsCatalogOffset)
                }
            }
        }

        // 2. PARALLEL BACKGROUND REFRESH FOR FRESH TITLES & PHOTOS
        CoroutineScope(Dispatchers.IO).launch {
            if (TelegramClient.authState.value !is TelegramAuthState.Ready) {
                withTimeoutOrNull(2000L) {
                    TelegramClient.authState.first { it is TelegramAuthState.Ready }
                }
            }
            runCatching {
                TelegramClient.sendRequest(TdApi.LoadChats(TdApi.ChatListMain(), 100))
            }

            val freshChannelItems = coroutineScope {
                channels.map { ch ->
                    async(Dispatchers.IO) {
                        val realTitle = TelegramRepository.getChannelTitle(ch)
                        val numericId = ch.toLongOrNull() ?: TelegramRepository.getChatId(ch)
                        val photoFileId = if (numericId != null) TelegramRepository.getChatPhotoFileId(numericId) else null
                        val poster = if (photoFileId != null && photoFileId > 0) {
                            TelegramStreamingProxy.getThumbnailUrl(photoFileId)
                        } else {
                            "https://cdn-icons-png.flaticon.com/512/2111/2111646.png"
                        }
                        MediaItem(
                            id = ch,
                            title = realTitle,
                            posterUrl = poster,
                            year = "Channel",
                            rating = "💬 Telegram",
                            overview = "",
                            type = "channel"
                        )
                    }
                }.awaitAll()
            }

            withContext(Dispatchers.Main) {
                if (isTelegramCatalogMode && currentOpenChannelId == null) {
                    var changed = mediaList.size != freshChannelItems.size
                    if (!changed) {
                        for (i in freshChannelItems.indices) {
                            if (mediaList[i].id != freshChannelItems[i].id ||
                                mediaList[i].title != freshChannelItems[i].title ||
                                mediaList[i].posterUrl != freshChannelItems[i].posterUrl) {
                                changed = true
                                break
                            }
                        }
                    }

                    if (changed) {
                        val gridManager = recyclerView.layoutManager as? GridLayoutManager
                        val firstPos = savedChannelsCatalogPos.takeIf { it >= 0 } ?: (gridManager?.findFirstVisibleItemPosition() ?: -1)
                        val offset = if (savedChannelsCatalogPos >= 0) savedChannelsCatalogOffset else (gridManager?.findViewByPosition(firstPos)?.top ?: 0)

                        loadingText.visibility = android.view.View.GONE
                        mediaList.clear()
                        mediaList.addAll(freshChannelItems)
                        mediaAdapter?.notifyDataSetChanged()

                        if (firstPos >= 0) {
                            gridManager?.scrollToPositionWithOffset(firstPos, offset)
                        }
                    }
                }
            }
        }
    }

    private fun loadTelegramChannelMedia(channelUsername: String, title: String) {
        if (currentOpenChannelId == null && isTelegramCatalogMode) {
            val gridManager = recyclerView.layoutManager as? GridLayoutManager
            savedChannelsCatalogScrollState = gridManager?.onSaveInstanceState()
            savedChannelsCatalogPos = gridManager?.findFirstVisibleItemPosition() ?: -1
            val v = gridManager?.findViewByPosition(savedChannelsCatalogPos)
            savedChannelsCatalogOffset = v?.top ?: 0
        }

        isInSearchMode = false
        isBrowsingFromBeginning = false
        currentOpenChannelId = channelUsername
        currentOpenTopicId = 0
        lastTelegramFromMessageId = 0L
        hasMoreItems = true
        isLoadingMore = true
        if (::searchContainer.isInitialized) searchContainer.visibility = android.view.View.GONE
        if (::channelRefreshButton.isInitialized) channelRefreshButton.visibility = android.view.View.VISIBLE
        if (::channelMenuButton.isInitialized) channelMenuButton.visibility = android.view.View.VISIBLE
        mediaList.clear()
        mediaAdapter?.notifyDataSetChanged()
        loadingText.visibility = android.view.View.VISIBLE
        loadingText.text = "Checking forum topics in $title..."
        updateCategoryBanner("⬅ Back to Channels  •  Browsing: $title", isClickable = true) {
            loadTelegramChannelsCatalog()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val chatId = TelegramRepository.getChatId(channelUsername)

            // Always invalidate cache when opening channel so user gets newest & latest content
            TelegramRepository.invalidateChannelCache(channelUsername, chatId)

            if (chatId != null) {
                try {
                    TelegramClient.sendRequest(TdApi.OpenChat(chatId))
                } catch (_: Exception) {}
            }

            val topics = if (chatId != null) {
                try { TelegramRepository.getForumTopics(chatId, forceRefresh = true) } catch (_: Exception) { emptyList() }
            } else emptyList()

            if (topics.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    loadingText.visibility = android.view.View.GONE
                    mediaList.clear()
                    hasMoreItems = false
                    isLoadingMore = false
                    updateCategoryBanner("⬅ Back to Channels  •  Topics in $title", isClickable = true) {
                        loadTelegramChannelsCatalog()
                    }

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

            withContext(Dispatchers.Main) {
                loadingText.text = "Loading newest media files from $title..."
            }

            val (mediaMessages, nextFromId) = try {
                TelegramRepository.fetchChannelMedia(channelUsername, fromMessageId = 0L, limit = 100, includeAudio = true, forceRefresh = true)
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
                    populateMediaListFromDisplayItems(groupedItems)
                }
            }
        }
    }

    private fun loadTelegramTopicMedia(topicKey: String, topicTitle: String) {
        val parts = topicKey.split("_")
        val topicId = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val channelUsername = parts.getOrNull(3) ?: currentOpenChannelId ?: ""

        isInSearchMode = false
        isBrowsingFromBeginning = false
        currentOpenTopicId = topicId
        lastTelegramFromMessageId = 0L
        hasMoreItems = true
        isLoadingMore = true
        if (::searchContainer.isInitialized) searchContainer.visibility = android.view.View.GONE
        if (::channelRefreshButton.isInitialized) channelRefreshButton.visibility = android.view.View.VISIBLE
        if (::channelMenuButton.isInitialized) channelMenuButton.visibility = android.view.View.VISIBLE
        mediaList.clear()
        mediaAdapter?.notifyDataSetChanged()
        loadingText.visibility = android.view.View.VISIBLE
        loadingText.text = "Loading media files from topic: $topicTitle..."
        updateCategoryBanner("⬅ Back to Topics  •  Topic: $topicTitle", isClickable = true) {
            if (channelUsername.isNotBlank()) {
                loadTelegramChannelMedia(channelUsername, channelUsername)
            } else {
                loadTelegramChannelsCatalog()
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val chatId = TelegramRepository.getChatId(channelUsername)

            // Always invalidate cache when opening topic so user gets newest & latest content
            TelegramRepository.invalidateChannelCache(channelUsername, chatId)

            if (chatId != null) {
                try {
                    TelegramClient.sendRequest(TdApi.OpenChat(chatId))
                } catch (_: Exception) {}
            }

            val (mediaMessages, nextFromId) = try {
                TelegramRepository.fetchChannelMedia(channelUsername, fromMessageId = 0L, topicId = topicId, limit = 100, includeAudio = true, forceRefresh = true)
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
                    loadingText.text = "No video or audio files found in topic $topicTitle."
                } else {
                    hasMoreItems = (nextFromId > 0L)
                    populateMediaListFromDisplayItems(groupedItems)
                }
            }
        }
    }

    private fun populateMediaListFromDisplayItems(groupedItems: List<DisplayItem>) {
        groupedItems.forEach { dItem ->
            when (dItem) {
                is DisplayItem.Group -> {
                    val group = dItem.group
                    val firstMsg = group.parts.first()
                    val key = "group_${firstMsg.chatId}_${group.baseName}"
                    val freshIds = group.parts.map { it.fileId }
                    val partSizes = group.parts.map { it.fileSize }
                    val groupChats = group.parts.map { it.chatId }
                    val groupMsgs = group.parts.map { it.messageId }
                    val formattedSize = formatFileSize(group.totalSize)
                    val url = TelegramRepository.getMergedStreamUrl(freshIds, group.baseName, partSizes, groupChats, groupMsgs)
                    telegramStreamCache[key] = Pair(url, group.baseName)
                    telegramGroupCache[key] = Pair(group.parts.map { Pair(it.chatId, it.messageId) }, partSizes)
                    telegramGroupPartsCache[key] = group.parts
                    val thumbUrl = if (firstMsg.thumbnailFileId != null || firstMsg.chatId != 0L) {
                        TelegramRepository.getThumbnailUrl(firstMsg.chatId, firstMsg.messageId, firstMsg.thumbnailFileId)
                    } else ""
                    val isZipGroup = group.parts.any { TelegramRepository.isZipArchiveFilename(it.fileName) }
                    mediaList.add(
                        MediaItem(
                            id = key,
                            title = "📦 ${group.baseName}",
                            posterUrl = thumbUrl,
                            year = formattedSize,
                            rating = "📦 Split Pack (${group.parts.size} parts)",
                            overview = if (isZipGroup) "Split ZIP" else "Split Video",
                            type = "telegram_media",
                            streamUrl = url
                        )
                    )
                }
                is DisplayItem.Single -> {
                    val msg = dItem.message
                    val key = "${msg.chatId}_${msg.messageId}"
                    val isZip = TelegramRepository.isZipArchiveFilename(msg.fileName)
                    val formattedSize = formatFileSize(msg.fileSize)
                    val url = if (isZip && msg.fileSize > 1_000_000) {
                        TelegramRepository.getZipStreamUrl(msg.fileId, msg.fileName, msg.fileSize, msg.chatId, msg.messageId)
                    } else {
                        TelegramRepository.getStreamUrl(msg.fileId, msg.fileName, msg.fileSize, msg.chatId, msg.messageId)
                    }
                    telegramStreamCache[key] = Pair(url, msg.fileName.ifBlank { "Telegram Media" })
                    val isAudio = msg.mimeType.startsWith("audio/")
                    val badge = when {
                        isZip -> "🗄️ ZIP Stream"
                        isAudio -> "🎵 Audio"
                        else -> "🎬 Video"
                    }
                    val thumbUrl = if (msg.thumbnailFileId != null || msg.chatId != 0L) {
                        TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId, msg.thumbnailFileId)
                    } else ""
                    mediaList.add(
                        MediaItem(
                            id = key,
                            title = if (isZip) "🗄️ ${msg.fileName}" else msg.fileName.ifBlank { "Unnamed Media" },
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

    private fun showChannelOptionsMenu() {
        val channelId = currentOpenChannelId ?: return
        val currentBannerText = categoryLabel.text.toString()
        val titleClean = if (currentBannerText.contains("Browsing: ")) {
            currentBannerText.substringAfter("Browsing: ")
        } else if (currentBannerText.contains("Topic: ")) {
            currentBannerText.substringAfter("Topic: ")
        } else {
            channelId
        }

        var dialog: AlertDialog? = null

        val optionsView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#151821"))
                cornerRadius = UITheme.dpToPx(this@MainActivity, 22).toFloat()
                setStroke(UITheme.dpToPx(this@MainActivity, 1), Color.parseColor("#262C3D"))
            }
            val pad = UITheme.dpToPx(this@MainActivity, 20)
            setPadding(pad, pad, pad, pad)
        }

        // 1. Top Pill Handle Bar
        val handlePill = android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#3A3F4D"))
                cornerRadius = UITheme.dpToPx(this@MainActivity, 2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                UITheme.dpToPx(this@MainActivity, 36),
                UITheme.dpToPx(this@MainActivity, 4)
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 16))
            }
        }
        optionsView.addView(handlePill)

        // 2. Header Row (Speech bubble icon + Title + Subtitle)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UITheme.dpToPx(this@MainActivity, 18))
        }

        val headerIconBox = TextView(this).apply {
            text = "💬"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#222736"))
                cornerRadius = UITheme.dpToPx(this@MainActivity, 12).toFloat()
            }
            val sz = UITheme.dpToPx(this@MainActivity, 44)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                setMargins(0, 0, UITheme.dpToPx(this@MainActivity, 12), 0)
            }
        }
        headerRow.addView(headerIconBox)

        val headerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val mainTitle = TextView(this).apply {
            text = "Channel Options"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        headerTextCol.addView(mainTitle)

        val subTitle = TextView(this).apply {
            text = "Channel: 🔵 $titleClean"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 12f
            setPadding(0, UITheme.dpToPx(this@MainActivity, 2), 0, 0)
        }
        headerTextCol.addView(subTitle)

        headerRow.addView(headerTextCol)
        optionsView.addView(headerRow)

        // Builder function for item cards matching screenshot
        fun createOptionCard(
            iconText: String,
            iconBgHex: String,
            accentStrokeHex: String,
            titleText: String,
            subText: String,
            onClick: () -> Unit
        ): android.view.View {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#1C212E"))
                    cornerRadius = UITheme.dpToPx(this@MainActivity, 14).toFloat()
                    setStroke(UITheme.dpToPx(this@MainActivity, 1), Color.parseColor("#293042"))
                }
                val pV = UITheme.dpToPx(this@MainActivity, 14)
                val pH = UITheme.dpToPx(this@MainActivity, 14)
                setPadding(pH, pV, pH, pV)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 12))
                }
                setOnClickListener {
                    dialog?.dismiss()
                    onClick()
                }
            }

            // Left Vertical Colored Line
            val accentLine = android.view.View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor(accentStrokeHex))
                    cornerRadius = UITheme.dpToPx(this@MainActivity, 2).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    UITheme.dpToPx(this@MainActivity, 4),
                    UITheme.dpToPx(this@MainActivity, 36)
                ).apply {
                    setMargins(0, 0, UITheme.dpToPx(this@MainActivity, 12), 0)
                }
            }
            container.addView(accentLine)

            // Left Icon Square
            val iconBox = TextView(this).apply {
                text = iconText
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor(iconBgHex))
                    cornerRadius = UITheme.dpToPx(this@MainActivity, 10).toFloat()
                }
                val sz = UITheme.dpToPx(this@MainActivity, 40)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    setMargins(0, 0, UITheme.dpToPx(this@MainActivity, 12), 0)
                }
            }
            container.addView(iconBox)

            // Middle Column (Title & Description)
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(this).apply {
                text = titleText
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            textCol.addView(titleTv)

            val subTv = TextView(this).apply {
                text = subText
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 12f
                setPadding(0, UITheme.dpToPx(this@MainActivity, 2), 0, 0)
            }
            textCol.addView(subTv)

            container.addView(textCol)

            // Right Chevron Arrow
            val arrowTv = TextView(this).apply {
                text = "›"
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(UITheme.dpToPx(this@MainActivity, 8), 0, 0, 0)
            }
            container.addView(arrowTv)

            return container
        }

        // Option 0: Refresh Channel (Green Refresh)
        val refreshCard = createOptionCard(
            "🔄",
            "#143324",
            "#34C759",
            "Refresh Channel",
            "Fetch newest & latest video and audio files"
        ) {
            if (currentOpenTopicId > 0) {
                loadTelegramTopicMedia("topic_0_${currentOpenTopicId}_$channelId", titleClean)
            } else {
                loadTelegramChannelMedia(channelId, titleClean)
            }
        }
        optionsView.addView(refreshCard)

        // Option 1: Go to the Beginning (Red Flag / Bookmark)
        val beginningCard = createOptionCard(
            "🔖",
            "#2F181E",
            "#FF3B30",
            "Go to the Beginning",
            "Jump to the oldest messages in this channel"
        ) {
            loadChannelMediaFromBeginning(channelId, currentOpenTopicId, titleClean)
        }
        optionsView.addView(beginningCard)

        // Option 2: Search This Channel (Blue Search)
        val searchCard = createOptionCard(
            "🔍",
            "#142538",
            "#007AFF",
            "Search This Channel",
            "Search video & audio files in this channel"
        ) {
            showChannelSearchDialog(channelId, currentOpenTopicId, titleClean)
        }
        optionsView.addView(searchCard)

        // Option 3: Pinned Messages (Purple Pin)
        val pinnedCard = createOptionCard(
            "📌",
            "#2A1A38",
            "#AF52DE",
            "Pinned Messages (Video / Audio)",
            "View pinned video & audio messages directly"
        ) {
            showPinnedMessagesDialog(channelId, currentOpenTopicId, titleClean)
        }
        optionsView.addView(pinnedCard)

        // Cancel Button (Red X)
        val cancelBtn = TextView(this).apply {
            text = "✖  Cancel"
            setTextColor(Color.parseColor("#FF3B30"))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1C212E"))
                cornerRadius = UITheme.dpToPx(this@MainActivity, 14).toFloat()
                setStroke(UITheme.dpToPx(this@MainActivity, 1), Color.parseColor("#293042"))
            }
            val pV = UITheme.dpToPx(this@MainActivity, 14)
            setPadding(0, pV, 0, pV)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, UITheme.dpToPx(this@MainActivity, 4), 0, 0)
            }
            setOnClickListener { dialog?.dismiss() }
        }
        optionsView.addView(cancelBtn)

        dialog = AlertDialog.Builder(this)
            .setView(optionsView)
            .create()

        dialog?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog?.show()
    }

    private fun loadChannelMediaFromBeginning(channelUsername: String, topicId: Int, title: String) {
        isInSearchMode = false
        isBrowsingFromBeginning = true
        currentOpenChannelId = channelUsername
        currentOpenTopicId = topicId
        lastTelegramFromMessageId = 1L
        hasMoreItems = true
        isLoadingMore = true
        if (::searchContainer.isInitialized) searchContainer.visibility = android.view.View.GONE
        if (::channelRefreshButton.isInitialized) channelRefreshButton.visibility = android.view.View.VISIBLE
        if (::channelMenuButton.isInitialized) channelMenuButton.visibility = android.view.View.VISIBLE
        mediaList.clear()
        mediaAdapter?.notifyDataSetChanged()
        loadingText.visibility = android.view.View.VISIBLE
        loadingText.text = "Loading media from beginning of $title..."
        updateCategoryBanner("⬅ Back to Channels  •  Beginning of: $title", isClickable = true) {
            loadTelegramChannelsCatalog()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val (mediaMessages, nextFromId) = try {
                TelegramRepository.fetchChannelMediaFromBeginning(channelUsername, fromMessageId = 1L, topicId = topicId, limit = 100)
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
                    loadingText.text = "No media files found at the beginning of $title."
                } else {
                    hasMoreItems = (nextFromId > 0L)
                    populateMediaListFromDisplayItems(groupedItems)
                    recyclerView.post {
                        try { recyclerView.scrollToPosition(0) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun showChannelSearchDialog(channelUsername: String, topicId: Int, title: String) {
        val searchView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UITheme.createCardShape(this@MainActivity, UITheme.CARD, 18, UITheme.STROKE_COLOR, 1)
            val pad = UITheme.dpToPx(this@MainActivity, 20)
            setPadding(pad, pad, pad, pad)
        }

        val dialogTitle = TextView(this).apply {
            text = "🔍 Search in $title"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
        }
        searchView.addView(dialogTitle)

        val searchEdit = EditText(this).apply {
            hint = "Enter keyword, movie name, episode..."
            setHintTextColor(Color.parseColor(UITheme.TEXT_SECONDARY))
            setTextColor(Color.WHITE)
            background = UITheme.createInputBackground(this@MainActivity)
            val pV = UITheme.dpToPx(this@MainActivity, 10)
            val pH = UITheme.dpToPx(this@MainActivity, 12)
            setPadding(pH, pV, pH, pV)
            textSize = 14f
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, UITheme.dpToPx(this@MainActivity, 12), 0, UITheme.dpToPx(this@MainActivity, 16))
            }
        }
        searchView.addView(searchEdit)

        var dialog: AlertDialog? = null

        val searchBtn = Button(this).apply {
            text = "Search Channel"
            background = UITheme.createBadgeDrawable(this@MainActivity, UITheme.PRIMARY, 12)
            setTextColor(Color.WHITE)
            setOnClickListener {
                val q = searchEdit.text.toString().trim()
                if (q.isNotBlank()) {
                    dialog?.dismiss()
                    performChannelSearch(channelUsername, topicId, title, q)
                }
            }
        }
        searchView.addView(searchBtn)

        dialog = AlertDialog.Builder(this)
            .setView(searchView)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun performChannelSearch(channelUsername: String, topicId: Int, title: String, query: String) {
        isInSearchMode = true
        hasMoreItems = false
        isLoadingMore = false
        mediaList.clear()
        mediaAdapter?.notifyDataSetChanged()
        loadingText.visibility = android.view.View.VISIBLE
        loadingText.text = "Searching \"$query\" in $title..."
        updateCategoryBanner("⬅ Back to Channels  •  Search: \"$query\" in $title", isClickable = true) {
            loadTelegramChannelsCatalog()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val mediaMessages = try {
                TelegramRepository.searchChannelMedia(channelUsername, query, topicId = topicId, limit = 100)
            } catch (e: Exception) {
                emptyList<TelegramVideoMessage>()
            }
            val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)

            withContext(Dispatchers.Main) {
                loadingText.visibility = android.view.View.GONE
                mediaList.clear()
                if (groupedItems.isEmpty()) {
                    loadingText.visibility = android.view.View.VISIBLE
                    loadingText.text = "No results matching \"$query\" in $title."
                } else {
                    populateMediaListFromDisplayItems(groupedItems)
                }
            }
        }
    }

    private data class PinnedMessageItemInfo(
        val title: String,
        val sub: String,
        val thumbUrl: String,
        val isAudio: Boolean,
        val accentHex: String
    )

    private fun showPinnedMessagesDialog(channelUsername: String, topicId: Int, title: String) {
        loadingText.visibility = android.view.View.VISIBLE
        loadingText.text = "Fetching pinned messages in $title..."

        CoroutineScope(Dispatchers.IO).launch {
            val mediaMessages = try {
                TelegramRepository.fetchPinnedMediaMessages(channelUsername, topicId = topicId)
            } catch (e: Exception) {
                emptyList<TelegramVideoMessage>()
            }
            val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)

            withContext(Dispatchers.Main) {
                loadingText.visibility = android.view.View.GONE

                if (groupedItems.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No pinned video or audio messages found in $title", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                if (groupedItems.size == 1) {
                    jumpToPinnedItemInList(groupedItems.first(), channelUsername, topicId, title)
                    return@withContext
                }

                var pDialog: AlertDialog? = null

                val pickerContainer = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#151821"))
                        cornerRadius = UITheme.dpToPx(this@MainActivity, 22).toFloat()
                        setStroke(UITheme.dpToPx(this@MainActivity, 1), Color.parseColor("#262C3D"))
                    }
                    val pad = UITheme.dpToPx(this@MainActivity, 20)
                    setPadding(pad, pad, pad, pad)
                }

                // 1. Top Pill Handle Bar
                val handlePill = android.view.View(this@MainActivity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#3A3F4D"))
                        cornerRadius = UITheme.dpToPx(this@MainActivity, 2).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        UITheme.dpToPx(this@MainActivity, 36),
                        UITheme.dpToPx(this@MainActivity, 4)
                    ).apply {
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                        setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 14))
                    }
                }
                pickerContainer.addView(handlePill)

                // 2. Header Row (Pin Icon + Title + Subtitle)
                val headerRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, UITheme.dpToPx(this@MainActivity, 16))
                }

                val headerIconBox = TextView(this@MainActivity).apply {
                    text = "📌"
                    textSize = 18f
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#2E1E26"))
                        cornerRadius = UITheme.dpToPx(this@MainActivity, 12).toFloat()
                    }
                    val sz = UITheme.dpToPx(this@MainActivity, 44)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        setMargins(0, 0, UITheme.dpToPx(this@MainActivity, 12), 0)
                    }
                }
                headerRow.addView(headerIconBox)

                val headerTextCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val dialogTitle = TextView(this@MainActivity).apply {
                    text = "Pinned Messages in $title"
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                headerTextCol.addView(dialogTitle)

                val subText = TextView(this@MainActivity).apply {
                    text = "Tap a pinned item to jump directly to it in the channel list"
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 12f
                    setPadding(0, UITheme.dpToPx(this@MainActivity, 2), 0, 0)
                }
                headerTextCol.addView(subText)

                headerRow.addView(headerTextCol)
                pickerContainer.addView(headerRow)

                val scrollView = ScrollView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        UITheme.dpToPx(this@MainActivity, 340)
                    )
                }
                val listLayout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }

                groupedItems.forEach { dItem ->
                    val info = when (dItem) {
                        is DisplayItem.Group -> {
                            val group = dItem.group
                            val firstMsg = group.parts.first()
                            val sizeStr = formatFileSize(group.totalSize)
                            val tUrl = if (firstMsg.thumbnailFileId != null || firstMsg.chatId != 0L) {
                                TelegramRepository.getThumbnailUrl(firstMsg.chatId, firstMsg.messageId, firstMsg.thumbnailFileId)
                            } else ""
                            PinnedMessageItemInfo("📦 ${group.baseName}", "📦 Split Pack (${group.parts.size} parts) • $sizeStr", tUrl, false, "#FF3B30")
                        }
                        is DisplayItem.Single -> {
                            val msg = dItem.message
                            val sizeStr = formatFileSize(msg.fileSize)
                            val audio = msg.mimeType.startsWith("audio/")
                            val badge = if (audio) "🎵 Audio" else "🎬 Video"
                            val tUrl = if (msg.thumbnailFileId != null || msg.chatId != 0L) {
                                TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId, msg.thumbnailFileId)
                            } else ""
                            val acc = if (audio) "#AF52DE" else "#007AFF"
                            PinnedMessageItemInfo(msg.fileName.ifBlank { "Unnamed Media" }, "$badge  •  $sizeStr", tUrl, audio, acc)
                        }
                    }

                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(Color.parseColor("#1C212E"))
                            cornerRadius = UITheme.dpToPx(this@MainActivity, 14).toFloat()
                            setStroke(UITheme.dpToPx(this@MainActivity, 1), Color.parseColor("#293042"))
                        }
                        val pV = UITheme.dpToPx(this@MainActivity, 12)
                        val pH = UITheme.dpToPx(this@MainActivity, 12)
                        setPadding(pH, pV, pH, pV)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 10))
                        }
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            pDialog?.dismiss()
                            jumpToPinnedItemInList(dItem, channelUsername, topicId, title)
                        }
                    }

                    // Left Accent Stroke
                    val accentLine = android.view.View(this@MainActivity).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(Color.parseColor(info.accentHex))
                            cornerRadius = UITheme.dpToPx(this@MainActivity, 2).toFloat()
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            UITheme.dpToPx(this@MainActivity, 4),
                            UITheme.dpToPx(this@MainActivity, 38)
                        ).apply {
                            setMargins(0, 0, UITheme.dpToPx(this@MainActivity, 10), 0)
                        }
                    }
                    row.addView(accentLine)

                    // Poster / Thumbnail Box
                    val posterImg = ImageView(this@MainActivity).apply {
                        val sz = UITheme.dpToPx(this@MainActivity, 48)
                        layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                            setMargins(0, 0, UITheme.dpToPx(this@MainActivity, 12), 0)
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(Color.parseColor("#252A38"))
                            cornerRadius = UITheme.dpToPx(this@MainActivity, 10).toFloat()
                        }
                        clipToOutline = true
                    }
                    if (info.thumbUrl.isNotBlank()) {
                        com.bumptech.glide.Glide.with(this@MainActivity)
                            .load(info.thumbUrl)
                            .placeholder(android.R.drawable.ic_menu_gallery)
                            .error(android.R.drawable.ic_menu_gallery)
                            .into(posterImg)
                    } else {
                        posterImg.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                    row.addView(posterImg)

                    // Text Column
                    val textLayout = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val titleV = TextView(this@MainActivity).apply {
                        text = info.title
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    textLayout.addView(titleV)

                    val subV = TextView(this@MainActivity).apply {
                        text = info.sub
                        setTextColor(Color.parseColor("#8E8E93"))
                        textSize = 12f
                        setPadding(0, UITheme.dpToPx(this@MainActivity, 2), 0, 0)
                    }
                    textLayout.addView(subV)

                    row.addView(textLayout)

                    // Right Arrow Chevron
                    val arrowTv = TextView(this@MainActivity).apply {
                        text = "›"
                        setTextColor(Color.parseColor("#8E8E93"))
                        textSize = 20f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setPadding(UITheme.dpToPx(this@MainActivity, 8), 0, 0, 0)
                    }
                    row.addView(arrowTv)

                    listLayout.addView(row)
                }

                scrollView.addView(listLayout)
                pickerContainer.addView(scrollView)

                // Close Button
                val closeBtn = TextView(this@MainActivity).apply {
                    text = "✖  Close"
                    setTextColor(Color.parseColor("#FF3B30"))
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#1C212E"))
                        cornerRadius = UITheme.dpToPx(this@MainActivity, 14).toFloat()
                        setStroke(UITheme.dpToPx(this@MainActivity, 1), Color.parseColor("#293042"))
                    }
                    val pV = UITheme.dpToPx(this@MainActivity, 14)
                    setPadding(0, pV, 0, pV)
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, UITheme.dpToPx(this@MainActivity, 10), 0, 0)
                    }
                    setOnClickListener { pDialog?.dismiss() }
                }
                pickerContainer.addView(closeBtn)

                pDialog = AlertDialog.Builder(this@MainActivity)
                    .setView(pickerContainer)
                    .create()

                pDialog?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                pDialog?.show()
            }
        }
    }

    private fun jumpToPinnedItemInList(dItem: DisplayItem, channelUsername: String, topicId: Int, title: String) {
        val targetKey = when (dItem) {
            is DisplayItem.Group -> "group_${dItem.group.parts.first().chatId}_${dItem.group.baseName}"
            is DisplayItem.Single -> "${dItem.message.chatId}_${dItem.message.messageId}"
        }

        val targetMsgId = when (dItem) {
            is DisplayItem.Group -> dItem.group.parts.first().messageId
            is DisplayItem.Single -> dItem.message.messageId
        }

        loadingText.visibility = android.view.View.VISIBLE
        loadingText.text = "Jumping to pinned message in $title..."

        CoroutineScope(Dispatchers.IO).launch {
            val (mediaMessages, nextFromId) = try {
                TelegramRepository.fetchChannelMediaAroundMessage(
                    channelUsername,
                    targetMsgId = targetMsgId,
                    topicId = topicId,
                    limit = 100,
                    includeAudio = true
                )
            } catch (e: Exception) {
                Pair(emptyList<TelegramVideoMessage>(), 0L)
            }

            if (nextFromId > 0L) {
                lastTelegramFromMessageId = nextFromId
            }

            val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)

            withContext(Dispatchers.Main) {
                loadingText.visibility = android.view.View.GONE
                if (groupedItems.isNotEmpty()) {
                    isInSearchMode = false
                    hasMoreItems = true
                    isLoadingMore = false
                    mediaList.clear()
                    populateMediaListFromDisplayItems(groupedItems)

                    val newIndex = mediaList.indexOfFirst { it.id == targetKey }
                    val targetPos = if (newIndex >= 0) newIndex else 0

                    recyclerView.post {
                        val layoutManager = recyclerView.layoutManager as? GridLayoutManager
                        layoutManager?.scrollToPositionWithOffset(targetPos, 0)
                    }
                    Toast.makeText(this@MainActivity, "Jumped to pinned message 📌", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Could not locate pinned message in list", Toast.LENGTH_SHORT).show()
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
                if (isBrowsingFromBeginning) {
                    TelegramRepository.fetchChannelMediaFromBeginning(
                        channelId,
                        fromMessageId = lastTelegramFromMessageId,
                        topicId = currentOpenTopicId,
                        limit = 100
                    )
                } else {
                    TelegramRepository.fetchChannelMedia(
                        channelId,
                        fromMessageId = lastTelegramFromMessageId,
                        topicId = currentOpenTopicId,
                        limit = 100,
                        includeAudio = true
                    )
                }
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
                                val groupChats = group.parts.map { it.chatId }
                                val groupMsgs = group.parts.map { it.messageId }
                                val formattedSize = formatFileSize(group.totalSize)
                                val url = TelegramRepository.getMergedStreamUrl(freshIds, group.baseName, partSizes, groupChats, groupMsgs)
                                telegramStreamCache[key] = Pair(url, group.baseName)
                                telegramGroupCache[key] = Pair(group.parts.map { Pair(it.chatId, it.messageId) }, partSizes)
                                telegramGroupPartsCache[key] = group.parts
                                val thumbUrl = if (firstMsg.thumbnailFileId != null || firstMsg.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(firstMsg.chatId, firstMsg.messageId, firstMsg.thumbnailFileId)
                                } else ""
                                val isZipGroup = group.parts.any { TelegramRepository.isZipArchiveFilename(it.fileName) }
                                newMediaItems.add(
                                    MediaItem(
                                        id = key,
                                        title = "📦 ${group.baseName}",
                                        posterUrl = thumbUrl,
                                        year = formattedSize,
                                        rating = "📦 Split Pack (${group.parts.size} parts)",
                                        overview = if (isZipGroup) "Split ZIP" else "Split Video",
                                        type = "telegram_media",
                                        streamUrl = url
                                    )
                                )
                            }
                            is DisplayItem.Single -> {
                                val msg = dItem.message
                                val key = "${msg.chatId}_${msg.messageId}"
                                val ext = msg.fileName.substringAfterLast('.', "").lowercase()
                                val isZip = TelegramRepository.isZipArchiveFilename(msg.fileName)
                                val formattedSize = formatFileSize(msg.fileSize)
                                val url = if (isZip && msg.fileSize > 1_000_000) {
                                    TelegramRepository.getZipStreamUrl(msg.fileId, msg.fileName, msg.fileSize, msg.chatId, msg.messageId)
                                } else {
                                    TelegramRepository.getStreamUrl(msg.fileId, msg.fileName, msg.fileSize, msg.chatId, msg.messageId)
                                }
                                telegramStreamCache[key] = Pair(url, msg.fileName.ifBlank { "Telegram Media" })
                                val isAudio = msg.mimeType.startsWith("audio/")
                                val badge = when {
                                    isZip -> "🗄️ ZIP Stream"
                                    isAudio -> "🎵 Audio"
                                    else -> "🎬 Video"
                                }
                                val thumbUrl = if (msg.thumbnailFileId != null || msg.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId, msg.thumbnailFileId)
                                } else ""
                                newMediaItems.add(
                                    MediaItem(
                                        id = key,
                                        title = if (isZip) "🗄️ ${msg.fileName}" else msg.fileName.ifBlank { "Unnamed Media" },
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
        updateCategoryBanner("Telegram Default Search: \"$query\"")
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
                                val groupChats = group.parts.map { it.chatId }
                                val groupMsgs = group.parts.map { it.messageId }
                                val formattedSize = formatFileSize(group.totalSize)
                                val url = TelegramRepository.getMergedStreamUrl(freshIds, group.baseName, partSizes, groupChats, groupMsgs)
                                telegramStreamCache[key] = Pair(url, group.baseName)
                                telegramGroupCache[key] = Pair(group.parts.map { Pair(it.chatId, it.messageId) }, partSizes)
                                telegramGroupPartsCache[key] = group.parts
                                val thumbUrl = if (firstMsg.thumbnailFileId != null || firstMsg.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(firstMsg.chatId, firstMsg.messageId, firstMsg.thumbnailFileId)
                                } else ""
                                val isZipGroup = group.parts.any { TelegramRepository.isZipArchiveFilename(it.fileName) }
                                mediaList.add(
                                    MediaItem(
                                        id = key,
                                        title = "📦 ${group.baseName}",
                                        posterUrl = thumbUrl,
                                        year = formattedSize,
                                        rating = "📦 Split Pack (${group.parts.size} parts)",
                                        overview = if (isZipGroup) "Split ZIP" else "Split Video",
                                        type = "telegram_media",
                                        streamUrl = url
                                    )
                                )
                            }
                            is DisplayItem.Single -> {
                                val msg = dItem.message
                                val key = "${msg.chatId}_${msg.messageId}"
                                val ext = msg.fileName.substringAfterLast('.', "").lowercase()
                                val isZip = TelegramRepository.isZipArchiveFilename(msg.fileName)
                                val formattedSize = formatFileSize(msg.fileSize)
                                val url = if (isZip && msg.fileSize > 1_000_000) {
                                    TelegramRepository.getZipStreamUrl(msg.fileId, msg.fileName, msg.fileSize, msg.chatId, msg.messageId)
                                } else {
                                    TelegramRepository.getStreamUrl(msg.fileId, msg.fileName, msg.fileSize, msg.chatId, msg.messageId)
                                }
                                telegramStreamCache[key] = Pair(url, msg.fileName.ifBlank { "Telegram Media" })
                                val isAudio = msg.mimeType.startsWith("audio/")
                                val badge = when {
                                    isZip -> "🗄️ ZIP Stream"
                                    isAudio -> "🎵 Audio"
                                    else -> "🎬 Video"
                                }
                                val thumbUrl = if (msg.thumbnailFileId != null || msg.chatId != 0L) {
                                    TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId, msg.thumbnailFileId)
                                } else ""
                                mediaList.add(
                                    MediaItem(
                                        id = key,
                                        title = if (isZip) "🗄️ ${msg.fileName}" else msg.fileName.ifBlank { "Unnamed Media" },
                                        posterUrl = thumbUrl,
                                        year = formattedSize,
                                        rating = badge,
                                        overview = msg.caption.ifBlank { "Telegram Search Match: ${msg.fileName}\nSize: $formattedSize" },
                                        type = "telegram_media",
                                        streamUrl = url,
                                        originalFileName = msg.fileName
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

    private fun fetchSeriesEpisodes(item: MediaItem, isDownloadMode: Boolean = false) {
        val cachedSeasons = cinemetaSeriesCache[item.id]
        val yearInt = item.year.filter { it.isDigit() }.take(4).toIntOrNull()
        if (cachedSeasons != null && cachedSeasons.isNotEmpty()) {
            showSeasonPicker(item.title, cachedSeasons, item.posterUrl, isDownloadMode = isDownloadMode, year = yearInt)
            return
        }

        Toast.makeText(this, "Loading episodes for ${item.title}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val metaType = if (item.type == "series" || item.type == "tv") "series" else item.type
                val url = URL("https://v3-cinemeta.strem.io/meta/$metaType/${item.id}.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val text = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val meta = json.optJSONObject("meta") ?: JSONObject()
                val videos = meta.optJSONArray("videos") ?: JSONArray()

                val episodes = mutableListOf<EpisodeItem>()
                for (i in 0 until videos.length()) {
                    val v = videos.getJSONObject(i)
                    val season = v.optInt("season", -1)
                    val episode = if (v.has("episode")) v.optInt("episode") else v.optInt("number", 0)
                    if (season > 0 && episode > 0) {
                        val epTitle = v.optString("name").ifBlank { v.optString("title", "Episode $episode") }
                        episodes.add(EpisodeItem(
                            season = season,
                            episode = episode,
                            title = epTitle,
                            overview = v.optString("overview", v.optString("description", "")),
                            released = v.optString("released", v.optString("firstAired", ""))
                        ))
                    }
                }

                val seasons = episodes.groupBy { it.season }.toSortedMap()
                if (seasons.isNotEmpty()) {
                    cinemetaSeriesCache[item.id] = seasons
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (seasons.isEmpty()) {
                        Toast.makeText(this@MainActivity, "No episodes found", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    showSeasonPicker(item.title, seasons, item.posterUrl, isDownloadMode = isDownloadMode, year = yearInt)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    Toast.makeText(this@MainActivity, "Failed to load episodes: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showAutoSeriesSeasons(item: MediaItem, isDownloadMode: Boolean = false) {
        val seasons = AutoLibraryManager.seriesEpisodesMap[item.id]
        if (seasons == null || seasons.isEmpty()) {
            Toast.makeText(this, "No episodes detected for ${item.title}", Toast.LENGTH_SHORT).show()
            return
        }

        val seasonList = seasons.keys.sorted()
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val headerTitle = TextView(this).apply {
            text = item.title
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.parseColor(UITheme.PRIMARY))
        }
        container.addView(headerTitle)

        val headerSub = TextView(this).apply {
            text = if (isDownloadMode) "Select Season to Download" else "${seasonList.size} Season(s) • Select Season"
            UITheme.applyMetadataStyle(this)
            setPadding(0, UITheme.dpToPx(this@MainActivity, 2), 0, UITheme.dpToPx(this@MainActivity, 14))
        }
        container.addView(headerSub)

        var dialog: AlertDialog? = null

        for (seasonNum in seasonList) {
            val episodes = seasons[seasonNum] ?: emptyList()
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = UITheme.createRippleCardShape(this@MainActivity, UITheme.CARD, 14, UITheme.STROKE_COLOR)
                val pV = UITheme.dpToPx(this@MainActivity, 14)
                val pH = UITheme.dpToPx(this@MainActivity, 16)
                setPadding(pH, pV, pH, pV)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 8))
                }
                setOnClickListener {
                    dialog?.dismiss()
                    showAutoSeriesEpisodes(item, seasonNum, episodes, isDownloadMode)
                }
            }

            val titleView = TextView(this).apply {
                text = "Season $seasonNum"
                UITheme.applyCardTitleStyle(this)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            card.addView(titleView)

            val badgeView = TextView(this).apply {
                text = "${episodes.size} Episodes"
                UITheme.applyCaptionStyle(this)
                background = UITheme.createBadgeDrawable(this@MainActivity, UITheme.SECONDARY, 8)
                setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
            }
            card.addView(badgeView)

            container.addView(card)
        }

        scrollView.addView(container)

        dialog = AlertDialog.Builder(this)
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun showAutoSeriesEpisodes(seriesItem: MediaItem, seasonNum: Int, episodes: List<EpisodeItem>, isDownloadMode: Boolean = false) {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val headerTitle = TextView(this).apply {
            text = "${seriesItem.title} — Season $seasonNum"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
        }
        container.addView(headerTitle)

        val headerSub = TextView(this).apply {
            text = "${episodes.size} Episodes Detected • 100% Playable"
            UITheme.applyMetadataStyle(this)
            setPadding(0, UITheme.dpToPx(this@MainActivity, 2), 0, UITheme.dpToPx(this@MainActivity, 14))
        }
        container.addView(headerSub)

        var dialog: AlertDialog? = null

        for (ep in episodes) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = UITheme.createRippleCardShape(this@MainActivity, UITheme.CARD, 12, UITheme.STROKE_COLOR)
                val pV = UITheme.dpToPx(this@MainActivity, 12)
                val pH = UITheme.dpToPx(this@MainActivity, 14)
                setPadding(pH, pV, pH, pV)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 8))
                }
                setOnClickListener {
                    dialog?.dismiss()
                    val epKey = "${seriesItem.id}_s${seasonNum}_e${ep.episode}"
                    val playable = AutoLibraryManager.episodePlayablesMap[epKey]
                    if (playable != null) {
                        if (isDownloadMode) {
                            handleDownloadAutoEpisode(playable, seriesItem.title)
                        } else {
                            playAutoEpisode(playable, seriesItem)
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Stream not found", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val epTextCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val epTitle = TextView(this).apply {
                text = ep.title
                UITheme.applyCardTitleStyle(this)
                textSize = 14f
            }
            epTextCol.addView(epTitle)

            val epSub = TextView(this).apply {
                text = ep.overview
                UITheme.applyMetadataStyle(this)
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            epTextCol.addView(epSub)
            card.addView(epTextCol)

            val playIcon = TextView(this).apply {
                text = if (isDownloadMode) "📥" else "▶"
                textSize = 14f
                setTextColor(Color.parseColor(UITheme.PRIMARY))
                setPadding(UITheme.dpToPx(this@MainActivity, 8), 0, 0, 0)
            }
            card.addView(playIcon)

            container.addView(card)
        }

        scrollView.addView(container)

        dialog = AlertDialog.Builder(this)
            .setView(scrollView)
            .setNegativeButton("Back", null)
            .create()
        dialog.show()
    }

    private fun playAutoEpisode(ep: AutoEpisode, seriesItem: MediaItem) {
        Toast.makeText(this, "Loading ${ep.title}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.Main) {
            val freshUrl = withContext(Dispatchers.IO) {
                TelegramRepository.getFreshMediaUrl(ep.chatId, ep.messageId)
            }
            val playTitle = "${seriesItem.title} - S${ep.season}E${ep.episode}"
            val fileName = ep.fileName.ifBlank { playTitle }
            if (freshUrl != null && freshUrl.isNotBlank()) {
                checkResumeAndSelectPlayer(freshUrl, playTitle, seriesItem.posterUrl, "${ep.chatId}_${ep.messageId}", fileName)
            } else {
                val backupUrl = TelegramStreamingProxy.refreshUrl(ep.streamUrl)
                if (backupUrl.isNotBlank()) {
                    checkResumeAndSelectPlayer(backupUrl, playTitle, seriesItem.posterUrl, "${ep.chatId}_${ep.messageId}", fileName)
                } else {
                    Toast.makeText(this@MainActivity, "Media stream unavailable", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleDownloadAutoEpisode(ep: AutoEpisode, seriesTitle: String) {
        val title = "$seriesTitle - S${ep.season}E${ep.episode}"
        DownloadManager.startDownload(
            context = this,
            title = title,
            fileName = ep.fileName,
            fileId = ep.fileId,
            chatId = ep.chatId,
            messageId = ep.messageId,
            posterUrl = "",
            totalBytes = ep.fileSize
        )
        Toast.makeText(this, "Download started: $title", Toast.LENGTH_SHORT).show()
    }

    private fun showSeasonPicker(seriesTitle: String, seasons: Map<Int, List<EpisodeItem>>, posterUrl: String = "", isDownloadMode: Boolean = false, year: Int? = null) {
        val seasonList = seasons.keys.toList()

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val headerTitle = TextView(this).apply {
            text = seriesTitle
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.parseColor(UITheme.PRIMARY))
        }
        container.addView(headerTitle)

        val headerSub = TextView(this).apply {
            text = if (isDownloadMode) "Select Season to Download" else "Select Season"
            UITheme.applyMetadataStyle(this)
            setPadding(0, UITheme.dpToPx(this@MainActivity, 2), 0, UITheme.dpToPx(this@MainActivity, 14))
        }
        container.addView(headerSub)

        var dialog: AlertDialog? = null

        for (seasonNum in seasonList) {
            val epCount = seasons[seasonNum]?.size ?: 0
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = UITheme.createRippleCardShape(this@MainActivity, UITheme.CARD, 14, UITheme.STROKE_COLOR)
                val pV = UITheme.dpToPx(this@MainActivity, 14)
                val pH = UITheme.dpToPx(this@MainActivity, 16)
                setPadding(pH, pV, pH, pV)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 8))
                }
                setOnClickListener {
                    dialog?.dismiss()
                    val episodes = seasons[seasonNum] ?: return@setOnClickListener
                    showEpisodePicker(seriesTitle, seasonNum, episodes, posterUrl, isDownloadMode = isDownloadMode, year = year)
                }
            }

            val titleView = TextView(this).apply {
                text = "Season $seasonNum"
                UITheme.applyCardTitleStyle(this)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            card.addView(titleView)

            val badgeView = TextView(this).apply {
                text = "$epCount Episodes"
                UITheme.applyCaptionStyle(this)
                background = UITheme.createBadgeDrawable(this@MainActivity, UITheme.SECONDARY, 8)
                setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
            }
            card.addView(badgeView)

            container.addView(card)
        }

        scrollView.addView(container)

        dialog = AlertDialog.Builder(this)
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun showEpisodePicker(seriesTitle: String, season: Int, episodes: List<EpisodeItem>, posterUrl: String = "", isDownloadMode: Boolean = false, year: Int? = null) {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val headerTitle = TextView(this).apply {
            text = "$seriesTitle — Season $season"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
        }
        container.addView(headerTitle)

        val headerSub = TextView(this).apply {
            text = if (isDownloadMode) "${episodes.size} Episodes — Select Episode to Download" else "${episodes.size} Episodes Available"
            UITheme.applyMetadataStyle(this)
            setPadding(0, UITheme.dpToPx(this@MainActivity, 2), 0, UITheme.dpToPx(this@MainActivity, 14))
        }
        container.addView(headerSub)

        var dialog: AlertDialog? = null

        for (ep in episodes) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = UITheme.createRippleCardShape(this@MainActivity, UITheme.CARD, 14, UITheme.STROKE_COLOR)
                val pV = UITheme.dpToPx(this@MainActivity, 12)
                val pH = UITheme.dpToPx(this@MainActivity, 14)
                setPadding(pH, pV, pH, pV)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 8))
                }
                setOnClickListener {
                    dialog?.dismiss()
                    showStreamOptions(seriesTitle, season, ep.episode, posterUrl, isDownloadMode = isDownloadMode, year = year)
                }
            }

            val epBadge = TextView(this).apply {
                text = "E${String.format("%02d", ep.episode)}"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(UITheme.PRIMARY))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, UITheme.dpToPx(this@MainActivity, 14), 0)
                }
                layoutParams = lp
            }
            card.addView(epBadge)

            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleView = TextView(this).apply {
                text = ep.title
                UITheme.applyCardTitleStyle(this)
                textSize = 14f
            }
            textContainer.addView(titleView)

            card.addView(textContainer)
            container.addView(card)
        }

        scrollView.addView(container)

        dialog = AlertDialog.Builder(this)
            .setView(scrollView)
            .setNegativeButton("Back", null)
            .create()
        dialog.show()
    }

    // ── Stream Selection ────────────────────────────────────────

    private fun showStreamOptions(title: String, season: Int? = null, episode: Int? = null, posterUrl: String = "", isDownloadMode: Boolean = false, year: Int? = null) {
        val displayTitle = if (season != null && episode != null) {
            "$title S${String.format("%02d", season)}E${String.format("%02d", episode)}"
        } else {
            title
        }

        val loadingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            background = UITheme.createCardShape(this@MainActivity, UITheme.CARD, 18, UITheme.STROKE_COLOR, 1)
            val pad = UITheme.dpToPx(this@MainActivity, 20)
            setPadding(pad, pad, pad, pad)
        }

        val progressBar = android.widget.ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(UITheme.PRIMARY))
            val lp = LinearLayout.LayoutParams(UITheme.dpToPx(this@MainActivity, 40), UITheme.dpToPx(this@MainActivity, 40)).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 12))
            }
            layoutParams = lp
        }
        loadingView.addView(progressBar)

        val loadTitle = TextView(this).apply {
            text = if (isDownloadMode) "Searching Downloadable Streams" else "Searching Telegram Streams"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }
        loadingView.addView(loadTitle)

        val loadSub = TextView(this).apply {
            text = "Querying connected Telegram channels & chats for:\n\"$displayTitle\""
            UITheme.applyMetadataStyle(this)
            gravity = android.view.Gravity.CENTER
            setPadding(0, UITheme.dpToPx(this@MainActivity, 6), 0, 0)
        }
        loadingView.addView(loadSub)

        var searchJob: kotlinx.coroutines.Job? = null

        val progressDialog = AlertDialog.Builder(this)
            .setView(loadingView)
            .setCancelable(true)
            .setNegativeButton("Cancel") { d, _ ->
                searchJob?.cancel()
                d.dismiss()
            }
            .setOnCancelListener {
                searchJob?.cancel()
            }
            .show()

        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            val streams = try {
                TdlibManager.resolveStreams(title, season, episode, year)
            } catch (e: Exception) {
                emptyList()
            }

            if (!isActive) return@launch

            withContext(Dispatchers.Main) {
                try { progressDialog.dismiss() } catch (_: Exception) {}

                if (isFinishing || isDestroyed || !isActive) return@withContext

                if (streams.isEmpty()) {
                    try {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("No Streams Found")
                            .setMessage("No matching streams found for '$displayTitle'.\nCheck your Telegram account & monitored channels.")
                            .setPositiveButton("OK", null)
                            .show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return@withContext
                }

                fun dp(v: Int) = UITheme.dpToPx(this@MainActivity, v)

                // Main container panel
                val container = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = UITheme.createCardShape(this@MainActivity, "#050608", 22, "#1F2633", 1)
                    setPadding(dp(18), dp(18), dp(18), dp(12))
                }

                // Header Row
                val titleRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(14))
                }

                val headerCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val subHeaderLabel = TextView(this@MainActivity).apply {
                    text = if (isDownloadMode) "Select Stream to Download for" else "Streams Found for"
                    UITheme.applyMetadataStyle(this)
                    textSize = 12f
                    setTextColor(Color.parseColor("#9CA3AF"))
                }
                headerCol.addView(subHeaderLabel)

                val mainTitleText = TextView(this@MainActivity).apply {
                    text = displayTitle
                    UITheme.applySectionTitleStyle(this)
                    setTextColor(Color.WHITE)
                    textSize = 18f
                }
                headerCol.addView(mainTitleText)

                val streamCountBadge = TextView(this@MainActivity).apply {
                    text = "🎬 ${streams.size} streams available"
                    UITheme.applyMetadataStyle(this)
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#10B981"))
                    setPadding(0, dp(2), 0, 0)
                }
                headerCol.addView(streamCountBadge)

                val closeBtn = TextView(this@MainActivity).apply {
                    text = "✕"
                    textSize = 16f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    gravity = android.view.Gravity.CENTER
                    background = UITheme.createCardShape(this@MainActivity, "#161B22", 10, "#21262D", 1)
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    isClickable = true
                    isFocusable = true
                }

                titleRow.addView(headerCol)
                titleRow.addView(closeBtn)
                container.addView(titleRow)

                // Scrollable Stream Cards Area
                val scrollView = ScrollView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                    isVerticalScrollBarEnabled = true
                }

                val cardList = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }

                val streamDialog = AlertDialog.Builder(this@MainActivity)
                    .setView(container)
                    .create()

                streamDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                closeBtn.setOnClickListener { streamDialog.dismiss() }

                for (stream in streams) {
                    val card = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = UITheme.createRippleCardShape(this@MainActivity, "#11151D", 14, "#21262D")
                        val pad = dp(14)
                        setPadding(pad, pad, pad, pad)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, dp(10)) }
                        layoutParams = lp
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            streamDialog.dismiss()
                            if (isDownloadMode) {
                                downloadStreamSource(stream, displayTitle, posterUrl)
                            } else {
                                val parts = telegramGroupPartsCache[stream.id]
                                val isZipFile = stream.isZip || TelegramRepository.isZipArchiveFilename(stream.fileName)
                                if ((stream.isSplit || stream.id.startsWith("group_")) && !isZipFile) {
                                    val cleanName = stream.fileName.removePrefix("📦 ").removePrefix("🔗 ").trim()
                                    val mediaItem = MediaItem(
                                        id = stream.id,
                                        title = cleanName,
                                        posterUrl = posterUrl,
                                        year = stream.size,
                                        rating = stream.quality,
                                        overview = "Multi-part video pack: $cleanName",
                                        type = "telegram_media",
                                        streamUrl = stream.url
                                    )
                                    if (parts != null && parts.isNotEmpty()) {
                                        showGroupPartsSelectionDialog(mediaItem, parts, cleanName)
                                    } else if (stream.chatId != 0L) {
                                        Toast.makeText(this@MainActivity, "Loading group parts...", Toast.LENGTH_SHORT).show()
                                        CoroutineScope(Dispatchers.Main).launch {
                                            val mediaMessages = withContext(Dispatchers.IO) {
                                                TelegramRepository.fetchChannelMedia(stream.chatId.toString(), limit = 200).first
                                            }
                                            val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)
                                            val matchGroup = groupedItems.filterIsInstance<DisplayItem.Group>()
                                                .find { it.group.baseName.equals(cleanName, ignoreCase = true) }
                                            if (matchGroup != null && matchGroup.group.parts.isNotEmpty()) {
                                                telegramGroupPartsCache[stream.id] = matchGroup.group.parts
                                                showGroupPartsSelectionDialog(mediaItem, matchGroup.group.parts, cleanName)
                                            } else {
                                                checkResumeAndSelectPlayer(stream.url, displayTitle, posterUrl, stream.id, stream.fileName)
                                            }
                                        }
                                    } else {
                                        checkResumeAndSelectPlayer(stream.url, displayTitle, posterUrl, stream.id, stream.fileName)
                                    }
                                } else {
                                    checkResumeAndSelectPlayer(stream.url, displayTitle, posterUrl, stream.id, stream.fileName)
                                }
                            }
                        }
                    }

                    val filenameText = TextView(this@MainActivity).apply {
                        text = stream.fileName
                        UITheme.applyCardTitleStyle(this)
                        textSize = 13f
                        setTextColor(Color.parseColor("#F5F5F5"))
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    card.addView(filenameText)

                    // Horizontal Badges Layout Row
                    val badgesRow = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, dp(8), 0, 0)
                    }

                    val isZipFile = stream.isZip || stream.id.startsWith("zip_") || TelegramRepository.isZipArchiveFilename(stream.fileName)

                    if (stream.quality.isNotBlank() && !stream.quality.equals("ZIP", ignoreCase = true) && !stream.quality.contains("🗄️")) {
                        val qualityBadge = TextView(this@MainActivity).apply {
                            text = if (stream.quality.startsWith("🎬 ") || stream.quality.startsWith("📦 ") || stream.quality.startsWith("🗄️ ")) stream.quality else "🎬 ${stream.quality}"
                            UITheme.applyCaptionStyle(this)
                            textSize = 10f
                            setSingleLine(true)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            background = UITheme.createCardShape(this@MainActivity, "#064E3B", 8, "#10B981", 1)
                            setTextColor(Color.parseColor("#10B981"))
                            setPadding(dp(8), dp(3), dp(8), dp(3))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, 0, dp(6), 0)
                            }
                        }
                        badgesRow.addView(qualityBadge)
                    }

                    if (isZipFile) {
                        val zipBadge = TextView(this@MainActivity).apply {
                            text = "🤐 ZIP"
                            UITheme.applyCaptionStyle(this)
                            textSize = 10f
                            setSingleLine(true)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            background = UITheme.createCardShape(this@MainActivity, "#312E81", 8, "#6366F1", 1)
                            setTextColor(Color.parseColor("#818CF8"))
                            setPadding(dp(8), dp(3), dp(8), dp(3))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, 0, dp(6), 0)
                            }
                        }
                        badgesRow.addView(zipBadge)
                    } else if (stream.isSplit || stream.id.startsWith("group_")) {
                        val splitBadge = TextView(this@MainActivity).apply {
                            text = "📦 SPLIT PACK"
                            UITheme.applyCaptionStyle(this)
                            textSize = 10f
                            setSingleLine(true)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            background = UITheme.createCardShape(this@MainActivity, "#451A03", 8, "#F59E0B", 1)
                            setTextColor(Color.parseColor("#F59E0B"))
                            setPadding(dp(8), dp(3), dp(8), dp(3))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, 0, dp(6), 0)
                            }
                        }
                        badgesRow.addView(splitBadge)
                    }

                    if (stream.size.isNotBlank()) {
                        val sizeBadge = TextView(this@MainActivity).apply {
                            text = "💾 ${stream.size}"
                            UITheme.applyCaptionStyle(this)
                            textSize = 10f
                            setSingleLine(true)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            background = UITheme.createCardShape(this@MainActivity, "#1E293B", 8, "#3B82F6", 1)
                            setTextColor(Color.parseColor("#3B82F6"))
                            setPadding(dp(8), dp(3), dp(8), dp(3))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        }
                        badgesRow.addView(sizeBadge)
                    }

                    if (stream.matchScore > 0) {
                        val scoreBadge = TextView(this@MainActivity).apply {
                            text = "⭐ ${stream.matchScore}%"
                            UITheme.applyCaptionStyle(this)
                            textSize = 10f
                            setSingleLine(true)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            background = UITheme.createCardShape(this@MainActivity, "#1E1B4B", 8, "#6366F1", 1)
                            setTextColor(Color.parseColor("#A5B4FC"))
                            setPadding(dp(8), dp(3), dp(8), dp(3))
                            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            if (stream.size.isNotBlank()) {
                                lp.setMargins(dp(6), 0, 0, 0)
                            }
                            layoutParams = lp
                        }
                        badgesRow.addView(scoreBadge)
                    }

                    card.addView(badgesRow)

                    cardList.addView(card)
                }

                scrollView.addView(cardList)
                container.addView(scrollView)

                // Sticky Footer Section
                val footerDivider = android.view.View(this@MainActivity).apply {
                    setBackgroundColor(Color.parseColor("#21262D"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        setMargins(0, dp(10), 0, dp(10))
                    }
                }
                container.addView(footerDivider)

                val footerRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val footerInfoCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val footerTitle = TextView(this@MainActivity).apply {
                    text = "🎬 ${streams.size} Streams Found"
                    UITheme.applyCardTitleStyle(this)
                    textSize = 13f
                    setTextColor(Color.WHITE)
                }
                footerInfoCol.addView(footerTitle)

                val footerSub = TextView(this@MainActivity).apply {
                    text = "Select a stream to start watching"
                    UITheme.applyMetadataStyle(this)
                    textSize = 11f
                    setTextColor(Color.parseColor("#9CA3AF"))
                }
                footerInfoCol.addView(footerSub)

                val footerCloseBtn = TextView(this@MainActivity).apply {
                    text = "CLOSE"
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = UITheme.createCardShape(this@MainActivity, "#E50914", 10, "#E50914", 1)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    isClickable = true
                    setOnClickListener { streamDialog.dismiss() }
                }
                footerRow.addView(footerInfoCol)
                footerRow.addView(footerCloseBtn)
                container.addView(footerRow)

                if (!isFinishing && !isDestroyed) {
                    try {
                        streamDialog.show()
                        val metrics = resources.displayMetrics
                        val w = (metrics.widthPixels * 0.92).toInt()
                        val h = (metrics.heightPixels * 0.82).toInt()
                        streamDialog.window?.setLayout(w, h)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun saveLinkToWatchHistory(streamUrl: String, title: String, posterUrl: String = "", mediaId: String = "", originalFileName: String = "") {
        if (streamUrl.isBlank() && mediaId.isBlank()) return
        val effectiveId = if (mediaId.isNotBlank()) mediaId else "link_" + streamUrl.hashCode()
        var effectivePoster = ""

        // Prioritize original video poster thumbnail from Telegram
        if (mediaId.contains("_")) {
            val cleanId = mediaId.removePrefix("group_")
            val parts = cleanId.split("_")
            if (parts.size >= 2) {
                val cId = parts[0].toLongOrNull()
                val mId = parts[1].toLongOrNull()
                if (cId != null && mId != null && cId != 0L) {
                    val tgThumbUrl = TelegramRepository.getThumbnailUrl(cId, mId)
                    if (tgThumbUrl.isNotBlank()) {
                        effectivePoster = tgThumbUrl
                    }
                }
            }
        }

        // Fallback to passed Cinemeta posterUrl if Telegram thumbnail is not available
        if (effectivePoster.isBlank()) {
            effectivePoster = posterUrl
        }

        if (effectivePoster.isBlank()) {
            val matchingMedia = mediaList.firstOrNull { it.id == mediaId || it.title.equals(title, ignoreCase = true) }
            if (matchingMedia != null && matchingMedia.posterUrl.isNotBlank()) {
                effectivePoster = matchingMedia.posterUrl
            }
        }
        val item = MediaItem(
            id = effectiveId,
            title = title,
            posterUrl = effectivePoster,
            year = "Watched",
            rating = "▶",
            overview = "Playing stream: $title",
            type = "telegram_media",
            streamUrl = streamUrl,
            originalFileName = originalFileName
        )
        saveToHistory(item)
    }

    private fun checkResumeAndSelectPlayer(streamUrl: String, title: String, posterUrl: String = "", mediaId: String = "", originalFileName: String = "") {
        saveLinkToWatchHistory(streamUrl, title, posterUrl, mediaId, originalFileName)
        val prefsLink = getSharedPreferences("teleflix_resume_points", android.content.Context.MODE_PRIVATE)
        val prefsTitle = getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE)
        var savedPositionMs = 0L
        var savedDurationMs = 0L
        if (mediaId.isNotBlank()) {
            savedPositionMs = prefsLink.getLong("id_$mediaId", 0L)
            savedDurationMs = prefsLink.getLong("dur_id_$mediaId", 0L).takeIf { it > 0 } ?: prefsLink.getLong("dur_$mediaId", 0L)
            if (savedPositionMs <= 3_000L) {
                savedPositionMs = prefsLink.getLong(mediaId, 0L)
            }
        }
        if (savedPositionMs <= 3_000L) {
            savedPositionMs = prefsLink.getLong(streamUrl, 0L)
            if (savedDurationMs <= 0L) {
                savedDurationMs = prefsLink.getLong("dur_$streamUrl", 0L)
            }
        }
        if (savedPositionMs <= 3_000L) {
            savedPositionMs = prefsTitle.getLong("resume_$title", 0L)
            if (savedDurationMs <= 0L) {
                savedDurationMs = prefsTitle.getLong("dur_$title", 0L)
            }
        }

        // If saved position is near the end (completed), clear resume point and start from beginning (0L)
        if (savedPositionMs > 3_000L && savedDurationMs > 0L) {
            if (savedPositionMs >= (savedDurationMs * 0.90).toLong() || (savedPositionMs >= savedDurationMs - 15_000L && savedDurationMs > 20_000L) || (savedPositionMs >= savedDurationMs - 3_000L)) {
                savedPositionMs = 0L
                val editLink = prefsLink.edit()
                val editTitle = prefsTitle.edit()
                if (mediaId.isNotBlank()) {
                    editLink.remove("id_$mediaId").remove(mediaId).remove("dur_id_$mediaId").remove("dur_$mediaId")
                }
                editLink.remove(streamUrl).remove("dur_$streamUrl")
                editTitle.remove("resume_$title").remove("dur_$title")
                editLink.apply()
                editTitle.apply()
            }
        }

        if (savedPositionMs > 3_000L) {
            val alwaysResume = getSharedPreferences("teleflix_preferences", android.content.Context.MODE_PRIVATE)
                .getBoolean("always_resume", false)
            if (alwaysResume) {
                handlePlayerLaunch(streamUrl, title, savedPositionMs, mediaId)
            } else {
                val formattedTime = formatMillisToTime(savedPositionMs)
                AlertDialog.Builder(this)
                    .setTitle("Resume Playback")
                    .setMessage("You previously watched '$title' up to $formattedTime.\n\nDo you want to resume where you left off or start from the beginning?")
                    .setPositiveButton("▶ Resume ($formattedTime)") { _, _ ->
                        handlePlayerLaunch(streamUrl, title, savedPositionMs, mediaId)
                    }
                    .setNegativeButton("🔄 Start Over") { _, _ ->
                        val editLink = prefsLink.edit()
                        val editTitle = prefsTitle.edit()
                        if (mediaId.isNotBlank()) {
                            editLink.remove("id_$mediaId").remove(mediaId).remove("dur_id_$mediaId").remove("dur_$mediaId")
                        }
                        editLink.remove(streamUrl).remove("dur_$streamUrl")
                        editTitle.remove("resume_$title").remove("dur_$title")
                        editLink.apply()
                        editTitle.apply()
                        handlePlayerLaunch(streamUrl, title, 0L, mediaId)
                    }
                    .setNeutralButton("Cancel", null)
                    .show()
            }
        } else {
            handlePlayerLaunch(streamUrl, title, 0L, mediaId)
        }
    }

    private fun handlePlayerLaunch(streamUrl: String, title: String, resumeMs: Long, mediaId: String = "") {
        val isMerged = streamUrl.contains("/merged/")
        val isZip = isMerged || streamUrl.contains("/zip/") || title.contains(".zip", ignoreCase = true)

        fun proceedToLaunch() {
            val prefPlayer = getSharedPreferences("teleflix_preferences", android.content.Context.MODE_PRIVATE)
                .getString("default_player", "ask") ?: "ask"
            if (prefPlayer == "ask") {
                showPlayerActionDialog(streamUrl, title, resumeMs, mediaId)
            } else {
                openStreamInPlayer(prefPlayer, streamUrl, title, resumeMs, mediaId)
            }
        }

        if (isZip) {
            val fileIds = if (isMerged) {
                streamUrl.substringAfter("/merged/").substringBefore("?").substringBefore("/").split(",").mapNotNull { it.toIntOrNull() }
            } else {
                listOfNotNull(streamUrl.substringAfter("/zip/").substringAfter("/file/").substringBefore("/").substringBefore("?").toIntOrNull())
            }
            val primaryFileId = fileIds.firstOrNull()
            if (primaryFileId != null) {
                val cachedMethod = TelegramStreamingProxy.getCachedZipCompression(primaryFileId)
                if (cachedMethod != null) {
                    if (cachedMethod != 0) {
                        showCompressedZipWarningDialog(cachedMethod, title, streamUrl, resumeMs, mediaId)
                    } else {
                        proceedToLaunch()
                    }
                    return
                }

                val sizes = if (isMerged) {
                    streamUrl.substringAfter("?", "").split("&").find { it.startsWith("sizes=") }
                        ?.substringAfter("=")?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
                } else emptyList()

                lifecycleScope.launch {
                    val probedMethod = TelegramStreamingProxy.probeZipCompression(fileIds, sizes)
                    if (probedMethod != null && probedMethod != 0) {
                        showCompressedZipWarningDialog(probedMethod, title, streamUrl, resumeMs, mediaId)
                    } else {
                        proceedToLaunch()
                    }
                }
                return
            }
        }

        proceedToLaunch()
    }

    private fun showCompressedZipWarningDialog(
        compressionMethod: Int,
        title: String,
        streamUrl: String,
        resumeMs: Long,
        mediaId: String
    ) {
        val (methodName, descText) = when (compressionMethod) {
            8 -> Pair("DEFLATE", "This split archive was uploaded using DEFLATE compression.")
            14 -> Pair("LZMA", "This split archive was uploaded using LZMA compression.")
            12 -> Pair("BZIP2", "This split archive was uploaded using BZIP2 compression.")
            999 -> Pair("Non-Streamable Archive", "This multi-part file is a compressed archive and does not contain raw video stream headers.")
            else -> Pair("Method $compressionMethod", "This file is compressed using method $compressionMethod.")
        }
        TeleflixLogger.log("MainActivity", "Showing compressed archive dialog for '$title' ($methodName)")
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("📦 Compressed Archive ($methodName)")
                .setMessage("$descText\n\nVideo players cannot stream compressed archives over the network and will misidentify them as broken audio files.\n\nTo watch this movie, please download all parts to your device and extract them with 7-Zip or ZArchiver.")
                .setPositiveButton("📥 Download Parts") { _, _ ->
                    val isMerged = streamUrl.contains("/merged/")
                    if (isMerged) {
                        val segment = streamUrl.substringAfter("/merged/").substringBefore("?")
                        val ids = segment.substringBefore("/").split(",").mapNotNull { it.toIntOrNull() }
                        ids.forEach { fId ->
                            DownloadManager.startDownload(this@MainActivity, "$title (Part)", "$title.part", fId, 0L, 0L, "", 0L)
                        }
                        Toast.makeText(this, "Started downloading ${ids.size} parts", Toast.LENGTH_SHORT).show()
                    } else {
                        val fileId = streamUrl.substringAfter("/file/").substringBefore("/").substringBefore("?").toIntOrNull()
                        if (fileId != null) {
                            DownloadManager.startDownload(this@MainActivity, title, title, fileId, 0L, 0L, "", 0L)
                            Toast.makeText(this, "Started download", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNeutralButton("Try Playing Anyway") { _, _ ->
                    val prefPlayer = getSharedPreferences("teleflix_preferences", android.content.Context.MODE_PRIVATE)
                        .getString("default_player", "ask") ?: "ask"
                    if (prefPlayer == "ask") {
                        showPlayerActionDialog(streamUrl, title, resumeMs, mediaId)
                    } else {
                        openStreamInPlayer(prefPlayer, streamUrl, title, resumeMs, mediaId)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
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
            "🎬 Inbuilt MPV Player (In-App)",
            "🔵 External MPV Player (is.xyz.mpv)",
            "⚡ External ExoPlayer (Just Player / Next Player)",
            "🔴 External MPVEX Player",
            "🧡 External VLC Player",
            "📱 Choose From All Installed Players..."
        )
        val keys = arrayOf("internal", "mpv", "exo", "mpvex", "vlc", "chooser")

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val headerTitle = TextView(this).apply {
            text = "Select Video Player"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
        }
        container.addView(headerTitle)

        val headerSub = TextView(this).apply {
            text = "Playing: $title"
            UITheme.applyMetadataStyle(this)
            setPadding(0, UITheme.dpToPx(this@MainActivity, 4), 0, UITheme.dpToPx(this@MainActivity, 14))
        }
        container.addView(headerSub)

        var dialog: AlertDialog? = null

        for (i in options.indices) {
            val playerKey = keys[i]
            val card = TextView(this).apply {
                text = options[i]
                UITheme.applyCardTitleStyle(this)
                background = UITheme.createRippleCardShape(this@MainActivity, UITheme.CARD, 14, UITheme.STROKE_COLOR)
                val pV = UITheme.dpToPx(this@MainActivity, 12)
                val pH = UITheme.dpToPx(this@MainActivity, 16)
                setPadding(pH, pV, pH, pV)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 8))
                }
                setOnClickListener {
                    dialog?.dismiss()
                    openStreamInPlayer(playerKey, streamUrl, title, resumeMs, mediaId)
                }
            }
            container.addView(card)
        }

        scrollView.addView(container)

        dialog = AlertDialog.Builder(this)
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
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

        val cleanDisplayTitle = title.replace(Regex("""^[📦🗄️⚡🎬🎥📽️\s]+"""), "").trim()
        val isPlaylist = streamUrl.contains("/playlist/") || streamUrl.lowercase().contains(".m3u8") || streamUrl.lowercase().contains(".m3u")
        val isAudio = !isPlaylist && Regex("""(?i)\.(mp3|flac|aac|ogg|opus|wav|wma|m4a|aiff)$""").containsMatchIn(cleanDisplayTitle)
        val isMkv = !isPlaylist && !isAudio && (cleanDisplayTitle.endsWith(".mkv", ignoreCase = true) || streamUrl.lowercase().contains(".mkv") || streamUrl.contains("/merged/") || streamUrl.contains("/zip/"))
        val mimeType = when {
            isPlaylist -> "application/vnd.apple.mpegurl"
            isAudio -> "audio/*"
            isMkv -> "video/x-matroska"
            else -> "video/mp4"
        }

        val videoSafeTitle = if (!isPlaylist && !isAudio && !cleanDisplayTitle.endsWith(".mkv", ignoreCase = true) && !cleanDisplayTitle.endsWith(".mp4", ignoreCase = true)) {
            val base = cleanDisplayTitle.removeSuffix(".zip").removeSuffix(".7z").removeSuffix(".rar").trim()
            "$base.mkv"
        } else cleanDisplayTitle

        if (playerType == "internal") {
            PlayerActivity.launch(this, streamUrl, title, resumeMs, mediaId)
            return
        }

        val baseIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(streamUrl), mimeType)
            putExtra("title", videoSafeTitle)
            putExtra("filename", videoSafeTitle)
            putExtra("org.videolan.vlc.player.ForceVideo", true)
            putExtra("ForceVideo", true)
            putExtra("return_result", true)
            if (resumeMs > 0) {
                putExtra("position", resumeMs.toInt())
                putExtra("extra_position", resumeMs)
                putExtra("resume_position", resumeMs)
                putExtra("position_ms", resumeMs)
                putExtra("start_position", resumeMs)
                putExtra("from_start", false)
            } else {
                putExtra("position", 0)
                putExtra("extra_position", 0L)
                putExtra("resume_position", 0L)
                putExtra("position_ms", 0L)
                putExtra("start_position", 0L)
                putExtra("from_start", true)
            }
        }

        when (playerType) {
            "exo" -> {
                val packagesToTry = listOf(
                    "dev.anilbeesetti.nextplayer",
                    "dev.anilbeesetti.nextplayer.play",
                    "dev.anilbeesetti.nextplayer.debug",
                    "dev.anilbeesetti.nextplayer.preview",
                    "com.brouken.player",
                    "com.nextplayer.app",
                    "com.google.android.exoplayer",
                    "com.mxtech.videoplayer.ad",
                    "com.mxtech.videoplayer.pro"
                )

                var launched = false
                for (pkg in packagesToTry) {
                    try {
                        val intent = Intent(baseIntent).apply {
                            setPackage(pkg)
                            if (isAudio && (pkg.contains("nextplayer") || pkg.contains("brouken"))) {
                                setDataAndType(Uri.parse(streamUrl), "video/*")
                            }
                        }
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
                            val intent = Intent(baseIntent).apply {
                                setPackage(exoMatch.activityInfo.packageName)
                                if (isAudio && (exoMatch.activityInfo.packageName.contains("nextplayer") || exoMatch.activityInfo.packageName.contains("brouken"))) {
                                    setDataAndType(Uri.parse(streamUrl), "video/*")
                                }
                            }
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
                // Internal Preferred Player: MPV Android (is.xyz.mpv)
                val packagesToTry = listOf(
                    "is.xyz.mpv",
                    "is.xyz.mpv.debug",
                    "id.nzxm.mpv"
                )
                var launched = false
                val explicitClasses = listOf(
                    "is.xyz.mpv" to "is.xyz.mpv.MPVActivity",
                    "is.xyz.mpv.debug" to "is.xyz.mpv.MPVActivity"
                )
                for ((pkg, cls) in explicitClasses) {
                    try {
                        val intent = Intent(baseIntent).apply {
                            setClassName(pkg, cls)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        playerLauncher.launch(intent)
                        launched = true
                        break
                    } catch (_: Exception) {}
                    try {
                        val intent = Intent(baseIntent).apply {
                            setClassName(pkg, cls)
                            setDataAndType(Uri.parse(streamUrl), "video/*")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        playerLauncher.launch(intent)
                        launched = true
                        break
                    } catch (_: Exception) {}
                }
                if (!launched) {
                    for (pkg in packagesToTry) {
                        try {
                            val intent = Intent(baseIntent).apply {
                                setPackage(pkg)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            playerLauncher.launch(intent)
                            launched = true
                            break
                        } catch (_: Exception) {}
                        try {
                            val intent = Intent(baseIntent).apply {
                                setPackage(pkg)
                                setDataAndType(Uri.parse(streamUrl), "video/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            playerLauncher.launch(intent)
                            launched = true
                            break
                        } catch (_: Exception) {}
                    }
                }
                if (!launched) {
                    try {
                        val resolveInfo = packageManager.queryIntentActivities(
                            Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(streamUrl), "video/*") },
                            0
                        )
                        val mpvMatch = resolveInfo.firstOrNull { 
                            val pkgName = it.activityInfo.packageName.lowercase()
                            val label = it.loadLabel(packageManager).toString().lowercase()
                            pkgName == "is.xyz.mpv" || pkgName == "is.xyz.mpv.debug" || label.contains("mpv")
                        }
                        if (mpvMatch != null) {
                            val intent = Intent(baseIntent).apply {
                                setClassName(mpvMatch.activityInfo.packageName, mpvMatch.activityInfo.name)
                                setDataAndType(Uri.parse(streamUrl), "video/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            playerLauncher.launch(intent)
                            launched = true
                        }
                    } catch (_: Exception) {}
                }
                if (!launched) {
                    AlertDialog.Builder(this)
                        .setTitle("🎬 MPV Player (Internal) Not Found")
                        .setMessage("MPV Player (is.xyz.mpv) was not detected on your device.\n\nInstall MPV Player directly from the Google Play Store for the best internal streaming experience, or choose another installed player.")
                        .setPositiveButton("Choose Installed Player") { _, _ ->
                            val chooser = Intent.createChooser(baseIntent, "Select Video Player")
                            try { playerLauncher.launch(chooser) } catch (_: Exception) {
                                Toast.makeText(this, "No video player found on device!", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNeutralButton("Install from Play Store") { _, _ ->
                            try {
                                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=is.xyz.mpv")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(marketIntent)
                            } catch (_: Exception) {
                                try {
                                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=is.xyz.mpv")).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(webIntent)
                                } catch (_: Exception) {
                                    Toast.makeText(this, "Could not open Play Store or browser", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            "mpvex" -> {
                val packagesToTry = listOf(
                    "app.marlboroadvance.mpvex",
                    "app.marlboroadvance.mpvex.debug",
                    "com.marlboroadvance.mpvex",
                    "id.nzxm.mpvex"
                )
                var launched = false
                val explicitClasses = listOf(
                    "app.marlboroadvance.mpvex" to "app.marlboroadvance.mpvex.player.PlayerActivity",
                    "app.marlboroadvance.mpvex" to "app.marlboroadvance.mpvex.MainActivity",
                    "app.marlboroadvance.mpvex.debug" to "app.marlboroadvance.mpvex.player.PlayerActivity"
                )
                for ((pkg, cls) in explicitClasses) {
                    try {
                        val intent = Intent(baseIntent).apply {
                            setClassName(pkg, cls)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        playerLauncher.launch(intent)
                        launched = true
                        break
                    } catch (_: Exception) {}
                    try {
                        val intent = Intent(baseIntent).apply {
                            setClassName(pkg, cls)
                            setDataAndType(Uri.parse(streamUrl), "video/*")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        playerLauncher.launch(intent)
                        launched = true
                        break
                    } catch (_: Exception) {}
                }
                if (!launched) {
                    for (pkg in packagesToTry) {
                        try {
                            val intent = Intent(baseIntent).apply {
                                setPackage(pkg)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            playerLauncher.launch(intent)
                            launched = true
                            break
                        } catch (_: Exception) {}
                        try {
                            val intent = Intent(baseIntent).apply {
                                setPackage(pkg)
                                setDataAndType(Uri.parse(streamUrl), "video/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            playerLauncher.launch(intent)
                            launched = true
                            break
                        } catch (_: Exception) {}
                    }
                }
                if (!launched) {
                    try {
                        val resolveInfo = packageManager.queryIntentActivities(
                            Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(streamUrl), "video/*") },
                            0
                        )
                        val mpvMatch = resolveInfo.firstOrNull { 
                            val pkgName = it.activityInfo.packageName.lowercase()
                            val label = it.loadLabel(packageManager).toString().lowercase()
                            pkgName.contains("mpvex") || label.contains("mpvex")
                        }
                        if (mpvMatch != null) {
                            val intent = Intent(baseIntent).apply {
                                setClassName(mpvMatch.activityInfo.packageName, mpvMatch.activityInfo.name)
                                setDataAndType(Uri.parse(streamUrl), "video/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            playerLauncher.launch(intent)
                            launched = true
                        }
                    } catch (_: Exception) {}
                }
                if (!launched) {
                    val chooser = Intent.createChooser(baseIntent, "Select MPVEX Player")
                    try { playerLauncher.launch(chooser) } catch (_: Exception) {}
                }
            }
            "vlc" -> {
                val vlcIntent = Intent(baseIntent).apply {
                    setClassName("org.videolan.vlc", "org.videolan.vlc.gui.video.VideoPlayerActivity")
                }
                var launched = false
                try {
                    playerLauncher.launch(vlcIntent)
                    launched = true
                } catch (_: Exception) {}

                if (!launched) {
                    val fallbackIntent = Intent(baseIntent).apply { setPackage("org.videolan.vlc") }
                    try {
                        playerLauncher.launch(fallbackIntent)
                        launched = true
                    } catch (e: Exception) {
                        Toast.makeText(this, "VLC Player is not installed on your device", Toast.LENGTH_SHORT).show()
                    }
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
            val currentList = loadRawWatchHistory().toMutableList()
            // Only remove exact duplicate by ID or streamUrl (not by title — we want to keep different files of same movie/series)
            currentList.removeAll { it.id == item.id || (it.streamUrl.isNotBlank() && it.streamUrl == item.streamUrl) || it.type == "channel" }
            currentList.add(0, item)
            val trimmed = if (currentList.size > 120) currentList.subList(0, 120) else currentList
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
                    put("originalFileName", m.originalFileName)
                    put("fileSize", m.fileSize)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString("history_items", jsonArray.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error saving watch history: ${e.message}")
        }
    }

    // Raw history loader — returns individual entries without grouping
    private fun loadRawWatchHistory(): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        try {
            val prefs = getSharedPreferences("teleflix_watch_history", android.content.Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("history_items", null) ?: return list
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val itemType = obj.optString("type", "movie")
                if (itemType == "channel") continue
                val sUrl = TelegramStreamingProxy.refreshUrl(obj.optString("streamUrl", ""))
                val parsedSize = obj.optLong("fileSize", 0L).let { if (it > 0) it else extractSizeFromUrl(sUrl) }
                val itemId = obj.optString("id", "")
                var pUrl = TelegramStreamingProxy.refreshUrl(obj.optString("posterUrl", ""))

                // Check for original Telegram video thumbnail if available
                if (itemId.contains("_")) {
                    val cleanId = itemId.removePrefix("group_")
                    val parts = cleanId.split("_")
                    if (parts.size >= 2) {
                        val cId = parts[0].toLongOrNull()
                        val mId = parts[1].toLongOrNull()
                        if (cId != null && mId != null && cId != 0L) {
                            val tgThumbUrl = TelegramRepository.getThumbnailUrl(cId, mId)
                            if (tgThumbUrl.isNotBlank()) {
                                pUrl = tgThumbUrl
                            }
                        }
                    }
                }

                list.add(
                    MediaItem(
                        id = itemId,
                        title = obj.optString("title", "Unknown"),
                        posterUrl = pUrl,
                        year = obj.optString("year", ""),
                        rating = obj.optString("rating", ""),
                        overview = obj.optString("overview", ""),
                        type = itemType,
                        streamUrl = sUrl,
                        originalFileName = obj.optString("originalFileName", ""),
                        fileSize = parsedSize
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error loading watch history: ${e.message}")
        }
        return list
    }

    private fun loadWatchHistory(): List<MediaItem> {
        val rawList = loadRawWatchHistory().distinctBy { it.id.ifBlank { it.streamUrl } }
        if (rawList.isEmpty()) return rawList

        // Map raw items to TelegramVideoMessage with index tracking to preserve exact order
        val indexedMessages = rawList.mapIndexed { idx, item ->
            val pId = item.id.removePrefix("single_").removePrefix("stream_").removePrefix("zip_").removePrefix("group_")
            val pParts = pId.split("_")
            val cId = pParts.getOrNull(0)?.toLongOrNull() ?: 0L
            val mId = pParts.getOrNull(1)?.toLongOrNull() ?: 0L
            val fId = extractFileIdFromUrl(item.streamUrl) ?: 0
            val sz = if (item.fileSize > 0) item.fileSize else extractSizeFromUrl(item.streamUrl)
            val fName = item.originalFileName.ifBlank { item.title }
            val fakeMsgId = if (mId != 0L) mId else (idx.toLong() + 1_000_000L)
            Pair(
                item,
                TelegramVideoMessage(
                    messageId = fakeMsgId,
                    chatId = cId,
                    fileName = fName,
                    fileSize = sz,
                    duration = 0,
                    fileId = fId,
                    mimeType = "video/mp4",
                    caption = ""
                )
            )
        }

        val messages = indexedMessages.map { it.second }
        val itemByMsgId = indexedMessages.associate { it.second.messageId to it.first }
        val displayItems = TelegramRepository.groupAndPreserveOrder(messages)

        val result = mutableListOf<MediaItem>()
        for (disp in displayItems) {
            when (disp) {
                is DisplayItem.Single -> {
                    val single = itemByMsgId[disp.message.messageId] ?: continue
                    val displayName = single.originalFileName.ifBlank { single.title }
                    val cleanTitle = displayName
                        .removePrefix("Select:")
                        .removePrefix("Select")
                        .removePrefix("📦 ")
                        .removePrefix("🗄️ ")
                        .removePrefix("📂 ")
                        .removeSuffix(" (Combined)")
                        .trim()
                    val overviewText = if (single.overview.isNotBlank() && !single.overview.contains("Playing stream")) {
                        single.overview
                    } else {
                        "Playing stream:\n► $cleanTitle"
                    }
                    result.add(
                        single.copy(
                            title = cleanTitle,
                            year = "Watched",
                            rating = "▶",
                            overview = overviewText,
                            type = "telegram_media"
                        )
                    )
                }
                is DisplayItem.Group -> {
                    val matchedMedia = disp.group.parts.mapNotNull { itemByMsgId[it.messageId] }
                    val mostRecent = matchedMedia.firstOrNull() ?: continue
                    val cleanBaseName = disp.group.baseName
                        .removePrefix("Select:")
                        .removePrefix("Select")
                        .removePrefix("📦 ")
                        .removePrefix("🗄️ ")
                        .removePrefix("📂 ")
                        .removeSuffix(" (Combined)")
                        .trim()
                    val totalSize = disp.group.parts.sumOf { it.fileSize }
                    val totalSizeStr = formatFileSize(totalSize)
                    val groupId = "group_${disp.group.parts.firstOrNull()?.chatId ?: 0}_$cleanBaseName"
                    telegramGroupPartsCache[groupId] = disp.group.parts

                    result.add(
                        MediaItem(
                            id = groupId,
                            title = cleanBaseName,
                            posterUrl = mostRecent.posterUrl,
                            year = "Watched",
                            rating = "▶",
                            overview = "Multi-part video pack: $cleanBaseName\n(${disp.group.parts.size} parts • $totalSizeStr)",
                            type = "history_group",
                            streamUrl = mostRecent.streamUrl,
                            originalFileName = mostRecent.originalFileName,
                            groupedFiles = matchedMedia
                        )
                    )
                }
            }
        }
        return result
    }

    private fun clearWatchHistory() {
        try {
            getSharedPreferences("teleflix_watch_history", android.content.Context.MODE_PRIVATE)
                .edit()
                .remove("history_items")
                .apply()
        } catch (_: Exception) {}
    }

    // Show a file picker dialog for grouped history entries (uses standard showGroupPartsSelectionDialog)
    private fun showHistoryGroupFilesPicker(groupItem: MediaItem) {
        val cached = telegramGroupPartsCache[groupItem.id]
        if (cached != null && cached.isNotEmpty()) {
            showGroupPartsSelectionDialog(groupItem, cached, groupItem.title)
            return
        }

        val files = groupItem.groupedFiles
        if (files.isEmpty()) return

        val parts = files.filter { file ->
            !file.streamUrl.contains("/merged/") &&
            !file.title.contains("(Combined)") &&
            !file.originalFileName.contains("(Combined)") &&
            (file.fileSize > 0 || extractSizeFromUrl(file.streamUrl) > 0)
        }.map { file ->
            val pId = file.id.removePrefix("single_").removePrefix("stream_")
            val pParts = pId.split("_")
            val cId = pParts.getOrNull(0)?.toLongOrNull() ?: 0L
            val mId = pParts.getOrNull(1)?.toLongOrNull() ?: 0L
            val fId = extractFileIdFromUrl(file.streamUrl) ?: 0
            val sz = if (file.fileSize > 0) file.fileSize else extractSizeFromUrl(file.streamUrl)
            TelegramVideoMessage(
                messageId = mId,
                chatId = cId,
                fileName = file.originalFileName.ifBlank { file.title },
                fileSize = sz,
                duration = 0,
                fileId = fId,
                mimeType = "video/mp4",
                caption = ""
            )
        }.distinctBy { if (it.messageId != 0L) "${it.chatId}_${it.messageId}" else it.fileName }

        if (parts.isEmpty()) {
            playHistoryItem(groupItem)
            return
        }

        showGroupPartsSelectionDialog(groupItem, parts, groupItem.title)
    }

    // Play a specific history item (resolves fresh URLs if needed)
    private fun playHistoryItem(item: MediaItem) {
        val streamInfo = telegramStreamCache[item.id]
        val titleToPlay = streamInfo?.second ?: item.title
        val fileName = item.originalFileName.ifBlank { titleToPlay }
        val groupInfo = telegramGroupCache[item.id]
        val rawUrl = streamInfo?.first ?: item.streamUrl

        fun ensureMsgRef(url: String, cId: Long?, mId: Long?): String {
            if (url.isBlank() || cId == null || mId == null || cId == 0L || mId == 0L) return url
            if (url.contains("chatId=") || url.contains("chats=")) return url
            val separator = if (url.contains("?")) "&" else "?"
            return "$url${separator}chatId=$cId&messageId=$mId"
        }

        if (rawUrl.contains("/merged/")) {
            val queryStr = rawUrl.substringAfter("?", "")
            val reqChats = queryStr.split("&").find { it.startsWith("chats=") }?.substringAfter("=")?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
            val reqMessages = queryStr.split("&").find { it.startsWith("messages=") }?.substringAfter("=")?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
            val reqSizes = queryStr.split("&").find { it.startsWith("sizes=") }?.substringAfter("=")?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
            val urlFileNameSegment = rawUrl.substringAfter("/merged/").substringBefore("?")
            val urlFileName = urlFileNameSegment.substringAfter("/", "").takeIf { it.isNotBlank() }?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: titleToPlay.removePrefix("📦 ")

            if (reqChats.size == reqMessages.size && reqChats.isNotEmpty()) {
                CoroutineScope(Dispatchers.Main).launch {
                    val parts = reqChats.zip(reqMessages)
                    val freshUrl = TelegramRepository.getFreshMergedMediaUrl(parts, urlFileName, reqSizes)
                    if (freshUrl != null && freshUrl.isNotBlank()) {
                        checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id, fileName)
                        return@launch
                    }
                    val backupUrl = TelegramStreamingProxy.refreshUrl(rawUrl)
                    checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                }
                return
            }
        }

        if (groupInfo != null) {
            CoroutineScope(Dispatchers.Main).launch {
                val cleanTitle = titleToPlay.removePrefix("📦 ")
                val freshUrl = TelegramRepository.getFreshMergedMediaUrl(groupInfo.first, cleanTitle, groupInfo.second)
                if (freshUrl != null && freshUrl.isNotBlank()) {
                    checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id, fileName)
                } else {
                    val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                    checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                }
            }
        } else if (item.id.startsWith("group_")) {
            val rest = item.id.removePrefix("group_")
            val chatId = rest.substringBefore("_").toLongOrNull()
            val baseName = rest.substringAfter("_")
            if (chatId != null && chatId != 0L) {
                Toast.makeText(this, "Refreshing media stream...", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.Main).launch {
                    val mediaMessages = withContext(Dispatchers.IO) {
                        TelegramRepository.fetchChannelMedia(chatId.toString(), limit = 1000).first
                    }
                    val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)
                    val matchGroup = groupedItems.filterIsInstance<DisplayItem.Group>()
                        .find { it.group.baseName.equals(baseName, ignoreCase = true) }
                    if (matchGroup != null && matchGroup.group.parts.isNotEmpty()) {
                        val parts = matchGroup.group.parts.map { Pair(it.chatId, it.messageId) }
                        val sizes = matchGroup.group.parts.map { it.fileSize }
                        telegramGroupCache[item.id] = Pair(parts, sizes)
                        telegramGroupPartsCache[item.id] = matchGroup.group.parts
                        val freshUrl = TelegramRepository.getFreshMergedMediaUrl(parts, baseName, sizes)
                        if (freshUrl != null && freshUrl.isNotBlank()) {
                            checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id, fileName)
                            return@launch
                        }
                    }
                    val backupUrl = ensureMsgRef(TelegramStreamingProxy.refreshUrl(item.streamUrl), chatId, null)
                    if (backupUrl.isNotBlank()) {
                        checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                    } else {
                        Toast.makeText(this@MainActivity, "Media link expired or unavailable", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                val backupUrl = TelegramStreamingProxy.refreshUrl(item.streamUrl)
                if (backupUrl.isNotBlank()) {
                    checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                }
            }
        } else {
            val cleanId = item.id.removePrefix("single_").removePrefix("stream_").removePrefix("zip_")
            val parts = cleanId.split("_")
            val chatId = parts.getOrNull(0)?.toLongOrNull()
            val messageId = parts.getOrNull(1)?.toLongOrNull()

            if (chatId != null && messageId != null) {
                CoroutineScope(Dispatchers.Main).launch {
                    val mediaMessages = withContext(Dispatchers.IO) {
                        TelegramRepository.fetchChannelMedia(chatId.toString(), limit = 1000).first
                    }
                    val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)
                    val matchGroup = groupedItems.filterIsInstance<DisplayItem.Group>()
                        .find { g -> g.group.parts.any { it.messageId == messageId } }

                    if (matchGroup != null && matchGroup.group.parts.size > 1) {
                        val groupParts = matchGroup.group.parts.map { Pair(it.chatId, it.messageId) }
                        val sizes = matchGroup.group.parts.map { it.fileSize }
                        val freshUrl = TelegramRepository.getFreshMergedMediaUrl(groupParts, matchGroup.group.baseName, sizes)
                        if (freshUrl != null && freshUrl.isNotBlank()) {
                            checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id, fileName)
                            return@launch
                        }
                    }

                    val freshUrl = TelegramRepository.getFreshMediaUrl(chatId, messageId)
                    if (freshUrl != null && freshUrl.isNotBlank()) {
                        checkResumeAndSelectPlayer(freshUrl, titleToPlay, item.posterUrl, item.id, fileName)
                    } else {
                        val backupUrl = ensureMsgRef(TelegramStreamingProxy.refreshUrl(item.streamUrl), chatId, messageId)
                        if (backupUrl.isNotBlank()) {
                            checkResumeAndSelectPlayer(backupUrl, titleToPlay, item.posterUrl, item.id, fileName)
                        } else {
                            Toast.makeText(this@MainActivity, "Media link expired or unavailable", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                val rawUrl = streamInfo?.first ?: item.streamUrl
                val urlToPlay = ensureMsgRef(TelegramStreamingProxy.refreshUrl(rawUrl), chatId, messageId)
                if (urlToPlay.isNotBlank()) {
                    checkResumeAndSelectPlayer(urlToPlay, titleToPlay, item.posterUrl, item.id, fileName)
                } else {
                    Toast.makeText(this@MainActivity, "Media link expired or unavailable", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleItemLongPress(item: MediaItem): Boolean {
        if (item.id == "watch_history" || item.id == "settings") return false

        if (item.type == "channel") {
            AlertDialog.Builder(this)
                .setTitle("Remove Monitored Channel")
                .setMessage("Remove '${item.title}' from your monitored channels list?")
                .setPositiveButton("Remove") { dialog, _ ->
                    val currentChannels = TelegramRepository.getCustomChannels(this)
                    val updatedChannels = currentChannels.filterNot { ch ->
                        ch.equals(item.id, ignoreCase = true) ||
                        ch.equals(item.title, ignoreCase = true) ||
                        (ch.toLongOrNull() != null && ch.toLongOrNull() == item.id.toLongOrNull())
                    }
                    TelegramRepository.saveCustomChannels(this, updatedChannels)
                    Toast.makeText(this, "Removed '${item.title}' from monitored channels", Toast.LENGTH_SHORT).show()
                    loadTelegramChannelsCatalog()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
            return true
        }
        
        val isLibraryTab = selectedCategory == "library/list"
        val isHistoryTab = selectedCategory == "history/list"
        val options = when {
            isLibraryTab -> arrayOf("🗑️ Remove from Library", "Cancel")
            isHistoryTab -> arrayOf("🗑️ Delete from Watch History", "🧹 Clear Entire Watch History", "Cancel")
            else -> arrayOf("🗑️ Remove from Watch History & Reset Resume Progress", "Cancel")
        }

        AlertDialog.Builder(this)
            .setTitle("Select: ${item.title}")
            .setItems(options) { _, which ->
                when {
                    isLibraryTab && which == 0 -> {
                        LibraryManager.toggleBookmark(this, item)
                        loadLibraryCatalog()
                        Toast.makeText(this, "Removed from Library", Toast.LENGTH_SHORT).show()
                    }
                    !isLibraryTab && which == 0 -> {
                        val currentList = loadRawWatchHistory().toMutableList()
                        // For history_group items, remove all grouped files
                        if (item.type == "history_group" && item.groupedFiles.isNotEmpty()) {
                            val groupIds = item.groupedFiles.map { it.id }.toSet()
                            currentList.removeAll { it.id in groupIds }
                            // Clean up resume points for all grouped files
                            val resumePrefs = getSharedPreferences("teleflix_resume_points", android.content.Context.MODE_PRIVATE).edit()
                            val titlePrefs = getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE).edit()
                            for (gf in item.groupedFiles) {
                                resumePrefs.remove(gf.streamUrl)
                                titlePrefs.remove("resume_${gf.title}")
                            }
                            resumePrefs.apply()
                            titlePrefs.apply()
                        } else {
                            currentList.removeAll { it.id == item.id }
                            getSharedPreferences("teleflix_resume_points", android.content.Context.MODE_PRIVATE)
                                .edit().remove(item.streamUrl).apply()
                            getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE)
                                .edit().remove("resume_${item.title}").apply()
                        }
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
                                put("originalFileName", m.originalFileName)
                            }
                            jsonArray.put(obj)
                        }
                        getSharedPreferences("teleflix_watch_history", android.content.Context.MODE_PRIVATE)
                            .edit().putString("history_items", jsonArray.toString()).apply()

                        if (isHistoryTab) {
                            mediaList.clear()
                            mediaList.addAll(loadWatchHistory())
                            mediaAdapter?.notifyDataSetChanged()
                            if (mediaList.isEmpty()) {
                                updateCategoryBanner(selectedLabel)
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
                        updateCategoryBanner(selectedLabel)
                        loadingText.text = "Watch history is empty. Movies and series you open will be automatically saved here!"
                        loadingText.visibility = android.view.View.VISIBLE
                        Toast.makeText(this, "Watch history deleted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
        return true
    }

    private fun extractPartNumber(name: String): Int {
        val partMatch = Regex("""[\._\s-]*(?:part|pt|cd)[\._\s-]*(\d{1,4})""", RegexOption.IGNORE_CASE).find(name)
            ?: Regex("""\((?:part|pt)?\s*(\d{1,4})\)""", RegexOption.IGNORE_CASE).find(name)
            ?: Regex("""\.\d{1,4}$""").find(name)
        return partMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun showGroupPartsSelectionDialog(item: MediaItem, parts: List<TelegramVideoMessage>, baseName: String) {
        val validParts = parts
            .filter { it.fileSize > 0 }
            .distinctBy { if (it.messageId != 0L) "${it.chatId}_${it.messageId}" else it.fileName }
            .sortedWith(compareBy({ extractPartNumber(it.fileName) }, { it.messageId }, { it.fileName }))
        val finalParts = if (validParts.isNotEmpty()) validParts else parts

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }
        val cardList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val cleanTitle = baseName.removePrefix("📦 ").removePrefix("🗄️ ")

        val headerText = TextView(this).apply {
            text = "📂 $cleanTitle"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        cardList.addView(headerText)

        val totalSize = finalParts.sumOf { it.fileSize }
        val totalSizeStr = formatFileSize(totalSize)

        val subHeaderText = TextView(this).apply {
            text = "This video has ${finalParts.size} parts (Total: $totalSizeStr). Select an option:"
            UITheme.applyMetadataStyle(this)
            setPadding(0, 4, 0, 14)
        }
        cardList.addView(subHeaderText)

        var reverseOrder = false
        val reverseCheckBox = android.widget.CheckBox(this).apply {
            text = "Reverse Part Order (Use if video fails to play)"
            setTextColor(android.graphics.Color.WHITE)
            setOnCheckedChangeListener { _, isChecked -> reverseOrder = isChecked }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 12))
            }
        }
        cardList.addView(reverseCheckBox)

        var dialog: AlertDialog? = null

        // Combined Stream Option Card
        val streamCombinedCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = UITheme.createRippleCardShape(this@MainActivity, UITheme.SURFACE, 14, UITheme.PRIMARY)
            val p = UITheme.dpToPx(this@MainActivity, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 8))
            }
            isClickable = true
            setOnClickListener {
                dialog?.dismiss()
                val streamInfo = telegramStreamCache[item.id]
                val titleToPlay = streamInfo?.second ?: item.title
                val fileName = item.originalFileName.ifBlank { titleToPlay }
                val groupInfo = telegramGroupCache[item.id]
                val activeParts = if (reverseOrder) finalParts.reversed() else finalParts
                val combinedMediaId = if (item.id.startsWith("group_")) item.id else "group_${activeParts.firstOrNull()?.chatId ?: 0}_$cleanTitle"
                telegramGroupPartsCache[combinedMediaId] = activeParts
                for (p in activeParts) {
                    val pId = "${p.chatId}_${p.messageId}"
                    val pName = p.fileName.ifBlank { "Part ${p.fileId}" }
                    val pTitle = pName
                    val pUrl = TelegramRepository.getStreamUrl(p.fileId, p.fileName, p.fileSize, p.chatId, p.messageId)
                    saveToHistory(
                        MediaItem(
                            id = pId,
                            title = pTitle,
                            posterUrl = item.posterUrl,
                            year = "Watched",
                            rating = "▶",
                            overview = "Telegram file: $pName",
                            type = "telegram_media",
                            streamUrl = pUrl,
                            originalFileName = p.fileName,
                            fileSize = p.fileSize
                        )
                    )
                }
                CoroutineScope(Dispatchers.Main).launch {
                    val urlToPlay = if (groupInfo != null && !reverseOrder) {
                        TelegramRepository.getFreshMergedMediaUrl(groupInfo.first, cleanTitle, groupInfo.second) ?: item.streamUrl
                    } else {
                        val freshIds = activeParts.map { it.fileId }
                        val partSizes = activeParts.map { it.fileSize }
                        val groupChats = activeParts.map { it.chatId }
                        val groupMsgs = activeParts.map { it.messageId }
                        TelegramRepository.getMergedStreamUrl(freshIds, cleanTitle, partSizes, groupChats, groupMsgs)
                    }
                    val freshUrl = TelegramStreamingProxy.refreshUrl(urlToPlay)
                    checkResumeAndSelectPlayer(freshUrl, "📦 $cleanTitle (Combined)", item.posterUrl, combinedMediaId, fileName)
                }
            }
        }

        val stIcon = TextView(this).apply {
            text = "🎬 ▶"
            textSize = 18f
            setPadding(0, 0, 12, 0)
        }
        streamCombinedCard.addView(stIcon)

        val stInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val stTitle = TextView(this).apply {
            text = "Stream Full Combined Video"
            UITheme.applyCardTitleStyle(this)
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        stInfo.addView(stTitle)

        val stSub = TextView(this).apply {
            text = "Stream continuous merged playback of all ${finalParts.size} parts ($totalSizeStr)"
            UITheme.applyMetadataStyle(this)
            setTextColor(Color.parseColor(UITheme.PRIMARY))
        }
        stInfo.addView(stSub)
        streamCombinedCard.addView(stInfo)

        cardList.addView(streamCombinedCard)

        // Combined Download Option Card
        val downloadCombinedCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = UITheme.createRippleCardShape(this@MainActivity, UITheme.SURFACE, 14, UITheme.PRIMARY)
            val p = UITheme.dpToPx(this@MainActivity, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 12))
            }
            isClickable = true
            setOnClickListener {
                dialog?.dismiss()
                val activeParts = if (reverseOrder) finalParts.reversed() else finalParts
                DownloadManager.startMultiPartDownload(this@MainActivity, cleanTitle, baseName, activeParts, item.posterUrl)
                Toast.makeText(this@MainActivity, "Started downloading full combined video ($totalSizeStr) 📥", Toast.LENGTH_SHORT).show()
            }
        }

        val dlIcon = TextView(this).apply {
            text = "⚡ 📥"
            textSize = 18f
            setPadding(0, 0, 12, 0)
        }
        downloadCombinedCard.addView(dlIcon)

        val dlInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val dlTitle = TextView(this).apply {
            text = "Download Full Combined Video"
            UITheme.applyCardTitleStyle(this)
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        dlInfo.addView(dlTitle)

        val dlSub = TextView(this).apply {
            text = "Combines all ${finalParts.size} parts into a single file ($totalSizeStr)"
            UITheme.applyMetadataStyle(this)
            setTextColor(Color.parseColor(UITheme.PRIMARY))
        }
        dlInfo.addView(dlSub)
        downloadCombinedCard.addView(dlInfo)

        cardList.addView(downloadCombinedCard)

        // Individual Parts Options
        finalParts.forEachIndexed { index, part ->
            val partNumStr = String.format("%03d", index + 1)
            val partTitle = part.fileName.ifBlank { "Part $partNumStr" }
            val partSize = formatFileSize(part.fileSize)
            val partMediaId = "${part.chatId}_${part.messageId}"
            val displayPartTitle = if (partTitle.contains(cleanTitle, ignoreCase = true)) partTitle else "$cleanTitle - Part ${index + 1}"

            val partCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = UITheme.createRippleCardShape(this@MainActivity, UITheme.CARD, 12, UITheme.STROKE_COLOR)
                val p = UITheme.dpToPx(this@MainActivity, 10)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 6))
                }
                isClickable = true
                setOnClickListener {
                    dialog?.dismiss()
                    CoroutineScope(Dispatchers.Main).launch {
                        val freshUrl = TelegramRepository.getFreshMediaUrl(part.chatId, part.messageId)
                        if (freshUrl != null && freshUrl.isNotBlank()) {
                            val groupId = if (item.id.startsWith("group_")) item.id else "group_${part.chatId}_$cleanTitle"
                            telegramGroupPartsCache[groupId] = finalParts
                            for (p in finalParts) {
                                val pId = "${p.chatId}_${p.messageId}"
                                val pName = p.fileName.ifBlank { "Part ${p.fileId}" }
                                val pTitle = pName
                                val pUrl = TelegramRepository.getStreamUrl(p.fileId, p.fileName, p.fileSize, p.chatId, p.messageId)
                                saveToHistory(
                                    MediaItem(
                                        id = pId,
                                        title = pTitle,
                                        posterUrl = item.posterUrl,
                                        year = "Watched",
                                        rating = "▶",
                                        overview = "Telegram file: $pName",
                                        type = "telegram_media",
                                        streamUrl = pUrl,
                                        originalFileName = p.fileName,
                                        fileSize = p.fileSize
                                    )
                                )
                            }
                            checkResumeAndSelectPlayer(freshUrl, displayPartTitle, item.posterUrl, partMediaId, part.fileName)
                        } else {
                            Toast.makeText(this@MainActivity, "Media link expired", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            val iconText = TextView(this).apply {
                text = "▶ Part ${index + 1}"
                UITheme.applyCardTitleStyle(this)
                textSize = 13f
                setTextColor(Color.parseColor(UITheme.PRIMARY))
                setPadding(0, 0, 12, 0)
            }
            partCard.addView(iconText)

            val partInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val pTitle = TextView(this).apply {
                text = partTitle
                UITheme.applyCardTitleStyle(this)
                textSize = 13f
            }
            partInfo.addView(pTitle)

            val pSub = TextView(this).apply {
                text = "Size: $partSize"
                UITheme.applyMetadataStyle(this)
                textSize = 11f
            }
            partInfo.addView(pSub)

            partCard.addView(partInfo)
            cardList.addView(partCard)
        }

        scrollView.addView(cardList)

        dialog = AlertDialog.Builder(this)
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        val metrics = resources.displayMetrics
        val w = (metrics.widthPixels * 0.92).toInt()
        val h = (metrics.heightPixels * 0.82).toInt()
        dialog.window?.setLayout(w, h)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor(UITheme.BACKGROUND)))
    }

    private fun showTelegramChatPicker() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val titleText = TextView(this).apply {
            text = "💬 Select Telegram Channels & Chats"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        dialogView.addView(titleText)

        val subText = TextView(this).apply {
            text = "Select channels, groups, or archived chats to display in your catalog."
            UITheme.applyMetadataStyle(this)
            setPadding(0, 0, 0, UITheme.dpToPx(this@MainActivity, 12))
        }
        dialogView.addView(subText)

        val searchInput = EditText(this).apply {
            hint = "🔍 Search chats by name..."
            setHintTextColor(Color.parseColor(UITheme.TEXT_SECONDARY))
            setTextColor(Color.WHITE)
            background = UITheme.createInputBackground(this@MainActivity)
            val pV = UITheme.dpToPx(this@MainActivity, 10)
            val pH = UITheme.dpToPx(this@MainActivity, 12)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 12))
            }
        }
        dialogView.addView(searchInput)

        var activeChatsList: List<TelegramChatInfo> = TelegramRepository.getCachedJoinedChatsInfo(this@MainActivity)
        val hasCachedChats = activeChatsList.isNotEmpty()

        val contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val loadingIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = if (hasCachedChats) android.view.View.GONE else android.view.View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER)
        }
        contentContainer.addView(loadingIndicator)

        val pickerRecyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = if (hasCachedChats) android.view.View.VISIBLE else android.view.View.GONE
        }
        val currentMonitored = TdlibManager.getChannels(this).map { it.username }.toMutableSet()
        val pickerAdapter = ChatPickerAdapter(activeChatsList, currentMonitored, this@MainActivity)
        pickerRecyclerView.adapter = pickerAdapter
        contentContainer.addView(pickerRecyclerView)
        dialogView.addView(contentContainer)

        var alertDialog: AlertDialog? = null
        alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save Selected") { _, _ ->
                TdlibManager.setChannels(this@MainActivity, currentMonitored.toList())
                if (isTelegramCatalogMode) {
                    loadTelegramChannelsCatalog()
                }
                Toast.makeText(this@MainActivity, "Updated catalog channels!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()

        var searchJob: kotlinx.coroutines.Job? = null
        fun filterList(filterQuery: String = "") {
            searchJob?.cancel()
            val trimmed = filterQuery.trim()
            val chats = activeChatsList
            val localFiltered = if (trimmed.isBlank()) chats else chats.filter {
                it.title.contains(trimmed, ignoreCase = true) ||
                (it.username != null && it.username.contains(trimmed, ignoreCase = true))
            }
            pickerAdapter.updateList(localFiltered)

            if (trimmed.length >= 2) {
                searchJob = CoroutineScope(Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(300)
                    val remoteResults = TelegramRepository.searchChatsAndChannels(trimmed)
                    if (remoteResults.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (alertDialog?.isShowing == true && searchInput.text?.toString()?.trim() == trimmed) {
                                val combined = (localFiltered + remoteResults).distinctBy { it.chatId }
                                pickerAdapter.updateList(combined)
                            }
                        }
                    }
                }
            }
        }

        alertDialog.show()
        val metrics = resources.displayMetrics
        val dialogW = (metrics.widthPixels * 0.92).toInt()
        val dialogH = (metrics.heightPixels * 0.85).toInt()
        alertDialog.window?.setLayout(dialogW, dialogH)
        alertDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor(UITheme.BACKGROUND)))

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Background update for fresh chats
        CoroutineScope(Dispatchers.IO).launch {
            val chats = TelegramRepository.getJoinedChatsInfo(forceRefresh = true)
            withContext(Dispatchers.Main) {
                if (alertDialog.isShowing) {
                    activeChatsList = chats
                    loadingIndicator.visibility = android.view.View.GONE
                    pickerRecyclerView.visibility = android.view.View.VISIBLE
                    filterList(searchInput.text?.toString() ?: "")
                }
            }
        }
    }

    private class ChatPickerAdapter(
        private var chatsList: List<TelegramChatInfo>,
        private val currentMonitored: MutableSet<String>,
        private val context: Context
    ) : RecyclerView.Adapter<ChatPickerAdapter.ViewHolder>() {

        class ViewHolder(
            val row: LinearLayout,
            val avatarView: ImageView,
            val titleV: TextView,
            val subV: TextView,
            val checkBox: CheckBox
        ) : RecyclerView.ViewHolder(row)

        fun updateList(newChats: List<TelegramChatInfo>) {
            chatsList = newChats
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = chatsList.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = UITheme.createRippleCardShape(context, UITheme.CARD, 12, UITheme.STROKE_COLOR)
                val p = UITheme.dpToPx(context, 10)
                setPadding(p, p, p, p)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(context, 6))
                }
            }

            val avatarView = ImageView(context).apply {
                val sz = UITheme.dpToPx(context, 40)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    setMargins(0, 0, UITheme.dpToPx(context, 10), 0)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            row.addView(avatarView)

            val infoLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleV = TextView(context).apply {
                UITheme.applyCardTitleStyle(this)
                textSize = 13f
            }
            infoLayout.addView(titleV)

            val subV = TextView(context).apply {
                UITheme.applyMetadataStyle(this)
                textSize = 11f
            }
            infoLayout.addView(subV)
            row.addView(infoLayout)

            val checkBox = CheckBox(context)
            row.addView(checkBox)

            return ViewHolder(row, avatarView, titleV, subV, checkBox)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val chat = chatsList[position]
            val chatKey = chat.chatId.toString()
            val chatKeyNo100 = chatKey.removePrefix("-100")
            val isChecked = currentMonitored.contains(chatKey) ||
                    currentMonitored.contains(chatKeyNo100) ||
                    currentMonitored.contains("-100$chatKey") ||
                    (chat.username != null && currentMonitored.contains("@" + chat.username))

            holder.titleV.text = chat.title
            val typeStr = when {
                chat.isBot -> "🤖 Bot"
                chat.isChannel -> "📢 Channel"
                chat.isGroup -> "👥 Group"
                chat.isArchived -> "📦 Archived"
                else -> "💬 Chat"
            }
            holder.subV.text = if (!chat.username.isNullOrBlank()) "$typeStr • @${chat.username}" else typeStr

            if (chat.photoFileId != null && chat.photoFileId > 0) {
                val thumbUrl = TelegramStreamingProxy.getThumbnailUrl(chat.photoFileId)
                com.bumptech.glide.Glide.with(context)
                    .load(thumbUrl)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_gallery)
                    .circleCrop()
                    .into(holder.avatarView)
            } else {
                holder.avatarView.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = isChecked
            holder.checkBox.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    currentMonitored.add(chatKey)
                } else {
                    currentMonitored.remove(chatKey)
                    currentMonitored.remove(chatKeyNo100)
                    currentMonitored.remove("-100$chatKey")
                    if (chat.username != null) {
                        currentMonitored.remove("@" + chat.username)
                    }
                }
            }

            holder.row.setOnClickListener {
                holder.checkBox.isChecked = !holder.checkBox.isChecked
            }
        }
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
                loadNetflixHome("home")
            }
            return
        }
        if (selectedCategory != "home/netflix" && !isTelegramCatalogMode) {
            loadNetflixHome("home")
            return
        }
        super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        (recyclerView.layoutManager as? GridLayoutManager)?.spanCount = if (isLandscape) 12 else 6
        mediaAdapter?.notifyDataSetChanged()
    }

    override fun onDestroy() {
        if (isFinishing) {
            TeleflixLogger.clearLogsOnExit()
            try { TelegramClient.clearMediaCacheSync(this) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if ((level == TRIM_MEMORY_COMPLETE || level == TRIM_MEMORY_MODERATE) && !DownloadManager.hasActiveDownloads()) {
            try { TelegramClient.clearMediaCache(this) } catch (_: Exception) {}
        }
    }

    // ── Downloads Engine & UI Helpers ─────────────────────────────

    private fun extractFileIdFromUrl(url: String): Int? {
        return extractFileIdsFromUrl(url).firstOrNull()
    }



    private fun downloadStreamSource(stream: StreamSource, displayTitle: String, posterUrl: String) {
        val cleanFileName = stream.fileName.removePrefix("📺 ").removePrefix("🗄️ ").removePrefix("📦 ").trim()
        val cleanDisplayTitle = displayTitle.removePrefix("📺 ").removePrefix("🗄️ ").removePrefix("📦 ").trim()
        val originalName = cleanFileName.ifBlank { cleanDisplayTitle }
        val fileName = DownloadManager.sanitizeFileName(originalName)

        val cachedParts = telegramGroupPartsCache[stream.id] ?: telegramGroupPartsCache["group_${stream.chatId}_$cleanFileName"]
        val isZip = stream.isZip || stream.id.startsWith("zip_") || TelegramRepository.isZipArchiveFilename(cleanFileName)
        val isSplit = stream.isSplit || stream.id.startsWith("group_") || (cachedParts != null && cachedParts.size > 1)

        // Only show popup for multi-part split video files (NEVER for zip files)
        if (!isZip && isSplit) {
            if (cachedParts != null && cachedParts.isNotEmpty()) {
                val mediaItem = MediaItem(
                    id = stream.id,
                    title = originalName,
                    originalFileName = originalName,
                    posterUrl = posterUrl,
                    year = stream.size,
                    rating = stream.quality,
                    overview = "Video: $originalName",
                    type = "telegram_media",
                    streamUrl = stream.url
                )
                showGroupDownloadOptionsDialog(mediaItem, cachedParts, originalName)
                return
            }

            if (stream.chatId != 0L) {
                Toast.makeText(this, "Resolving video download...", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.Main).launch {
                    val mediaMessages = withContext(Dispatchers.IO) {
                        TelegramRepository.fetchChannelMedia(stream.chatId.toString(), limit = 200).first
                    }
                    val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)
                    val cleanBase = cleanFileName.removePrefix("📦 ").removePrefix("🗄️ ").trim()
                    val matchGroup = groupedItems.filterIsInstance<DisplayItem.Group>()
                        .find { it.group.baseName.equals(cleanBase, ignoreCase = true) || it.group.parts.any { p -> stream.url.contains(p.fileId.toString()) } }

                    val parts = if (matchGroup != null && matchGroup.group.parts.isNotEmpty()) {
                        matchGroup.group.parts
                    } else {
                        val cleanId = stream.id.removePrefix("single_").removePrefix("stream_").removePrefix("zip_")
                        val msgId = cleanId.split("_").getOrNull(1)?.toLongOrNull() ?: 0L
                        val fId = extractFileIdFromUrl(stream.url) ?: 0
                        listOf(
                            TelegramVideoMessage(
                                messageId = msgId,
                                chatId = stream.chatId,
                                fileName = originalName,
                                fileSize = extractSizeFromUrl(stream.url),
                                duration = 0,
                                fileId = fId,
                                mimeType = "video/mp4",
                                caption = ""
                            )
                        )
                    }

                    telegramGroupPartsCache[stream.id] = parts
                    val mediaItem = MediaItem(
                        id = stream.id,
                        title = originalName,
                        originalFileName = originalName,
                        posterUrl = posterUrl,
                        year = stream.size,
                        rating = stream.quality,
                        overview = "Video: $originalName",
                        type = "telegram_media",
                        streamUrl = stream.url
                    )
                    showGroupDownloadOptionsDialog(mediaItem, parts, originalName)
                }
                return
            }
        }

        // Single non-zip file: download directly without popup using original file name
        val fileId = extractFileIdFromUrl(stream.url)
        if (fileId != null && fileId != 0) {
            val cleanId = stream.id.removePrefix("single_").removePrefix("stream_").removePrefix("zip_")
            val messageId = cleanId.split("_").getOrNull(1)?.toLongOrNull() ?: 0L
            val chatId = if (stream.chatId != 0L) stream.chatId else cleanId.split("_").getOrNull(0)?.toLongOrNull() ?: 0L
            DownloadManager.startDownload(
                context = this,
                title = originalName,
                fileName = fileName,
                fileId = fileId,
                chatId = chatId,
                messageId = messageId,
                posterUrl = posterUrl
            )
            Toast.makeText(this, "Started downloading '$originalName' 📥", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Unable to extract file download ID", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showGroupDownloadOptionsDialog(item: MediaItem, parts: List<TelegramVideoMessage>, cleanTitle: String) {
        val validParts = parts
            .filter { it.fileSize > 0 }
            .distinctBy { if (it.messageId != 0L) "${it.chatId}_${it.messageId}" else it.fileName }
            .sortedWith(compareBy({ extractPartNumber(it.fileName) }, { it.messageId }, { it.fileName }))
        val finalParts = if (validParts.isNotEmpty()) validParts else parts

        val totalSize = finalParts.sumOf { it.fileSize }
        val totalSizeStr = formatFileSize(totalSize)

        val isZip = item.id.startsWith("zip_") || TelegramRepository.isZipArchiveFilename(cleanTitle) || finalParts.any { TelegramRepository.isZipArchiveFilename(it.fileName) }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }
        val cardList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val headerText = TextView(this).apply {
            text = if (isZip) "🗄️ Download ZIP Archive" else "📥 Download Multi-Part Video"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        cardList.addView(headerText)

        val subHeaderText = TextView(this).apply {
            text = if (finalParts.size > 1) "'$cleanTitle' has ${finalParts.size} parts (Total: $totalSizeStr)." else "'$cleanTitle' (Total: $totalSizeStr)."
            UITheme.applyMetadataStyle(this)
            setPadding(0, 4, 0, 14)
        }
        cardList.addView(subHeaderText)

        var reverseOrder = false
        val reverseCheckBox = android.widget.CheckBox(this).apply {
            text = "Reverse Part Order (Use if combined video fails to play)"
            setTextColor(android.graphics.Color.WHITE)
            setOnCheckedChangeListener { _, isChecked -> reverseOrder = isChecked }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 12))
            }
        }
        cardList.addView(reverseCheckBox)

        var dialog: AlertDialog? = null

        // Option 1: Download & Combine All Parts
        val optCombine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = UITheme.createRippleCardShape(this@MainActivity, UITheme.SURFACE, 14, UITheme.PRIMARY)
            val p = UITheme.dpToPx(this@MainActivity, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 10))
            }
            isClickable = true
            setOnClickListener {
                dialog?.dismiss()
                val activeParts = if (reverseOrder) finalParts.reversed() else finalParts
                DownloadManager.startMultiPartDownload(this@MainActivity, cleanTitle, item.originalFileName.ifBlank { cleanTitle }, activeParts, item.posterUrl)
                Toast.makeText(this@MainActivity, "Started downloading & combining '$cleanTitle' 📥", Toast.LENGTH_SHORT).show()
            }
        }

        val icon1 = TextView(this).apply { text = "⚡"; textSize = 18f; setPadding(0, 0, 12, 0) }
        optCombine.addView(icon1)

        val info1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val t1 = TextView(this).apply { text = if (isZip) "Extract / Save as 1 Video File" else "Combine & Save as 1 Video File"; UITheme.applyCardTitleStyle(this); textSize = 14f; setTextColor(Color.WHITE) }
        val s1 = TextView(this).apply { text = if (finalParts.size > 1) "Downloads all ${finalParts.size} parts and merges into single file ($totalSizeStr)" else "Downloads and saves as single video file ($totalSizeStr)"; UITheme.applyMetadataStyle(this); setTextColor(Color.parseColor(UITheme.PRIMARY)) }
        info1.addView(t1); info1.addView(s1)
        optCombine.addView(info1)
        cardList.addView(optCombine)

        // Option 2: Download All Parts Separately
        val optSep = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = UITheme.createRippleCardShape(this@MainActivity, UITheme.CARD, 14, UITheme.STROKE_COLOR)
            val p = UITheme.dpToPx(this@MainActivity, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 14))
            }
            isClickable = true
            setOnClickListener {
                dialog?.dismiss()
                finalParts.forEachIndexed { idx, part ->
                    val partTitle = "${cleanTitle} (Part ${idx + 1})"
                    val fileName = DownloadManager.sanitizeFileName(part.fileName.ifBlank { "${cleanTitle}_part${idx + 1}" })
                    DownloadManager.startDownload(this@MainActivity, partTitle, fileName, part.fileId, part.chatId, part.messageId, item.posterUrl, part.fileSize)
                }
                Toast.makeText(this@MainActivity, "Started downloading ${finalParts.size} separate parts 📥", Toast.LENGTH_SHORT).show()
            }
        }

        val icon2 = TextView(this).apply { text = "📂"; textSize = 18f; setPadding(0, 0, 12, 0) }
        optSep.addView(icon2)

        val info2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val t2 = TextView(this).apply { text = "Download All Parts Separately"; UITheme.applyCardTitleStyle(this); textSize = 14f; setTextColor(Color.WHITE) }
        val s2 = TextView(this).apply { text = "Saves ${finalParts.size} individual files to your Downloads folder"; UITheme.applyMetadataStyle(this); setTextColor(Color.parseColor(UITheme.TEXT_SECONDARY)) }
        info2.addView(t2); info2.addView(s2)
        optSep.addView(info2)
        cardList.addView(optSep)

        // Parts Header
        val partsTitle = TextView(this).apply {
            text = "Or Select Specific Part to Download:"
            UITheme.applyCardTitleStyle(this)
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        cardList.addView(partsTitle)

        finalParts.forEachIndexed { index, part ->
            val partTitle = part.fileName.ifBlank { "Part ${index + 1}" }
            val partSize = formatFileSize(part.fileSize)

            val partCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = UITheme.createRippleCardShape(this@MainActivity, UITheme.CARD, 12, UITheme.STROKE_COLOR)
                val p = UITheme.dpToPx(this@MainActivity, 10)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 6))
                }
                isClickable = true
                setOnClickListener {
                    dialog?.dismiss()
                    val fileName = DownloadManager.sanitizeFileName(partTitle)
                    DownloadManager.startDownload(this@MainActivity, "${cleanTitle} - ${partTitle}", fileName, part.fileId, part.chatId, part.messageId, item.posterUrl, part.fileSize)
                    Toast.makeText(this@MainActivity, "Started downloading ${partTitle} 📥", Toast.LENGTH_SHORT).show()
                }
            }

            val iconText = TextView(this).apply {
                text = "📥 Part ${index + 1}"
                UITheme.applyCardTitleStyle(this)
                textSize = 13f
                setTextColor(Color.parseColor(UITheme.PRIMARY))
                setPadding(0, 0, 12, 0)
            }
            partCard.addView(iconText)

            val partInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val pTitle = TextView(this).apply {
                text = partTitle
                UITheme.applyCardTitleStyle(this)
                textSize = 13f
                setTextColor(Color.WHITE)
            }
            partInfo.addView(pTitle)

            val pSub = TextView(this).apply {
                text = partSize
                UITheme.applyMetadataStyle(this)
            }
            partInfo.addView(pSub)

            partCard.addView(partInfo)
            cardList.addView(partCard)
        }

        scrollView.addView(cardList)
        dialog = AlertDialog.Builder(this)
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun extractSizeFromUrl(url: String): Long {
        if (url.isBlank()) return 0L
        val match = Regex("""[?&]size=(\d+)""", RegexOption.IGNORE_CASE).find(url)
        return match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    }

    private fun handleDownloadItem(item: MediaItem) {
        if (item.type == "channel") {
            Toast.makeText(this, "Select a video inside the channel to download", Toast.LENGTH_SHORT).show()
            return
        }
        if (item.type == "auto_series") {
            showAutoSeriesSeasons(item, isDownloadMode = true)
            return
        }

        val cleanOriginalName = item.originalFileName.removePrefix("📺 ").removePrefix("🗄️ ").removePrefix("📦 ").trim()
        val cleanDisplayTitle = item.title.removePrefix("Select:").removePrefix("Select").removePrefix("📺 ").removePrefix("🗄️ ").removePrefix("📦 ").trim()
        val originalName = cleanOriginalName.ifBlank { cleanDisplayTitle }
        val fileName = DownloadManager.sanitizeFileName(originalName)

        val cachedParts = telegramGroupPartsCache[item.id]
        val parts = if (item.groupedFiles.isNotEmpty()) {
            item.groupedFiles.map { 
                val pId = it.id.removePrefix("single_").removePrefix("stream_").removePrefix("zip_")
                val pParts = pId.split("_")
                val cId = pParts.getOrNull(0)?.toLongOrNull() ?: 0L
                val mId = pParts.getOrNull(1)?.toLongOrNull() ?: 0L
                val fId = extractFileIdFromUrl(it.streamUrl) ?: 0
                val sz = if (it.fileSize > 0) it.fileSize else extractSizeFromUrl(it.streamUrl)
                TelegramVideoMessage(
                    messageId = mId,
                    chatId = cId,
                    fileName = it.originalFileName.ifBlank { it.title },
                    fileSize = sz,
                    duration = 0,
                    fileId = fId,
                    mimeType = "video/mp4",
                    caption = ""
                )
            }
        } else {
            cachedParts ?: emptyList()
        }

        val isZip = item.id.startsWith("zip_") || TelegramRepository.isZipArchiveFilename(originalName) || parts.any { TelegramRepository.isZipArchiveFilename(it.fileName) }
        val isSplit = item.id.startsWith("group_") || item.type == "history_group" || parts.size > 1

        // Only show popup for multi-part split video files (NEVER for zip files)
        if (!isZip && isSplit) {
            if (parts.isNotEmpty()) {
                showGroupDownloadOptionsDialog(item, parts, originalName)
                return
            }
            if (item.id.startsWith("group_") || item.id.startsWith("zip_")) {
                val cleanId = item.id.removePrefix("single_").removePrefix("stream_").removePrefix("zip_").removePrefix("group_")
                val chatId = cleanId.substringBefore("_").toLongOrNull()
                if (chatId != null && chatId != 0L) {
                    Toast.makeText(this, "Resolving video download...", Toast.LENGTH_SHORT).show()
                    CoroutineScope(Dispatchers.Main).launch {
                        val mediaMessages = withContext(Dispatchers.IO) {
                            TelegramRepository.fetchChannelMedia(chatId.toString(), limit = 200).first
                        }
                        val groupedItems = TelegramRepository.groupAndPreserveOrder(mediaMessages)
                        val matchGroup = groupedItems.filterIsInstance<DisplayItem.Group>()
                            .find { it.group.baseName.equals(originalName, ignoreCase = true) || it.group.parts.any { p -> item.streamUrl.contains(p.fileId.toString()) } }
                        val resolvedParts = matchGroup?.group?.parts ?: emptyList()
                        if (resolvedParts.isNotEmpty()) {
                            telegramGroupPartsCache[item.id] = resolvedParts
                            showGroupDownloadOptionsDialog(item, resolvedParts, originalName)
                        } else {
                            val fileId = extractFileIdFromUrl(item.streamUrl)
                            val messageId = cleanId.substringAfter("_").toLongOrNull() ?: 0L
                            if (fileId != null && fileId != 0) {
                                val singlePart = listOf(
                                    TelegramVideoMessage(
                                        messageId = messageId,
                                        chatId = chatId,
                                        fileName = originalName,
                                        fileSize = if (item.fileSize > 0) item.fileSize else extractSizeFromUrl(item.streamUrl),
                                        duration = 0,
                                        fileId = fileId,
                                        mimeType = "video/mp4",
                                        caption = ""
                                    )
                                )
                                showGroupDownloadOptionsDialog(item, singlePart, originalName)
                            }
                        }
                    }
                    return
                }
            }
        }

        val streamInfo = telegramStreamCache[item.id]
        val rawUrl = streamInfo?.first ?: item.streamUrl
        val fileId = extractFileIdFromUrl(rawUrl)

        if (fileId != null && fileId != 0) {
            val rest = item.id.removePrefix("single_").removePrefix("stream_").removePrefix("zip_")
            val pList = rest.split("_")
            val chatId = pList.getOrNull(0)?.toLongOrNull() ?: 0L
            val messageId = pList.getOrNull(1)?.toLongOrNull() ?: 0L

            DownloadManager.startDownload(
                context = this,
                title = originalName,
                fileName = fileName,
                fileId = fileId,
                chatId = chatId,
                messageId = messageId,
                posterUrl = item.posterUrl
            )
            Toast.makeText(this, "Started downloading '$originalName' 📥", Toast.LENGTH_SHORT).show()
            return
        }

        if (item.type == "telegram_media" || item.streamUrl.contains("/stream") || item.streamUrl.contains("http") || item.id.contains("_")) {
            val rest = item.id.removePrefix("single_").removePrefix("stream_").removePrefix("zip_")
            val pList = rest.split("_")
            val chatId = pList.getOrNull(0)?.toLongOrNull() ?: 0L
            val messageId = pList.getOrNull(1)?.toLongOrNull() ?: 0L

            Toast.makeText(this, "Resolving video link for download...", Toast.LENGTH_SHORT).show()
            if (chatId != 0L && messageId != 0L) {
                CoroutineScope(Dispatchers.Main).launch {
                    val freshUrl = withContext(Dispatchers.IO) {
                        TelegramRepository.getFreshMediaUrl(chatId, messageId)
                    }
                    if (freshUrl != null) {
                        val freshId = extractFileIdFromUrl(freshUrl)
                        if (freshId != null && freshId != 0) {
                            DownloadManager.startDownload(
                                context = this@MainActivity,
                                title = originalName,
                                fileName = fileName,
                                fileId = freshId,
                                chatId = chatId,
                                messageId = messageId,
                                posterUrl = item.posterUrl
                            )
                            Toast.makeText(this@MainActivity, "Started downloading '$originalName' 📥", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                    }
                    Toast.makeText(this@MainActivity, "Failed to resolve download link", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

        if (item.type == "series" || item.type == "tv") {
            fetchSeriesEpisodes(item, isDownloadMode = true)
        } else {
            val yearInt = item.year.filter { it.isDigit() }.take(4).toIntOrNull()
            showStreamOptions(item.title, null, null, item.posterUrl, isDownloadMode = true, year = yearInt)
        }
    }

    private var downloadsObserveJob: kotlinx.coroutines.Job? = null

    private fun showDownloadsDialog() {
        val context = this
        val builder = AlertDialog.Builder(context)

        fun dp(v: Int) = UITheme.dpToPx(context, v)

        // Main container panel
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UITheme.createCardShape(context, "#050608", 20, "#1F2633", 1)
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }

        // Header Row
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }

        val headerCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleText = TextView(context).apply {
            text = "📥 Offline Downloads"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
            textSize = 19f
        }

        val subtitleText = TextView(context).apply {
            text = "Watch your content anytime, anywhere"
            UITheme.applyMetadataStyle(this)
            textSize = 12f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, dp(2), 0, 0)
        }

        headerCol.addView(titleText)
        headerCol.addView(subtitleText)

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#9CA3AF"))
            gravity = android.view.Gravity.CENTER
            background = UITheme.createCardShape(context, "#161B22", 10, "#21262D", 1)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            isClickable = true
            isFocusable = true
        }

        titleRow.addView(headerCol)
        titleRow.addView(closeBtn)
        container.addView(titleRow)

        // Scrollable Download Items Area
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = true
        }

        val itemsListLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollView.addView(itemsListLayout)
        container.addView(scrollView)

        // Footer Summary Section
        val footerDivider = android.view.View(context).apply {
            setBackgroundColor(Color.parseColor("#21262D"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(0, dp(10), 0, dp(12))
            }
        }
        container.addView(footerDivider)

        val footerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val summaryText = TextView(context).apply {
            UITheme.applyMetadataStyle(this)
            textSize = 12f
            setTextColor(Color.parseColor("#9CA3AF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val clearAllBtn = TextView(context).apply {
            text = "🗑 Clear All"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FF3B30"))
            background = UITheme.createCardShape(context, "#261014", 10, "#E50914", 1)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
        }

        footerRow.addView(summaryText)
        footerRow.addView(clearAllBtn)
        container.addView(footerRow)

        val dialog = builder.setView(container).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        closeBtn.setOnClickListener { dialog.dismiss() }

        clearAllBtn.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Clear All Downloads?")
                .setMessage("Are you sure you want to delete all offline downloaded files?")
                .setPositiveButton("Yes, Clear All") { d, _ ->
                    DownloadManager.clearAllDownloads(context)
                    Toast.makeText(context, "All downloads cleared", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        downloadsObserveJob?.cancel()
        downloadsObserveJob = CoroutineScope(Dispatchers.Main).launch {
            DownloadManager.downloadsFlow.collect { downloads ->
                val activeDownloads = downloads.filter { it.status != DownloadStatus.COMPLETED }
                itemsListLayout.removeAllViews()
                if (activeDownloads.isEmpty()) {
                    summaryText.text = "📥 0 Active Downloads"
                    clearAllBtn.visibility = android.view.View.GONE
                    val emptyView = TextView(context).apply {
                        text = "No active or paused downloads.\nTap 📥 on any movie card to start downloading!"
                        UITheme.applyMetadataStyle(this)
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, dp(50), 0, dp(50))
                    }
                    itemsListLayout.addView(emptyView)
                } else {
                    val activeCount = activeDownloads.count { it.status == DownloadStatus.DOWNLOADING }
                    summaryText.text = "📥 $activeCount/${activeDownloads.size} Active/Paused Downloads"
                    clearAllBtn.visibility = android.view.View.VISIBLE

                    for (item in activeDownloads) {
                        val card = createDownloadItemCard(context, item, dialog)
                        itemsListLayout.addView(card)
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            downloadsObserveJob?.cancel()
        }

        dialog.show()

        val metrics = resources.displayMetrics
        val w = (metrics.widthPixels * 0.92).toInt()
        val h = (metrics.heightPixels * 0.78).toInt()
        dialog.window?.setLayout(w, h)
    }

    private fun createDownloadItemCard(context: android.content.Context, item: DownloadItem, dialog: AlertDialog): android.view.View {
        fun dp(v: Int) = UITheme.dpToPx(context, v)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UITheme.createCardShape(context, "#0F1218", 16, "#21262D", 1)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) }
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        // Left Status Icon Container
        val statusContainer = TextView(context).apply {
            text = when (item.status) {
                DownloadStatus.COMPLETED -> "✔"
                DownloadStatus.DOWNLOADING -> "📥"
                DownloadStatus.PAUSED -> "⏸"
                DownloadStatus.FAILED -> "⚠️"
                DownloadStatus.QUEUED -> "⏳"
            }
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            val (bg, stroke) = when (item.status) {
                DownloadStatus.COMPLETED -> "#064E3B" to "#10B981"
                DownloadStatus.DOWNLOADING -> "#3B0A0E" to "#E50914"
                DownloadStatus.PAUSED -> "#1F2937" to "#4B5563"
                DownloadStatus.FAILED -> "#451A03" to "#F59E0B"
                DownloadStatus.QUEUED -> "#1F2937" to "#4B5563"
            }
            background = UITheme.createCardShape(context, bg, 12, stroke, 1)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(0, 0, dp(12), 0)
            }
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(context).apply {
            text = item.title
            UITheme.applyCardTitleStyle(this)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val subText = TextView(context).apply {
            UITheme.applyMetadataStyle(this)
            textSize = 11f
            text = when (item.status) {
                DownloadStatus.COMPLETED -> "Completed • ${item.getFormattedSize()}"
                DownloadStatus.DOWNLOADING -> "${item.getFormattedSpeed()} • ${item.progressPercent}% (${item.getFormattedSize()})"
                DownloadStatus.PAUSED -> "Paused • ${item.progressPercent}%"
                DownloadStatus.FAILED -> "Download Failed"
                DownloadStatus.QUEUED -> "Queued..."
            }
        }

        textCol.addView(titleView)
        textCol.addView(subText)

        topRow.addView(statusContainer)
        topRow.addView(textCol)
        card.addView(topRow)

        if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.PAUSED) {
            val progressBar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = item.progressPercent
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(if (item.status == DownloadStatus.DOWNLOADING) "#E50914" else "#4B5563"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(4)
                ).apply { setMargins(0, dp(8), 0, dp(8)) }
            }
            card.addView(progressBar)
        }

        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, dp(6), 0, 0)
        }

        if (item.status == DownloadStatus.COMPLETED) {
            val playBtn = TextView(context).apply {
                text = "▶ Play Offline"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = UITheme.createCardShape(context, "#10B981", 10, "#10B981", 1)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    checkResumeAndSelectPlayer(item.localPath, item.title, item.posterUrl, item.id, item.fileName)
                }
            }
            val delBtn = TextView(context).apply {
                text = "🗑 Delete"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#FF3B30"))
                background = UITheme.createCardShape(context, "#261014", 10, "#E50914", 1)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), 0, 0, 0)
                }
                layoutParams = lp
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                setOnClickListener {
                    showDeleteConfirmationDialog(context, item, isCancel = false)
                }
            }
            actionsRow.addView(playBtn)
            actionsRow.addView(delBtn)
        } else if (item.status == DownloadStatus.DOWNLOADING) {
            val pauseBtn = TextView(context).apply {
                text = "⏸ Pause"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = UITheme.createCardShape(context, "#1F2937", 10, "#4B5563", 1)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                setOnClickListener {
                    DownloadManager.pauseDownload(context, item.id)
                }
            }
            val cancelBtn = TextView(context).apply {
                text = "✖ Cancel"
                textSize = 12f
                setTextColor(Color.parseColor("#FF3B30"))
                background = UITheme.createCardShape(context, "#261014", 10, "#E50914", 1)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), 0, 0, 0)
                }
                layoutParams = lp
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                setOnClickListener {
                    showDeleteConfirmationDialog(context, item, isCancel = true)
                }
            }
            actionsRow.addView(pauseBtn)
            actionsRow.addView(cancelBtn)
        } else if (item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.FAILED) {
            val resumeBtn = TextView(context).apply {
                text = "▶ Resume"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = UITheme.createCardShape(context, "#2563EB", 10, "#2563EB", 1)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                setOnClickListener {
                    DownloadManager.resumeDownload(context, item.id)
                }
            }
            val delBtn = TextView(context).apply {
                text = "🗑 Delete"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#FF3B30"))
                background = UITheme.createCardShape(context, "#261014", 10, "#E50914", 1)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), 0, 0, 0)
                }
                layoutParams = lp
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                setOnClickListener {
                    showDeleteConfirmationDialog(context, item, isCancel = false)
                }
            }
            actionsRow.addView(resumeBtn)
            actionsRow.addView(delBtn)
        }

        card.addView(actionsRow)
        return card
    }

    private fun showDeleteConfirmationDialog(context: Context, item: DownloadItem, isCancel: Boolean = false) {
        val titleText = if (isCancel) "Cancel Download?" else "Delete Download?"
        val messageText = if (isCancel) {
            "Are you sure you want to cancel downloading '${item.title}'?"
        } else {
            "Are you sure you want to delete '${item.title}'?\nThis will permanently remove the downloaded file from your device storage."
        }

        AlertDialog.Builder(context)
            .setTitle(titleText)
            .setMessage(messageText)
            .setPositiveButton(if (isCancel) "Yes, Cancel" else "Yes, Delete") { dialog, _ ->
                if (isCancel) {
                    DownloadManager.cancelDownload(context, item.id)
                    Toast.makeText(context, "Cancelled '${item.title}'", Toast.LENGTH_SHORT).show()
                } else {
                    DownloadManager.deleteDownloadedFile(context, item.id)
                    Toast.makeText(context, "Deleted '${item.title}'", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Keep File") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun handleTabSwipe(direction: Int) {
        if (isTelegramCatalogMode) return
        
        var currentIndex = categories.indexOfFirst { it.second == selectedCategory }
        if (currentIndex == -1) {
            currentIndex = 0
        }
        
        var newIndex = currentIndex - direction
        
        if (newIndex in categories.indices && categories[newIndex].second == "genres/picker") {
            newIndex -= direction
        }
        
        if (newIndex in categories.indices) {
            val (label, catalogId) = categories[newIndex]
            if (catalogId == "library/list") {
                loadLibraryCatalog(label)
            } else if (catalogId == "channels/list") {
                isTelegramCatalogMode = true
                modeToggleButton.text = "🎬 ARVIO Stream"
                modeToggleButton.setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
                modeToggleButton.background = UITheme.createCardShape(this, UITheme.SECONDARY, 14, UITheme.ACCENT_BLUE, 1)
                updateCategoryBanner("Monitored Telegram Channels")
                searchInput.hint = "Search chats & channels..."
                loadTelegramChannelsCatalog()
                updateTabSelection(catalogId)
            } else {
                selectedCategory = catalogId
                selectedLabel = label
                updateCategoryBanner(label)
                loadInitialCinemeta(catalogId, label)
                updateTabSelection(catalogId)
            }
            tabScroll.post {
                val tab = tabRow.getChildAt(newIndex)
                if (tab != null) {
                    val scrollX = tab.left - (tabScroll.width / 2) + (tab.width / 2)
                    tabScroll.smoothScrollTo(scrollX, 0)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { TelegramService.start(this) } catch (_: Exception) {}
        }
    }
}
