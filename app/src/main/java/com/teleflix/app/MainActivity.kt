package com.teleflix.app

import android.content.Context
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
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
    val type: String = "movie",  // "movie" or "series" or "telegram_media" or "history_group"
    val streamUrl: String = "",
    val originalFileName: String = "",
    val fileSize: Long = 0L,
    val groupedFiles: List<MediaItem> = emptyList()
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
    private lateinit var fabSelectChats: ImageButton
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
    private val telegramGroupPartsCache get() = TelegramRepository.groupPartsCache

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

        val urlToClean = activeStreamUrlForResume
        if (urlToClean.isNotBlank()) {
            val fileIds = extractFileIdsFromUrl(urlToClean)
            for (fileId in fileIds) {
                if (fileId != 0 && !DownloadManager.isFileIdActive(fileId)) {
                    TelegramStreamingProxy.clearStreamCache(fileId)
                    TelegramClient.deleteFile(fileId)
                    TeleflixLogger.log("MainActivity", "Cleaned stream cache for fileId=$fileId on player exit")
                }
            }
        }
    }

    private val mediaList = mutableListOf<MediaItem>()
    private var mediaAdapter: MediaAdapter? = null

    private val cinemetaCatalogCache = java.util.concurrent.ConcurrentHashMap<String, List<MediaItem>>()
    private val cinemetaSeriesCache = java.util.concurrent.ConcurrentHashMap<String, Map<Int, List<EpisodeItem>>>()

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
        "🎭 Genres" to "genres/picker",
        "📚 Library" to "library/list"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            view.setPadding(
                pad + insets.left,
                pad + insets.top,
                pad + insets.right,
                pad + insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        // Top App Header
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UITheme.dpToPx(this@MainActivity, 14))
        }

        val titleView = TextView(this).apply {
            text = "TELEFLIX"
            UITheme.applyLargeTitleStyle(this)
            setTextColor(Color.parseColor(UITheme.PRIMARY)) // Netflix Red
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

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
            text = "🎬 Cinemeta"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.NORMAL)
            background = UITheme.createCardShape(this@MainActivity, UITheme.SECONDARY, 14, UITheme.ACCENT_BLUE, 1)
            setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
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

        headerLayout.addView(titleView)
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

        // Category Banner / Current Selection Header
        categoryLabel = TextView(this).apply {
            text = selectedLabel
            UITheme.applySectionTitleStyle(this)
            setPadding(0, UITheme.dpToPx(this@MainActivity, 4), 0, UITheme.dpToPx(this@MainActivity, 10))
        }
        rootView.addView(categoryLabel)

        // Loading and Search Spinner / Text
        loadingText = TextView(this).apply {
            text = "Loading catalog..."
            UITheme.applyMetadataStyle(this)
            background = UITheme.createCardShape(this@MainActivity, UITheme.SURFACE, 12, UITheme.STROKE_COLOR, 1)
            val pV = UITheme.dpToPx(this@MainActivity, 10)
            val pH = UITheme.dpToPx(this@MainActivity, 14)
            setPadding(pH, pV, pH, pV)
            visibility = android.view.View.GONE
        }
        rootView.addView(loadingText)

        // Search Box (Cinemeta Search)
        val searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = UITheme.createInputBackground(this@MainActivity)
            setPadding(UITheme.dpToPx(this@MainActivity, 6), UITheme.dpToPx(this@MainActivity, 4), UITheme.dpToPx(this@MainActivity, 6), UITheme.dpToPx(this@MainActivity, 4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 14))
            }
        }

        searchInput = EditText(this).apply {
            hint = "Search Movies & Series..."
            setHintTextColor(Color.parseColor(UITheme.TEXT_SECONDARY))
            setTextColor(Color.WHITE)
            background = null
            val pV = UITheme.dpToPx(this@MainActivity, 10)
            val pH = UITheme.dpToPx(this@MainActivity, 12)
            setPadding(pH, pV, pH, pV)
            textSize = 14f
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)) {
                    val q = text.toString()
                    if (q.isNotBlank()) {
                        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                        imm?.hideSoftInputFromWindow(windowToken, 0)
                        if (isTelegramCatalogMode) {
                            performTelegramSearch(q)
                        } else {
                            performSearch(q)
                        }
                    }
                    true
                } else {
                    false
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        searchButton = Button(this).apply {
            text = "🔍"
            textSize = 16f
            background = UITheme.createBadgeDrawable(this@MainActivity, UITheme.PRIMARY, 12)
            setTextColor(Color.WHITE)
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

        searchContainer.addView(searchInput)
        searchContainer.addView(searchButton)
        rootView.addView(searchContainer)

        // Category Tabs (Horizontal Scroll)
        tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, UITheme.dpToPx(this@MainActivity, 12))
        }

        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        categories.forEach { (label, catalogId) ->
            val tab = Button(this).apply {
                text = label
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.NORMAL)
                val isSelected = catalogId == selectedCategory
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor(UITheme.TEXT_SECONDARY))
                background = UITheme.createPillDrawable(this@MainActivity, isSelected, UITheme.PRIMARY, UITheme.SURFACE)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, UITheme.dpToPx(this@MainActivity, 8), 0) }
                layoutParams = lp
                setOnClickListener {
                    if (catalogId == "genres/picker") {
                        showGenreSelectionDialog()
                        return@setOnClickListener
                    }
                    if (catalogId == "library/list") {
                        loadLibraryCatalog()
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
                            val cleanId = item.id.removePrefix("single_").removePrefix("stream_").removePrefix("zip_")
                            val parts = cleanId.split("_")
                            val chatId = parts.getOrNull(0)?.toLongOrNull()
                            val messageId = parts.getOrNull(1)?.toLongOrNull()

                            if (chatId != null && messageId != null && streamInfo == null) {
                                CoroutineScope(Dispatchers.Main).launch {
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
                            val cleanId = item.id.removePrefix("single_").removePrefix("stream_")
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
                "series" -> fetchSeriesEpisodes(item)
                else -> showStreamOptions(item.title, null, null, item.posterUrl)
            }
        }, { item ->
            handleItemLongPress(item)
        }, { item, nowBookmarked ->
            if (selectedCategory == "library/list" && !nowBookmarked) {
                loadLibraryCatalog()
            }
        }, { item ->
            handleDownloadItem(item)
        })

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val initialSpan = if (isLandscape) 4 else 2
        val gridLayoutManager = GridLayoutManager(this, initialSpan).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val item = mediaList.getOrNull(position)
                    val currentSpan = spanCount
                    return when (item?.type) {
                        "channel" -> currentSpan
                        else -> 1
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
            child.setTextColor(if (isSelected) Color.WHITE else Color.parseColor(UITheme.TEXT_SECONDARY))
            child.background = UITheme.createPillDrawable(this, isSelected, UITheme.PRIMARY, UITheme.SURFACE)
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

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "🎭 Select Movie / Series Genre"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, UITheme.dpToPx(this@MainActivity, 14))
        }
        container.addView(title)

        var dialog: AlertDialog? = null

        for (i in genreIcons.indices) {
            val genreCard = TextView(this).apply {
                text = genreIcons[i]
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
                    val genre = genres[i]
                    showFormatSelectionDialog(genre)
                }
            }
            container.addView(genreCard)
        }

        scrollView.addView(container)

        dialog = AlertDialog.Builder(this)
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun showFormatSelectionDialog(genre: String) {
        val optionsView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UITheme.createCardShape(this@MainActivity, UITheme.CARD, 18, UITheme.STROKE_COLOR, 1)
            val pad = UITheme.dpToPx(this@MainActivity, 20)
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Select Category for $genre"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, UITheme.dpToPx(this@MainActivity, 16))
        }
        optionsView.addView(title)

        var fmtDialog: AlertDialog? = null

        val movieCard = TextView(this).apply {
            text = "🎬 Top $genre Movies"
            UITheme.applyCardTitleStyle(this)
            background = UITheme.createRippleCardShape(this@MainActivity, UITheme.SURFACE, 14, UITheme.PRIMARY)
            val pV = UITheme.dpToPx(this@MainActivity, 14)
            val pH = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pH, pV, pH, pV)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 10)) }
            setOnClickListener {
                fmtDialog?.dismiss()
                val catalogId = "movie/top/genre=$genre"
                val label = "🎬 Top $genre Movies"
                selectedCategory = catalogId
                selectedLabel = label
                categoryLabel.text = label
                categoryLabel.isClickable = false
                updateTabSelection(catalogId)
                loadInitialCinemeta(catalogId, label)
            }
        }
        optionsView.addView(movieCard)

        val seriesCard = TextView(this).apply {
            text = "📺 Top $genre Series"
            UITheme.applyCardTitleStyle(this)
            background = UITheme.createRippleCardShape(this@MainActivity, UITheme.SURFACE, 14, UITheme.ACCENT_BLUE)
            val pV = UITheme.dpToPx(this@MainActivity, 14)
            val pH = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pH, pV, pH, pV)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                fmtDialog?.dismiss()
                val catalogId = "series/top/genre=$genre"
                val label = "📺 Top $genre Series"
                selectedCategory = catalogId
                selectedLabel = label
                categoryLabel.text = label
                categoryLabel.isClickable = false
                updateTabSelection(catalogId)
                loadInitialCinemeta(catalogId, label)
            }
        }
        optionsView.addView(seriesCard)

        fmtDialog = AlertDialog.Builder(this)
            .setView(optionsView)
            .setNegativeButton("Back") { _, _ -> showGenreSelectionDialog() }
            .create()
        fmtDialog.show()
    }

    // ── Catalog Loading & Endless Pagination ────────────────────

    private fun loadLibraryCatalog(label: String = "📚 Library") {
        isInSearchMode = false
        hasMoreItems = false
        isLoadingMore = false
        selectedCategory = "library/list"
        selectedLabel = label
        categoryLabel.text = label
        categoryLabel.isClickable = false
        updateTabSelection("library/list")

        val libraryItems = LibraryManager.getBookmarkedItems(this)
        mediaList.clear()
        mediaList.addAll(libraryItems)
        mediaAdapter?.notifyDataSetChanged()

        if (libraryItems.isEmpty()) {
            loadingText.text = "Your Library is empty. Bookmark movies, series, or Telegram media using the 🔖⭐ icon on any poster to save them here!"
            loadingText.visibility = android.view.View.VISIBLE
        } else {
            loadingText.visibility = android.view.View.GONE
            categoryLabel.text = "$label  •  [ 🗑️ Clear Library ]"
            categoryLabel.isClickable = true
            categoryLabel.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Clear Library?")
                    .setMessage("Are you sure you want to remove all bookmarked items from your Library?")
                    .setPositiveButton("🗑️ Clear All") { _, _ ->
                        LibraryManager.clearLibrary(this)
                        mediaList.clear()
                        mediaAdapter?.notifyDataSetChanged()
                        categoryLabel.text = label
                        categoryLabel.isClickable = false
                        loadingText.text = "Your Library is empty."
                        loadingText.visibility = android.view.View.VISIBLE
                        Toast.makeText(this, "Library cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun loadInitialCinemeta(catalogId: String, label: String) {
        isInSearchMode = false
        currentSkip = 0
        hasMoreItems = true
        isLoadingMore = true

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
                if (results.isNotEmpty()) {
                    cinemetaCatalogCache[catalogId] = results
                }

                withContext(Dispatchers.Main) {
                    mediaList.clear()
                    mediaList.addAll(results)
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
        if (isLoadingMore || !hasMoreItems || isInSearchMode) return

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
        isInSearchMode = true
        categoryLabel.text = "Search: \"$query\""
        loadingText.text = "Searching Cinemeta..."
        loadingText.visibility = android.view.View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val allResults = coroutineScope {
                listOf("movie", "series").map { type ->
                    async(Dispatchers.IO) {
                        try {
                            val url = URL("https://v3-cinemeta.strem.io/catalog/$type/top/search=${java.net.URLEncoder.encode(query, "UTF-8")}.json")
                            val connection = url.openConnection() as HttpURLConnection
                            connection.requestMethod = "GET"
                            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            connection.connectTimeout = 5000
                            connection.readTimeout = 5000
                            val text = connection.inputStream.bufferedReader().readText()
                            val json = JSONObject(text)
                            val metas = json.optJSONArray("metas") ?: JSONArray()
                            parseMetas(metas, type)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
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
            tabScroll.visibility = android.view.View.GONE
            modeToggleButton.text = "💬 Telegram Channels"
            modeToggleButton.setTextColor(Color.parseColor(UITheme.SUCCESS))
            modeToggleButton.background = UITheme.createCardShape(this, UITheme.SECONDARY, 14, UITheme.SUCCESS, 1)
            categoryLabel.text = "Monitored Telegram Channels"
            categoryLabel.isClickable = false
            searchInput.hint = "Default Telegram search (all chats & channels)..."
            loadTelegramChannelsCatalog()
        } else {
            currentOpenChannelId = null
            tabScroll.visibility = android.view.View.VISIBLE
            modeToggleButton.text = "🎬 Cinemeta"
            modeToggleButton.setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
            modeToggleButton.background = UITheme.createCardShape(this, UITheme.SECONDARY, 14, UITheme.ACCENT_BLUE, 1)
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
        if (cachedSeasons != null && cachedSeasons.isNotEmpty()) {
            showSeasonPicker(item.title, cachedSeasons, item.posterUrl, isDownloadMode = isDownloadMode)
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
                    showSeasonPicker(item.title, seasons, item.posterUrl, isDownloadMode = isDownloadMode)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    Toast.makeText(this@MainActivity, "Failed to load episodes: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSeasonPicker(seriesTitle: String, seasons: Map<Int, List<EpisodeItem>>, posterUrl: String = "", isDownloadMode: Boolean = false) {
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
                    showEpisodePicker(seriesTitle, seasonNum, episodes, posterUrl, isDownloadMode = isDownloadMode)
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

    private fun showEpisodePicker(seriesTitle: String, season: Int, episodes: List<EpisodeItem>, posterUrl: String = "", isDownloadMode: Boolean = false) {
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
                    showStreamOptions(seriesTitle, season, ep.episode, posterUrl, isDownloadMode = isDownloadMode)
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

    private fun showStreamOptions(title: String, season: Int? = null, episode: Int? = null, posterUrl: String = "", isDownloadMode: Boolean = false) {
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
                TdlibManager.resolveStreams(title, season, episode)
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
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        background = UITheme.createRippleCardShape(this@MainActivity, "#11151D", 16, "#21262D")
                        val pad = dp(12)
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

                    // Left Source/Media Icon Box (48x48dp fixed)
                    val iconBox = TextView(this@MainActivity).apply {
                        text = if (stream.isZip) "🤐" else if (stream.isSplit) "📦" else "🎬"
                        textSize = 18f
                        gravity = android.view.Gravity.CENTER
                        background = UITheme.createCardShape(this@MainActivity, "#1A202C", 14, "#21262D", 1)
                        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                            setMargins(0, 0, dp(12), 0)
                        }
                    }
                    card.addView(iconBox)

                    // Center Details Column (Flexible width weight = 1f)
                    val detailsCol = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(0, 0, dp(10), 0)
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
                    detailsCol.addView(filenameText)

                    // Horizontal Badges Layout Row
                    val badgesRow = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, dp(6), 0, 0)
                    }

                    if (stream.quality.isNotBlank()) {
                        val qualityBadge = TextView(this@MainActivity).apply {
                            text = "🎬 ${stream.quality}"
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

                    if (stream.isSplit) {
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
                    } else if (stream.isZip) {
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

                    detailsCol.addView(badgesRow)
                    card.addView(detailsCol)

                    // Right Play Action Button (Fixed dimensions: 95dp x 44dp)
                    val playAction = TextView(this@MainActivity).apply {
                        text = if (isDownloadMode) "⚡ DOWN" else "▶ PLAY"
                        textSize = 12f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        background = UITheme.createCardShape(this@MainActivity, "#E50914", 12, "#E50914", 1)
                        setTextColor(Color.WHITE)
                        gravity = android.view.Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(95), dp(44))
                    }
                    card.addView(playAction)

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

    private fun saveLinkToWatchHistory(streamUrl: String, title: String, posterUrl: String, mediaId: String, originalFileName: String = "") {
        if (streamUrl.isBlank() && mediaId.isBlank()) return
        val effectiveId = if (mediaId.isNotBlank()) mediaId else "link_" + streamUrl.hashCode()
        var effectivePoster = posterUrl
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
            "🔴 MPVEX / MPV Player (External App)",
            "🧡 VLC Player",
            "📱 Choose From All Installed Players..."
        )
        val keys = arrayOf("exo", "mpv", "vlc", "chooser")

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

        val isPlaylist = streamUrl.contains("/playlist/") || streamUrl.lowercase().contains(".m3u8") || streamUrl.lowercase().contains(".m3u")
        val isMkv = !isPlaylist && (title.endsWith(".mkv", ignoreCase = true) || streamUrl.lowercase().contains(".mkv"))
        val mimeType = when {
            isPlaylist -> "application/vnd.apple.mpegurl"
            isMkv -> "video/x-matroska"
            else -> "video/*"
        }

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
            "exo" -> {
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
            "mpv", "mpvex" -> {
                val packagesToTry = listOf(
                    "app.marlboroadvance.mpvex",
                    "app.marlboroadvance.mpvex.debug",
                    "com.marlboroadvance.mpvex",
                    "is.xyz.mpv",
                    "is.xyz.mpv.debug",
                    "id.nzxm.mpv"
                )
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
                        val mpvMatch = resolveInfo.firstOrNull { 
                            val pkgName = it.activityInfo.packageName.lowercase()
                            val label = it.loadLabel(packageManager).toString().lowercase()
                            pkgName.contains("mpvex") || pkgName.contains("mpv") || label.contains("mpvex") || label.contains("mpv")
                        }
                        if (mpvMatch != null) {
                            val intent = Intent(baseIntent).apply { setPackage(mpvMatch.activityInfo.packageName) }
                            playerLauncher.launch(intent)
                            launched = true
                        }
                    } catch (_: Exception) {}
                }
                if (!launched) {
                    val chooser = Intent.createChooser(baseIntent, "Select MPV / MPVEX Player")
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
            val currentList = loadRawWatchHistory().toMutableList()
            // Only remove exact duplicate by ID (not by title — we want to keep different files of same movie/series)
            currentList.removeAll { it.id == item.id || it.type == "channel" }
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
                list.add(
                    MediaItem(
                        id = obj.optString("id", ""),
                        title = obj.optString("title", "Unknown"),
                        posterUrl = TelegramStreamingProxy.refreshUrl(obj.optString("posterUrl", "")),
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
        val rawList = loadRawWatchHistory()
        if (rawList.isEmpty()) return rawList

        // Normalize title for grouping (strip episode/part prefixes & suffixes & bracket noise)
        fun normalizeTitle(title: String): String {
            var clean = title.trim()
                .removePrefix("Select:")
                .removePrefix("Select")
                .removePrefix("📦")
                .removePrefix("🗄️")
                .removePrefix("📂")
                .trim()
            clean = clean.removeSuffix(" (Combined)").trim()
            clean = clean.replace(Regex("""[\._\s-]*(?:part|pt|cd)[\._\s-]*\d+.*$""", RegexOption.IGNORE_CASE), "")
                         .replace(Regex("""\.\d{3,4}$"""), "")
                         .replace(Regex("""\.(mkv|mp4|avi|mov|wmv|ts|flv)$""", RegexOption.IGNORE_CASE), "")
                         .replace(Regex("""\[.*?\]"""), " ")
                         .replace(Regex("""\(.*?\)"""), " ")
                         .replace(Regex("""[\[\]\(\)\{\}\._\s-]+"""), " ")
                         .trim()
            return if (clean.isNotBlank()) clean.lowercase() else title.lowercase()
        }

        // Group items by normalized title, preserving insertion order
        val groupMap = LinkedHashMap<String, MutableList<MediaItem>>()
        fun findHistoryGroupKey(key: String): String {
            if (key.isBlank()) return key
            if (groupMap.containsKey(key)) return key
            for (existingKey in groupMap.keys) {
                if (existingKey.isNotBlank()) {
                    if (key.startsWith(existingKey) || existingKey.startsWith(key)) {
                        return existingKey
                    }
                }
            }
            return key
        }

        for (item in rawList) {
            val rawKey = normalizeTitle(item.title)
            val groupKey = findHistoryGroupKey(rawKey)
            groupMap.getOrPut(groupKey) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<MediaItem>()
        for ((_, items) in groupMap) {
            if (items.size == 1) {
                val single = items.first()
                val displayName = single.originalFileName.ifBlank { single.title }
                val cleanTitle = displayName.removePrefix("Select:").removePrefix("Select").removePrefix("📦 ").removePrefix("🗄️ ").removePrefix("📂 ").removeSuffix(" (Combined)").trim()
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
            } else {
                val mostRecent = items.first()
                val displayTitle = mostRecent.originalFileName.ifBlank { mostRecent.title }
                    .removePrefix("Select:").removePrefix("Select").removePrefix("📦 ").removePrefix("🗄️ ").removePrefix("📂 ").removeSuffix(" (Combined)").trim()
                    .replace(Regex("""[\._\s-]*(?:part|pt|cd)[\._\s-]*\d+.*$""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\.(mkv|mp4|avi|mov|wmv|ts|flv)$""", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifBlank { mostRecent.title }

                result.add(
                    MediaItem(
                        id = "history_group_${mostRecent.id}",
                        title = displayTitle,
                        posterUrl = mostRecent.posterUrl,
                        year = "Watched",
                        rating = "▶",
                        overview = "Playing stream:\n► $displayTitle",
                        type = "history_group",
                        streamUrl = mostRecent.streamUrl,
                        originalFileName = mostRecent.originalFileName,
                        groupedFiles = items
                    )
                )
            }
        }
        return result
    }

    // Show a file picker dialog for grouped history entries (uses standard showGroupPartsSelectionDialog)
    private fun showHistoryGroupFilesPicker(groupItem: MediaItem) {
        val files = groupItem.groupedFiles
        if (files.isEmpty()) return

        val parts = files.mapIndexed { index, file ->
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
        if (item.type == "channel" || item.id == "watch_history" || item.id == "settings") return false
        
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

    private fun showGroupPartsSelectionDialog(item: MediaItem, parts: List<TelegramVideoMessage>, baseName: String) {
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

        val totalSize = parts.sumOf { it.fileSize }
        val totalSizeStr = formatFileSize(totalSize)

        val subHeaderText = TextView(this).apply {
            text = "This video has ${parts.size} parts (Total: $totalSizeStr). Select an option:"
            UITheme.applyMetadataStyle(this)
            setPadding(0, 4, 0, 14)
        }
        cardList.addView(subHeaderText)

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
                val combinedMediaId = if (item.id.startsWith("group_")) item.id else "group_${parts.firstOrNull()?.chatId ?: 0}_$cleanTitle"
                CoroutineScope(Dispatchers.Main).launch {
                    val urlToPlay = if (groupInfo != null) {
                        TelegramRepository.getFreshMergedMediaUrl(groupInfo.first, cleanTitle, groupInfo.second) ?: item.streamUrl
                    } else {
                        val freshIds = parts.map { it.fileId }
                        val partSizes = parts.map { it.fileSize }
                        val groupChats = parts.map { it.chatId }
                        val groupMsgs = parts.map { it.messageId }
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
            text = "Stream continuous merged playback of all ${parts.size} parts ($totalSizeStr)"
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
                DownloadManager.startMultiPartDownload(this@MainActivity, cleanTitle, baseName, parts, item.posterUrl)
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
            text = "Combines all ${parts.size} parts into a single file ($totalSizeStr)"
            UITheme.applyMetadataStyle(this)
            setTextColor(Color.parseColor(UITheme.PRIMARY))
        }
        dlInfo.addView(dlSub)
        downloadCombinedCard.addView(dlInfo)

        cardList.addView(downloadCombinedCard)

        // Individual Parts Options
        parts.forEachIndexed { index, part ->
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
                            telegramGroupPartsCache[groupId] = parts
                            for (p in parts) {
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
                                        originalFileName = p.fileName
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

        val contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val loadingIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = android.view.View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER)
        }
        contentContainer.addView(loadingIndicator)

        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = android.view.View.GONE
        }
        val chatListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(chatListContainer)
        contentContainer.addView(scrollView)
        dialogView.addView(contentContainer)

        val currentMonitored = TdlibManager.getChannels(this).map { it.username }.toMutableSet()

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

        alertDialog.show()
        val metrics = resources.displayMetrics
        val dialogW = (metrics.widthPixels * 0.92).toInt()
        val dialogH = (metrics.heightPixels * 0.85).toInt()
        alertDialog.window?.setLayout(dialogW, dialogH)
        alertDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor(UITheme.BACKGROUND)))

        CoroutineScope(Dispatchers.IO).launch {
            val chats = TelegramRepository.getJoinedChatsInfo()
            withContext(Dispatchers.Main) {
                loadingIndicator.visibility = android.view.View.GONE
                scrollView.visibility = android.view.View.VISIBLE

                fun renderList(filterQuery: String = "") {
                    chatListContainer.removeAllViews()

                    // Render any unmatched / custom saved entries (e.g. legacy/corrupted IDs like -1008092263340) at the top so user can delete them easily
                    val unmatched = currentMonitored.filter { item ->
                        val cleanItem = item.trim()
                        val cleanNo100 = cleanItem.removePrefix("-100")
                        chats.none { chat ->
                            val cId = chat.chatId.toString()
                            cId == cleanItem || cId == cleanNo100 || "-100$cId" == cleanItem ||
                                    (chat.username != null && ("@" + chat.username).equals(cleanItem, ignoreCase = true))
                        }
                    }

                    if (unmatched.isNotEmpty() && filterQuery.isBlank()) {
                        val header = TextView(this@MainActivity).apply {
                            text = "⚠️ Custom / Legacy Saved Entries (${unmatched.size}):"
                            UITheme.applySectionTitleStyle(this)
                            textSize = 12f
                            setTextColor(Color.parseColor("#F59E0B"))
                            setPadding(0, 4, 0, 8)
                        }
                        chatListContainer.addView(header)

                        unmatched.forEach { item ->
                            val row = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                background = UITheme.createCardShape(this@MainActivity, UITheme.SURFACE, 10, UITheme.STROKE_COLOR, 1)
                                val p = UITheme.dpToPx(this@MainActivity, 8)
                                setPadding(p, p, p, p)
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                    setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 6))
                                }
                            }

                            val itemText = TextView(this@MainActivity).apply {
                                text = "Entry: $item"
                                UITheme.applyMetadataStyle(this)
                                textSize = 12f
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            row.addView(itemText)

                            val removeBtn = Button(this@MainActivity).apply {
                                text = "🗑 Remove"
                                textSize = 11f
                                background = UITheme.createBadgeDrawable(this@MainActivity, "#991B1B", 8)
                                setTextColor(Color.WHITE)
                                setOnClickListener {
                                    currentMonitored.remove(item)
                                    TdlibManager.setChannels(this@MainActivity, currentMonitored.toList())
                                    renderList(filterQuery)
                                }
                            }
                            row.addView(removeBtn)
                            chatListContainer.addView(row)
                        }
                    }

                    val filtered = if (filterQuery.isBlank()) chats else chats.filter {
                        it.title.contains(filterQuery, ignoreCase = true)
                    }

                    if (filtered.isEmpty() && unmatched.isEmpty()) {
                        val empty = TextView(this@MainActivity).apply {
                            text = "No Telegram chats found."
                            UITheme.applyMetadataStyle(this)
                            setPadding(0, 20, 0, 20)
                        }
                        chatListContainer.addView(empty)
                        return
                    }

                    filtered.forEach { chat ->
                        val chatKey = chat.chatId.toString()
                        val chatKeyNo100 = chatKey.removePrefix("-100")
                        val isChecked = currentMonitored.contains(chatKey) ||
                                currentMonitored.contains(chatKeyNo100) ||
                                currentMonitored.contains("-100$chatKey") ||
                                (chat.username != null && currentMonitored.contains("@" + chat.username))

                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            background = UITheme.createRippleCardShape(this@MainActivity, UITheme.CARD, 12, UITheme.STROKE_COLOR)
                            val p = UITheme.dpToPx(this@MainActivity, 10)
                            setPadding(p, p, p, p)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, 0, 0, UITheme.dpToPx(this@MainActivity, 6))
                            }
                        }

                        val avatarView = ImageView(this@MainActivity).apply {
                            val sz = UITheme.dpToPx(this@MainActivity, 40)
                            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                                setMargins(0, 0, UITheme.dpToPx(this@MainActivity, 10), 0)
                            }
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                        if (chat.photoFileId != null && chat.photoFileId > 0) {
                            val thumbUrl = TelegramStreamingProxy.getThumbnailUrl(chat.photoFileId)
                            com.bumptech.glide.Glide.with(this@MainActivity)
                                .load(thumbUrl)
                                .placeholder(android.R.drawable.ic_menu_gallery)
                                .error(android.R.drawable.ic_menu_gallery)
                                .circleCrop()
                                .into(avatarView)
                        } else {
                            avatarView.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                        row.addView(avatarView)

                        val infoLayout = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val titleV = TextView(this@MainActivity).apply {
                            text = chat.title
                            UITheme.applyCardTitleStyle(this)
                            textSize = 13f
                        }
                        infoLayout.addView(titleV)

                        val typeBadge = when {
                            chat.isBot -> "🤖 Bot"
                            chat.isChannel -> "📢 Channel"
                            chat.isGroup -> "👥 Group"
                            chat.isArchived -> "📦 Archived"
                            else -> "💬 Chat"
                        }
                        val subV = TextView(this@MainActivity).apply {
                            text = typeBadge
                            UITheme.applyMetadataStyle(this)
                            textSize = 11f
                        }
                        infoLayout.addView(subV)

                        row.addView(infoLayout)

                        val checkBox = CheckBox(this@MainActivity).apply {
                            this.isChecked = isChecked
                            setOnCheckedChangeListener { _, checked ->
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
                        }
                        row.addView(checkBox)

                        row.setOnClickListener {
                            checkBox.isChecked = !checkBox.isChecked
                        }

                        chatListContainer.addView(row)
                    }
                }

                renderList()

                searchInput.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        renderList(s?.toString() ?: "")
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
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
                categoryLabel.text = selectedLabel
                categoryLabel.isClickable = false
                loadInitialCinemeta(selectedCategory, selectedLabel)
            }
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
        (recyclerView.layoutManager as? GridLayoutManager)?.spanCount = if (isLandscape) 4 else 2
        mediaAdapter?.notifyDataSetChanged()
    }

    override fun onDestroy() {
        if (isFinishing && !DownloadManager.hasActiveDownloads()) {
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

    private fun downloadStreamSource(stream: StreamSource, displayTitle: String, posterUrl: String) {
        val cleanFileName = stream.fileName.removePrefix("📺 ").removePrefix("🗄️ ").removePrefix("📦 ").trim()
        val cleanDisplayTitle = displayTitle.removePrefix("📺 ").removePrefix("🗄️ ").removePrefix("📦 ").trim()

        val cachedParts = telegramGroupPartsCache[stream.id]
        if ((stream.isSplit || stream.id.startsWith("group_")) && cachedParts != null && cachedParts.isNotEmpty()) {
            DownloadManager.startMultiPartDownload(this, cleanDisplayTitle, cleanFileName, cachedParts, posterUrl)
            Toast.makeText(this, "Started multi-part download '$cleanDisplayTitle' 📥", Toast.LENGTH_SHORT).show()
            return
        }

        val fileId = extractFileIdFromUrl(stream.url)
        if (fileId != null && fileId != 0) {
            val fileName = when {
                cleanFileName.isNotBlank() && cleanFileName.contains(".") -> cleanFileName
                cleanDisplayTitle.contains(".") -> cleanDisplayTitle
                else -> "$cleanDisplayTitle.mp4"
            }
            val messageId = stream.id.split("_").getOrNull(1)?.toLongOrNull() ?: 0L
            val chatId = if (stream.chatId != 0L) stream.chatId else stream.id.split("_").getOrNull(0)?.toLongOrNull() ?: 0L
            DownloadManager.startDownload(
                context = this,
                title = cleanDisplayTitle,
                fileName = fileName,
                fileId = fileId,
                chatId = chatId,
                messageId = messageId,
                posterUrl = posterUrl
            )
            Toast.makeText(this, "Started downloading '$cleanDisplayTitle' 📥", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Unable to extract file download ID", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showGroupDownloadOptionsDialog(item: MediaItem, parts: List<TelegramVideoMessage>, cleanTitle: String) {
        val totalSize = parts.sumOf { it.fileSize }
        val totalSizeStr = formatFileSize(totalSize)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }
        val cardList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UITheme.dpToPx(this@MainActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val headerText = TextView(this).apply {
            text = "📥 Download Multi-Part Video"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        cardList.addView(headerText)

        val subHeaderText = TextView(this).apply {
            text = "'$cleanTitle' has ${parts.size} parts (Total: $totalSizeStr)."
            UITheme.applyMetadataStyle(this)
            setPadding(0, 4, 0, 14)
        }
        cardList.addView(subHeaderText)

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
                DownloadManager.startMultiPartDownload(this@MainActivity, cleanTitle, item.originalFileName.ifBlank { cleanTitle }, parts, item.posterUrl)
                Toast.makeText(this@MainActivity, "Started downloading & combining '$cleanTitle' 📥", Toast.LENGTH_SHORT).show()
            }
        }

        val icon1 = TextView(this).apply { text = "⚡"; textSize = 18f; setPadding(0, 0, 12, 0) }
        optCombine.addView(icon1)

        val info1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val t1 = TextView(this).apply { text = "Combine & Save as 1 Video File"; UITheme.applyCardTitleStyle(this); textSize = 14f; setTextColor(Color.WHITE) }
        val s1 = TextView(this).apply { text = "Downloads all ${parts.size} parts and merges into single file ($totalSizeStr)"; UITheme.applyMetadataStyle(this); setTextColor(Color.parseColor(UITheme.PRIMARY)) }
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
                parts.forEachIndexed { idx, part ->
                    val partTitle = "${cleanTitle} (Part ${idx + 1})"
                    val fileName = part.fileName.ifBlank { "${cleanTitle}_part${idx + 1}.mp4" }
                    DownloadManager.startDownload(this@MainActivity, partTitle, fileName, part.fileId, part.chatId, part.messageId, item.posterUrl, part.fileSize)
                }
                Toast.makeText(this@MainActivity, "Started downloading ${parts.size} separate parts 📥", Toast.LENGTH_SHORT).show()
            }
        }

        val icon2 = TextView(this).apply { text = "📂"; textSize = 18f; setPadding(0, 0, 12, 0) }
        optSep.addView(icon2)

        val info2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val t2 = TextView(this).apply { text = "Download All Parts Separately"; UITheme.applyCardTitleStyle(this); textSize = 14f; setTextColor(Color.WHITE) }
        val s2 = TextView(this).apply { text = "Saves ${parts.size} individual files to your Downloads folder"; UITheme.applyMetadataStyle(this); setTextColor(Color.parseColor(UITheme.TEXT_SECONDARY)) }
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

        parts.forEachIndexed { index, part ->
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
                    DownloadManager.startDownload(this@MainActivity, "${cleanTitle} - ${partTitle}", partTitle, part.fileId, part.chatId, part.messageId, item.posterUrl, part.fileSize)
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

        val cachedParts = telegramGroupPartsCache[item.id]
        val parts = if (item.groupedFiles.isNotEmpty()) {
            item.groupedFiles.map { 
                val pId = it.id.removePrefix("single_").removePrefix("stream_")
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

        val cleanTitle = item.title.removePrefix("Select:").removePrefix("Select").removePrefix("📺 ").removePrefix("🗄️ ").removePrefix("📦 ").trim()
        if (item.id.startsWith("group_") || item.type == "history_group" || parts.size > 1) {
            if (parts.isNotEmpty()) {
                showGroupPartsSelectionDialog(item, parts, cleanTitle)
                return
            }
        }

        val streamInfo = telegramStreamCache[item.id]
        val rawUrl = streamInfo?.first ?: item.streamUrl
        val fileId = extractFileIdFromUrl(rawUrl)

        if (fileId != null && fileId != 0) {
            val fileName = item.originalFileName.ifBlank { "$cleanTitle.mp4" }
            val rest = item.id.removePrefix("single_").removePrefix("stream_")
            val parts = rest.split("_")
            val chatId = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val messageId = parts.getOrNull(1)?.toLongOrNull() ?: 0L

            DownloadManager.startDownload(
                context = this,
                title = cleanTitle,
                fileName = fileName,
                fileId = fileId,
                chatId = chatId,
                messageId = messageId,
                posterUrl = item.posterUrl
            )
            Toast.makeText(this, "Started downloading '$cleanTitle' 📥", Toast.LENGTH_SHORT).show()
            return
        }

        if (item.type == "telegram_media" || item.streamUrl.contains("/stream") || item.streamUrl.contains("http") || item.id.contains("_")) {
            val fileName = item.originalFileName.ifBlank { "$cleanTitle.mp4" }
            val rest = item.id.removePrefix("single_").removePrefix("stream_")
            val parts = rest.split("_")
            val chatId = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val messageId = parts.getOrNull(1)?.toLongOrNull() ?: 0L

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
                                title = cleanTitle,
                                fileName = fileName,
                                fileId = freshId,
                                chatId = chatId,
                                messageId = messageId,
                                posterUrl = item.posterUrl
                            )
                            Toast.makeText(this@MainActivity, "Started downloading '$cleanTitle' 📥", Toast.LENGTH_SHORT).show()
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
            showStreamOptions(item.title, null, null, item.posterUrl, isDownloadMode = true)
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
                itemsListLayout.removeAllViews()
                if (downloads.isEmpty()) {
                    summaryText.text = "📥 0 Downloads • 0 B Available Offline"
                    clearAllBtn.visibility = android.view.View.GONE
                    val emptyView = TextView(context).apply {
                        text = "No active or saved downloads.\nTap 📥 on any movie card to start downloading!"
                        UITheme.applyMetadataStyle(this)
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, dp(50), 0, dp(50))
                    }
                    itemsListLayout.addView(emptyView)
                } else {
                    val completedCount = downloads.count { it.status == DownloadStatus.COMPLETED }
                    val totalBytes = downloads.sumOf { if (it.status == DownloadStatus.COMPLETED) it.downloadedBytes else 0L }
                    val totalSizeStr = formatFileSize(totalBytes)
                    summaryText.text = "📥 $completedCount/${downloads.size} Downloads • $totalSizeStr Available Offline"
                    clearAllBtn.visibility = android.view.View.VISIBLE

                    for (item in downloads) {
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { TelegramService.start(this) } catch (_: Exception) {}
        }
    }
}
