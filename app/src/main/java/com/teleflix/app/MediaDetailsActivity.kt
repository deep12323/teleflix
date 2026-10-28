package com.teleflix.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MediaDetailsActivity : AppCompatActivity() {

    private var itemId: String = ""
    private var itemTitle: String = ""
    private var itemPosterUrl: String = ""
    private var itemYear: String = ""
    private var itemRating: String = ""
    private var itemOverview: String = ""
    private var itemType: String = "movie"
    private var isSeries: Boolean = false

    private lateinit var backdropImage: ImageView
    private lateinit var titleText: TextView
    private lateinit var metaBadgesContainer: LinearLayout
    private lateinit var genresContainer: LinearLayout
    private lateinit var playButton: TextView
    private lateinit var myListButton: TextView
    private lateinit var downloadButton: TextView
    private lateinit var trailerButton: TextView
    private lateinit var storylineText: TextView
    private lateinit var episodesSection: LinearLayout
    private lateinit var seasonChipsLayout: LinearLayout
    private lateinit var episodesListLayout: LinearLayout
    private lateinit var streamsSection: LinearLayout
    private lateinit var streamsContainer: LinearLayout
    private lateinit var streamsLoadingIndicator: ProgressBar
    private lateinit var streamsStatusText: TextView

    private val seasonMap = mutableMapOf<Int, MutableList<EpisodeItem>>()
    private val episodeStreamsCache = mutableMapOf<String, List<StreamSource>>()
    private var currentSeason: Int = 1
    private var currentTrailerId: String = ""
    private var resolvedStreams = listOf<StreamSource>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        itemId = intent.getStringExtra("id") ?: ""
        itemTitle = intent.getStringExtra("title") ?: "Media Details"
        itemPosterUrl = intent.getStringExtra("posterUrl") ?: ""
        itemYear = intent.getStringExtra("year") ?: ""
        itemRating = intent.getStringExtra("rating") ?: ""
        itemOverview = intent.getStringExtra("overview") ?: ""
        itemType = intent.getStringExtra("type") ?: "movie"
        isSeries = itemType == "series" || itemType == "tv"

        buildLayout()
        populateInitialData()
        fetchFullMetadataFromCinemeta()
        if (!isSeries) {
            resolveStreamsForCurrent()
        }
    }

    private fun dp(v: Int): Int = UITheme.dpToPx(this, v)

    private fun buildLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0B0F"))
            fitsSystemWindows = true
        }

        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, dp(40))
        }

        // ── 1. Hero Backdrop (340dp) ──
        val heroFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(340)
            )
        }

        backdropImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#141414"))
        }
        heroFrame.addView(backdropImage)

        // Gradient overlay fading seamlessly into background
        val gradient = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#40000000"),
                    Color.TRANSPARENT,
                    Color.parseColor("#800B0B0F"),
                    Color.parseColor("#E60B0B0F"),
                    Color.parseColor("#FF0B0B0F")
                )
            )
        }
        heroFrame.addView(gradient)

        // Top Navigation Bar (Back & Bookmark)
        val topNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(14)
            setPadding(pad, pad, pad, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createBadgeDrawable(this@MediaDetailsActivity, "#80000000", 20)
            val s = dp(38)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { finish() }
        }
        topNav.addView(backBtn)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        topNav.addView(spacer)

        val bookmarkTopBtn = TextView(this).apply {
            text = if (LibraryManager.isBookmarked(this@MediaDetailsActivity, itemId)) "★" else "☆"
            textSize = 18f
            setTextColor(if (LibraryManager.isBookmarked(this@MediaDetailsActivity, itemId)) Color.parseColor("#E5A00D") else Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createBadgeDrawable(this@MediaDetailsActivity, "#80000000", 20)
            val s = dp(38)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener {
                val item = MediaItem(
                    id = itemId,
                    title = itemTitle,
                    posterUrl = itemPosterUrl,
                    year = itemYear,
                    rating = itemRating,
                    overview = itemOverview,
                    type = itemType
                )
                val bookmarked = LibraryManager.toggleBookmark(this@MediaDetailsActivity, item)
                text = if (bookmarked) "★" else "☆"
                setTextColor(if (bookmarked) Color.parseColor("#E5A00D") else Color.WHITE)
                updateMyListButtonState(bookmarked)
                val msg = if (bookmarked) "Added to My List 📚" else "Removed from My List"
                Toast.makeText(this@MediaDetailsActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        topNav.addView(bookmarkTopBtn)

        heroFrame.addView(topNav)

        // Hero Bottom Title and Badges Container
        val heroInfoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, 0, pad, dp(6))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        }

        titleText = TextView(this).apply {
            text = itemTitle
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setShadowLayer(dp(4).toFloat(), 0f, dp(2).toFloat(), Color.BLACK)
        }
        heroInfoBox.addView(titleText)

        metaBadgesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        heroInfoBox.addView(metaBadgesContainer)

        heroFrame.addView(heroInfoBox)
        content.addView(heroFrame)

        // ── 2. Primary Actions (Play, My List, Download, Trailer) ──
        val actionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(16)
            setPadding(padH, dp(12), padH, 0)
        }

        playButton = TextView(this).apply {
            text = "▶   Play Now"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@MediaDetailsActivity, UITheme.PRIMARY, 10, UITheme.PRIMARY, 1)
            val pV = dp(12)
            setPadding(0, pV, 0, pV)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                handlePrimaryPlay()
            }
        }
        actionsContainer.addView(playButton)

        val secondaryActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
        }

        myListButton = TextView(this).apply {
            val inList = LibraryManager.isBookmarked(this@MediaDetailsActivity, itemId)
            text = if (inList) "✓  In List" else "+  My List"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (inList) Color.parseColor(UITheme.SUCCESS) else Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@MediaDetailsActivity, "#1A1D27", 8, "#283042", 1)
            val pV = dp(10)
            setPadding(0, pV, 0, pV)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val item = MediaItem(
                    id = itemId,
                    title = itemTitle,
                    posterUrl = itemPosterUrl,
                    year = itemYear,
                    rating = itemRating,
                    overview = itemOverview,
                    type = itemType
                )
                val bookmarked = LibraryManager.toggleBookmark(this@MediaDetailsActivity, item)
                updateMyListButtonState(bookmarked)
                bookmarkTopBtn.text = if (bookmarked) "★" else "☆"
                bookmarkTopBtn.setTextColor(if (bookmarked) Color.parseColor("#E5A00D") else Color.WHITE)
                val msg = if (bookmarked) "Added to My List 📚" else "Removed from My List"
                Toast.makeText(this@MediaDetailsActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        secondaryActionsRow.addView(myListButton)

        downloadButton = TextView(this).apply {
            text = "⬇  Download"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@MediaDetailsActivity, "#1A1D27", 8, "#283042", 1)
            val pV = dp(10)
            setPadding(0, pV, 0, pV)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            visibility = View.GONE
            setOnClickListener {
                handlePrimaryDownload()
            }
        }
        secondaryActionsRow.addView(downloadButton)

        trailerButton = TextView(this).apply {
            text = "🎬  Trailer"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#9CA3AF"))
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@MediaDetailsActivity, "#1A1D27", 8, "#283042", 1)
            val pV = dp(10)
            setPadding(0, pV, 0, pV)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            visibility = View.GONE
            setOnClickListener {
                if (currentTrailerId.isNotBlank()) {
                    try {
                        val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$currentTrailerId"))
                        startActivity(ytIntent)
                    } catch (_: Exception) {
                        Toast.makeText(this@MediaDetailsActivity, "Could not open trailer", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        secondaryActionsRow.addView(trailerButton)

        actionsContainer.addView(secondaryActionsRow)
        updateActionButtonsLayout()
        content.addView(actionsContainer)

        // ── 3. Storyline / Overview & Genres ──
        val storyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(16)
            setPadding(padH, dp(18), padH, 0)
        }

        genresContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        storyContainer.addView(genresContainer)

        val storyHeader = TextView(this).apply {
            text = "Storyline"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        storyContainer.addView(storyHeader)

        storylineText = TextView(this).apply {
            text = if (itemOverview.isNotBlank()) itemOverview else "Loading synopsis..."
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1.1f)
        }
        storyContainer.addView(storylineText)
        content.addView(storyContainer)

        // ── 4. TV Series Episodes Section (Only for Series) ──
        episodesSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(16)
            setPadding(padH, dp(22), padH, 0)
            visibility = if (isSeries) View.VISIBLE else View.GONE
        }

        val epHeader = TextView(this).apply {
            text = "Episodes"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        episodesSection.addView(epHeader)

        val seasonScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(12))
        }
        seasonChipsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        seasonScroll.addView(seasonChipsLayout)
        episodesSection.addView(seasonScroll)

        episodesListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        episodesSection.addView(episodesListLayout)
        content.addView(episodesSection)

        // ── 5. Available Streams Section (Telegram Channels) ──
        streamsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(16)
            setPadding(padH, dp(24), padH, 0)
            visibility = if (isSeries) View.GONE else View.VISIBLE
        }

        val streamsTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val streamsHeader = TextView(this).apply {
            text = "Available Streams (Telegram)"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        streamsTitleRow.addView(streamsHeader)

        val refreshStreamsBtn = TextView(this).apply {
            text = "🔄 Refresh"
            setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                resolveStreamsForCurrent(forceRefresh = true)
            }
        }
        streamsTitleRow.addView(refreshStreamsBtn)
        streamsSection.addView(streamsTitleRow)

        val loadingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }

        streamsLoadingIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        loadingRow.addView(streamsLoadingIndicator)

        streamsStatusText = TextView(this).apply {
            text = "Searching monitored Telegram channels & chats..."
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
        }
        loadingRow.addView(streamsStatusText)
        streamsSection.addView(loadingRow)

        streamsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        streamsSection.addView(streamsContainer)
        content.addView(streamsSection)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun updateMyListButtonState(isBookmarked: Boolean) {
        myListButton.text = if (isBookmarked) "✓  In List" else "+  My List"
        myListButton.setTextColor(if (isBookmarked) Color.parseColor(UITheme.SUCCESS) else Color.WHITE)
    }

    private fun updateActionButtonsLayout() {
        playButton.visibility = View.GONE
        downloadButton.visibility = View.GONE

        val lpMyList = (myListButton.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        if (trailerButton.visibility == View.VISIBLE) {
            lpMyList.setMargins(0, 0, dp(8), 0)
        } else {
            lpMyList.setMargins(0, 0, 0, 0)
        }
        myListButton.layoutParams = lpMyList
    }

    private fun populateInitialData() {
        // Load Poster or Backdrop into Hero Image
        if (itemPosterUrl.isNotBlank()) {
            Glide.with(this)
                .load(itemPosterUrl)
                .centerCrop()
                .into(backdropImage)
        }

        renderMetadataBadges(itemYear, itemRating, if (isSeries) "Series" else "Movie")
    }

    private fun renderMetadataBadges(year: String, rating: String, typeTag: String, runtime: String = "") {
        metaBadgesContainer.removeAllViews()

        fun addBadge(text: String, isWarning: Boolean = false, isAccent: Boolean = false) {
            val badge = TextView(this).apply {
                this.text = text
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                val textColor = when {
                    isWarning -> Color.parseColor(UITheme.WARNING)
                    isAccent -> Color.parseColor(UITheme.ACCENT_BLUE)
                    else -> Color.parseColor("#E5E7EB")
                }
                setTextColor(textColor)
                background = UITheme.createBadgeDrawable(this@MediaDetailsActivity, "#801E222D", 5)
                val pV = dp(3)
                val pH = dp(6)
                setPadding(pH, pV, pH, pV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, dp(8), 0)
                }
            }
            metaBadgesContainer.addView(badge)
        }

        if (year.isNotBlank()) addBadge(year)
        if (rating.isNotBlank() && rating != "—") addBadge("⭐ $rating", isWarning = true)
        addBadge(typeTag, isAccent = true)
        if (runtime.isNotBlank()) addBadge(runtime)
        addBadge("4K Ultra HD")
    }

    private fun fetchFullMetadataFromCinemeta() {
        if (itemId.isBlank()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cat = if (isSeries) "series" else "movie"
                val url = URL("https://v3-cinemeta.strem.io/meta/$cat/$itemId.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val text = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val meta = json.optJSONObject("meta") ?: return@launch

                val bgUrl = meta.optString("background").takeIf { it.isNotBlank() } ?: itemPosterUrl
                val desc = meta.optString("description").takeIf { it.isNotBlank() } ?: itemOverview
                val year = meta.optString("year").takeIf { it.isNotBlank() } ?: itemYear
                val rating = meta.optString("imdbRating").takeIf { it.isNotBlank() } ?: itemRating
                val runtime = meta.optString("runtime", "")

                val genresList = mutableListOf<String>()
                val genresArr = meta.optJSONArray("genres")
                if (genresArr != null) {
                    for (i in 0 until genresArr.length()) {
                        genresList.add(genresArr.getString(i))
                    }
                }

                val trailersArr = meta.optJSONArray("trailers")
                var trailerId = ""
                if (trailersArr != null && trailersArr.length() > 0) {
                    val trObj = trailersArr.getJSONObject(0)
                    trailerId = trObj.optString("source", "")
                }

                // Parse TV Series Episodes
                val videosArr = meta.optJSONArray("videos")
                val seasons = mutableMapOf<Int, MutableList<EpisodeItem>>()
                if (videosArr != null) {
                    for (i in 0 until videosArr.length()) {
                        val vObj = videosArr.getJSONObject(i)
                        val s = vObj.optInt("season", 1)
                        val epNum = vObj.optInt("episode", vObj.optInt("number", 1))
                        val epName = vObj.optString("name", "Episode $epNum")
                        val epOverview = vObj.optString("overview", vObj.optString("description", ""))
                        val epThumb = vObj.optString("thumbnail", "")

                        val ep = EpisodeItem(
                            season = s,
                            episode = epNum,
                            title = epName,
                            overview = epOverview,
                            released = "",
                            thumbnail = epThumb
                        )
                        seasons.getOrPut(s) { mutableListOf() }.add(ep)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (bgUrl.isNotBlank()) {
                        Glide.with(this@MediaDetailsActivity)
                            .load(bgUrl)
                            .centerCrop()
                            .into(backdropImage)
                    }

                    if (desc.isNotBlank()) {
                        storylineText.text = desc
                    }

                    renderMetadataBadges(year, rating, if (isSeries) "Series" else "Movie", runtime)

                    // Render Genres Chips
                    genresContainer.removeAllViews()
                    for (g in genresList) {
                        val gPill = TextView(this@MediaDetailsActivity).apply {
                            this.text = g
                            textSize = 11f
                            setTextColor(Color.parseColor("#9CA3AF"))
                            background = UITheme.createCardShape(this@MediaDetailsActivity, "#141720", 12, "#242B3B", 1)
                            val pV = dp(3)
                            val pH = dp(8)
                            setPadding(pH, pV, pH, pV)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { setMargins(0, 0, dp(6), 0) }
                        }
                        genresContainer.addView(gPill)
                    }

                    // Trailer Button
                    if (trailerId.isNotBlank()) {
                        currentTrailerId = trailerId
                        trailerButton.visibility = View.VISIBLE
                        updateActionButtonsLayout()
                    }

                    // Populate Seasons & Episodes
                    if (seasons.isNotEmpty()) {
                        seasonMap.clear()
                        seasonMap.putAll(seasons)
                        renderSeasonChips()
                        val firstAvailableSeason = seasons.keys.filter { it > 0 }.minOrNull() ?: seasons.keys.firstOrNull() ?: 1
                        displayEpisodesForSeason(firstAvailableSeason)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun renderSeasonChips() {
        seasonChipsLayout.removeAllViews()
        val sortedSeasons = seasonMap.keys.sorted().filter { it > 0 }
        if (sortedSeasons.isEmpty()) return

        for (s in sortedSeasons) {
            val chip = TextView(this).apply {
                text = "Season $s"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                val isSelected = s == currentSeason
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#9CA3AF"))
                background = if (isSelected) {
                    UITheme.createBadgeDrawable(this@MediaDetailsActivity, UITheme.PRIMARY, 14)
                } else {
                    UITheme.createCardShape(this@MediaDetailsActivity, "#161922", 14, "#262D3D", 1)
                }
                val pV = dp(6)
                val pH = dp(14)
                setPadding(pH, pV, pH, pV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8), 0) }
                setOnClickListener {
                    currentSeason = s
                    renderSeasonChips()
                    displayEpisodesForSeason(s)
                }
            }
            seasonChipsLayout.addView(chip)
        }
    }

    private fun displayEpisodesForSeason(season: Int) {
        episodesListLayout.removeAllViews()
        val epList = seasonMap[season]?.sortedBy { it.episode } ?: return

        for (ep in epList) {
            val isFirstEpisode = ep.episode == (epList.firstOrNull()?.episode ?: 1)

            // Outer Episode Block Container
            val episodeBlock = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = UITheme.createCardShape(this@MediaDetailsActivity, "#12151F", 12, "#1E2536", 1)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(12)) }
            }

            // Episode Header Row (Thumbnail + Details + Expand Pill)
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = dp(10)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isClickable = true
                isFocusable = true
            }

            // Episode Thumbnail (16:9)
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                val w = dp(100)
                val h = dp(56)
                layoutParams = LinearLayout.LayoutParams(w, h).apply {
                    setMargins(0, 0, dp(12), 0)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1B202D"))
                    cornerRadius = dp(8).toFloat()
                }
                clipToOutline = true
            }

            val thumbUrl = if (ep.thumbnail.isNotBlank()) ep.thumbnail else itemPosterUrl
            if (thumbUrl.isNotBlank()) {
                Glide.with(this)
                    .load(thumbUrl)
                    .transform(CenterCrop(), RoundedCorners(dp(8)))
                    .placeholder(android.R.drawable.ic_media_play)
                    .into(thumb)
            } else {
                thumb.setImageResource(android.R.drawable.ic_media_play)
            }
            headerRow.addView(thumb)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val epTitle = TextView(this).apply {
                text = "${ep.episode}. ${ep.title}"
                setTextColor(Color.WHITE)
                textSize = 13.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textCol.addView(epTitle)

            val epPlot = TextView(this).apply {
                text = if (ep.overview.isNotBlank()) ep.overview else "Season $season Episode ${ep.episode}"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 11.5f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            }
            textCol.addView(epPlot)

            headerRow.addView(textCol)

            // Streams Expand / Collapse Pill Indicator
            val indicatorBadge = TextView(this).apply {
                text = "Streams ▼"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#9CA3AF"))
                background = UITheme.createCardShape(this@MediaDetailsActivity, "#1A2234", 12, "#2B3650", 1)
                val pV = dp(5)
                val pH = dp(9)
                setPadding(pH, pV, pH, pV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(8), 0, 0, 0)
                }
            }
            headerRow.addView(indicatorBadge)

            episodeBlock.addView(headerRow)

            // Subtle divider line
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
                setBackgroundColor(Color.parseColor("#1C2333"))
                visibility = View.GONE
            }
            episodeBlock.addView(divider)

            // Inline Streams Layout (Shown below the episode)
            val inlineStreamsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(10)
                setPadding(pad, dp(8), pad, pad)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0C0E15"))
                    cornerRadii = floatArrayOf(
                        0f, 0f, 0f, 0f,
                        dp(12).toFloat(), dp(12).toFloat(),
                        dp(12).toFloat(), dp(12).toFloat()
                    )
                }
                visibility = View.GONE
            }
            episodeBlock.addView(inlineStreamsLayout)

            // Toggle Expand / Collapse on Episode Header click
            headerRow.setOnClickListener {
                val isExpanded = inlineStreamsLayout.visibility == View.VISIBLE
                if (isExpanded) {
                    inlineStreamsLayout.visibility = View.GONE
                    divider.visibility = View.GONE
                    indicatorBadge.text = "Streams ▼"
                    indicatorBadge.setTextColor(Color.parseColor("#9CA3AF"))
                } else {
                    inlineStreamsLayout.visibility = View.VISIBLE
                    divider.visibility = View.VISIBLE
                    indicatorBadge.text = "Streams ▲"
                    indicatorBadge.setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
                    loadStreamsForEpisode(ep, inlineStreamsLayout, indicatorBadge)
                }
            }

            episodesListLayout.addView(episodeBlock)
        }
    }

    private fun loadStreamsForEpisode(
        ep: EpisodeItem,
        container: LinearLayout,
        indicatorBadge: TextView? = null,
        forceRefresh: Boolean = false
    ) {
        val cacheKey = "${ep.season}_${ep.episode}"
        if (!forceRefresh && episodeStreamsCache.containsKey(cacheKey)) {
            val cached = episodeStreamsCache[cacheKey] ?: emptyList()
            renderInlineEpisodeStreams(ep, cached, container, indicatorBadge)
            return
        }

        container.removeAllViews()

        // Inline Loading Spinner
        val loadingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }

        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        loadingRow.addView(spinner)

        val loadingText = TextView(this).apply {
            text = "Searching Telegram channels for S${ep.season}E${ep.episode}..."
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
        }
        loadingRow.addView(loadingText)
        container.addView(loadingRow)

        val yearInt = itemYear.filter { it.isDigit() }.take(4).toIntOrNull()

        lifecycleScope.launch(Dispatchers.IO) {
            val streams = try {
                TdlibManager.resolveStreams(itemTitle, ep.season, ep.episode, yearInt, forceRefresh)
            } catch (e: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                episodeStreamsCache[cacheKey] = streams
                renderInlineEpisodeStreams(ep, streams, container, indicatorBadge)
            }
        }
    }

    private fun renderInlineEpisodeStreams(
        ep: EpisodeItem,
        streams: List<StreamSource>,
        container: LinearLayout,
        indicatorBadge: TextView? = null
    ) {
        container.removeAllViews()

        if (indicatorBadge != null) {
            if (streams.isNotEmpty()) {
                indicatorBadge.text = "${streams.size} Streams ▲"
                indicatorBadge.setTextColor(Color.parseColor(UITheme.SUCCESS))
            } else {
                indicatorBadge.text = "0 Streams ▲"
                indicatorBadge.setTextColor(Color.parseColor("#9CA3AF"))
            }
        }

        // Header Row inside inline container: Title + Refresh Button
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }

        val headerText = TextView(this).apply {
            text = if (streams.isNotEmpty()) {
                "Available Streams (${streams.size})"
            } else {
                "No Telegram streams found"
            }
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(headerText)

        val refreshBtn = TextView(this).apply {
            text = "🔄 Refresh"
            setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
            textSize = 11.5f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener {
                loadStreamsForEpisode(ep, container, indicatorBadge, forceRefresh = true)
            }
        }
        headerRow.addView(refreshBtn)
        container.addView(headerRow)

        if (streams.isEmpty()) {
            val emptyBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = UITheme.createCardShape(this@MediaDetailsActivity, "#10131D", 8, "#1C2232", 1)
                val pad = dp(14)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val emptyMsg = TextView(this).apply {
                text = "No direct stream found for Season ${ep.season} Episode ${ep.episode}."
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 12f
                gravity = Gravity.CENTER
            }
            emptyBox.addView(emptyMsg)

            val emptySub = TextView(this).apply {
                text = "Ensure this episode file is uploaded or forwarded into your connected Telegram channels."
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }
            emptyBox.addView(emptySub)

            container.addView(emptyBox)
            return
        }

        for (st in streams) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = UITheme.createCardShape(this@MediaDetailsActivity, "#151824", 10, "#22293A", 1)
                val pad = dp(10)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(8)) }
            }

            // Top meta line: Quality + Type (Split/ZIP) + Audio (Multi/Hi/Eng) + Size
            val metaRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val qualityBadge = TextView(this).apply {
                text = st.quality.ifBlank { "HD" }
                setTextColor(Color.parseColor(UITheme.PRIMARY))
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = UITheme.createBadgeDrawable(this@MediaDetailsActivity, "#33E50914", 4)
                setPadding(dp(5), dp(2), dp(5), dp(2))
            }
            metaRow.addView(qualityBadge)

            if (st.isSplit) {
                val splitBadge = TextView(this).apply {
                    text = "📦 Split Video"
                    setTextColor(Color.parseColor("#C084FC"))
                    textSize = 9.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = UITheme.createCardShape(this@MediaDetailsActivity, "#261542", 4, "#7C3AED", 1)
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(5), 0, 0, 0) }
                }
                metaRow.addView(splitBadge)
            } else if (st.isZip) {
                val zipBadge = TextView(this).apply {
                    text = "🗄️ ZIP Archive"
                    setTextColor(Color.parseColor("#FBBF24"))
                    textSize = 9.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = UITheme.createCardShape(this@MediaDetailsActivity, "#332500", 4, "#D97706", 1)
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(5), 0, 0, 0) }
                }
                metaRow.addView(zipBadge)
            }

            val audio = st.audioTag.ifBlank { TdlibManager.extractAudioTags(st.rawName.ifBlank { st.fileName }) }
            if (audio.isNotBlank()) {
                val audioBadge = TextView(this).apply {
                    text = audio
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 9.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = UITheme.createCardShape(this@MediaDetailsActivity, "#0E2A38", 4, "#0284C7", 1)
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(5), 0, 0, 0) }
                }
                metaRow.addView(audioBadge)
            }

            val sizeBadge = TextView(this).apply {
                text = st.size
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 11f
                setPadding(dp(7), 0, 0, 0)
            }
            metaRow.addView(sizeBadge)

            card.addView(metaRow)

            // File Name
            val fileNameView = TextView(this).apply {
                text = st.fileName
                setTextColor(Color.parseColor("#F1F5F9"))
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, dp(8))
            }
            card.addView(fileNameView)

            // Action Buttons Row: Play & Download
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val epDisplayTitle = "$itemTitle S${String.format("%02d", ep.season)}E${String.format("%02d", ep.episode)} - ${ep.title}"

            card.setOnClickListener {
                handleStreamPlay(st, epDisplayTitle, if (ep.thumbnail.isNotBlank()) ep.thumbnail else itemPosterUrl)
            }

            val playBtn = TextView(this).apply {
                text = "▶  Play Episode"
                setTextColor(Color.WHITE)
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                background = UITheme.createCardShape(this@MediaDetailsActivity, UITheme.PRIMARY, 6, UITheme.PRIMARY, 1)
                val pV = dp(7)
                setPadding(0, pV, 0, pV)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(6), 0)
                }
                setOnClickListener {
                    handleStreamPlay(st, epDisplayTitle, if (ep.thumbnail.isNotBlank()) ep.thumbnail else itemPosterUrl)
                }
            }
            btnRow.addView(playBtn)

            val dlBtn = TextView(this).apply {
                text = "⬇  Download"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                background = UITheme.createCardShape(this@MediaDetailsActivity, "#1C202E", 6, "#2E364A", 1)
                val pV = dp(7)
                setPadding(0, pV, 0, pV)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    handleStreamDownload(st, epDisplayTitle, if (ep.thumbnail.isNotBlank()) ep.thumbnail else itemPosterUrl)
                }
            }
            btnRow.addView(dlBtn)

            card.addView(btnRow)
            container.addView(card)
        }
    }

    private fun resolveStreamsForCurrent(forceRefresh: Boolean = false) {
        if (isSeries) return
        streamsLoadingIndicator.visibility = View.VISIBLE
        streamsStatusText.text = "Querying connected Telegram channels & chats..."

        val yearInt = itemYear.filter { it.isDigit() }.take(4).toIntOrNull()
        val s = if (isSeries) currentSeason else null
        val ep = if (isSeries) 1 else null

        lifecycleScope.launch(Dispatchers.IO) {
            val streams = try {
                TdlibManager.resolveStreams(itemTitle, s, ep, yearInt, forceRefresh)
            } catch (e: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                streamsLoadingIndicator.visibility = View.GONE
                resolvedStreams = streams

                if (streams.isNotEmpty()) {
                    streamsStatusText.text = "${streams.size} streams found in your Telegram channels"
                    renderStreamsList(streams)
                } else {
                    streamsStatusText.text = "No direct stream found yet in monitored channels."
                    streamsContainer.removeAllViews()
                }
            }
        }
    }

    private fun renderStreamsList(streams: List<StreamSource>) {
        streamsContainer.removeAllViews()

        for (st in streams) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = UITheme.createCardShape(this@MediaDetailsActivity, "#12151F", 12, "#1E2536", 1)
                val pad = dp(12)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(8)) }
            }

            // Top line: Quality + Type (Split/ZIP) + Audio (Multi/Hi/Eng) + Size
            val metaRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val qualityBadge = TextView(this).apply {
                text = st.quality.ifBlank { "HD" }
                setTextColor(Color.parseColor(UITheme.PRIMARY))
                textSize = 10.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = UITheme.createBadgeDrawable(this@MediaDetailsActivity, "#33E50914", 4)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            metaRow.addView(qualityBadge)

            if (st.isSplit) {
                val splitBadge = TextView(this).apply {
                    text = "📦 Split Video"
                    setTextColor(Color.parseColor("#C084FC"))
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = UITheme.createCardShape(this@MediaDetailsActivity, "#261542", 4, "#7C3AED", 1)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(6), 0, 0, 0) }
                }
                metaRow.addView(splitBadge)
            } else if (st.isZip) {
                val zipBadge = TextView(this).apply {
                    text = "🗄️ ZIP Archive"
                    setTextColor(Color.parseColor("#FBBF24"))
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = UITheme.createCardShape(this@MediaDetailsActivity, "#332500", 4, "#D97706", 1)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(6), 0, 0, 0) }
                }
                metaRow.addView(zipBadge)
            }

            val audio = st.audioTag.ifBlank { TdlibManager.extractAudioTags(st.rawName.ifBlank { st.fileName }) }
            if (audio.isNotBlank()) {
                val audioBadge = TextView(this).apply {
                    text = audio
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = UITheme.createCardShape(this@MediaDetailsActivity, "#0E2A38", 4, "#0284C7", 1)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(6), 0, 0, 0) }
                }
                metaRow.addView(audioBadge)
            }

            val sizeBadge = TextView(this).apply {
                text = st.size
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 11f
                setPadding(dp(8), 0, 0, 0)
            }
            metaRow.addView(sizeBadge)

            card.addView(metaRow)

            // File Name
            val fileNameView = TextView(this).apply {
                text = st.fileName
                setTextColor(Color.WHITE)
                textSize = 12.5f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, dp(10))
            }
            card.addView(fileNameView)

            // Buttons row: Play & Download
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            card.setOnClickListener {
                handleStreamPlay(st, itemTitle, itemPosterUrl)
            }

            val playStreamBtn = TextView(this).apply {
                text = "▶  Play Stream"
                setTextColor(Color.WHITE)
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                background = UITheme.createCardShape(this@MediaDetailsActivity, UITheme.PRIMARY, 6, UITheme.PRIMARY, 1)
                val pV = dp(7)
                setPadding(0, pV, 0, pV)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(6), 0)
                }
                setOnClickListener {
                    handleStreamPlay(st, itemTitle, itemPosterUrl)
                }
            }
            btnRow.addView(playStreamBtn)

            val dlStreamBtn = TextView(this).apply {
                text = "⬇  Download"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                background = UITheme.createCardShape(this@MediaDetailsActivity, "#1C202E", 6, "#2E364A", 1)
                val pV = dp(7)
                setPadding(0, pV, 0, pV)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    handleStreamDownload(st, itemTitle, itemPosterUrl)
                }
            }
            btnRow.addView(dlStreamBtn)

            card.addView(btnRow)
            streamsContainer.addView(card)
        }
    }

    private fun handleStreamPlay(
        st: StreamSource,
        fallbackTitle: String = itemTitle,
        fallbackPoster: String = itemPosterUrl
    ) {
        val isZip = st.isZip || 
                    st.id.startsWith("zip_") || 
                    st.fileName.endsWith(".zip", ignoreCase = true) || 
                    st.rawName.endsWith(".zip", ignoreCase = true) || 
                    TelegramRepository.isZipArchiveFilename(st.fileName) || 
                    TelegramRepository.isZipArchiveFilename(st.rawName)

        if (!isZip && st.isSplit) {
            val cachedParts = TelegramRepository.groupPartsCache[st.id]
            if (cachedParts != null && cachedParts.size > 1) {
                val hasZipPart = cachedParts.any { TelegramRepository.isZipArchiveFilename(it.fileName) || it.fileName.endsWith(".zip", ignoreCase = true) }
                if (!hasZipPart) {
                    val cleanBase = st.rawName.ifBlank { st.fileName }.removePrefix("📦 ").removePrefix("🗄️ ").trim()
                    PlayerHelper.showGroupPartsSelectionDialog(
                        this,
                        st.id,
                        st.url,
                        st.fileName.ifBlank { fallbackTitle },
                        fallbackPoster,
                        cleanBase,
                        cachedParts
                    )
                    return
                }
            }
        }
        PlayerHelper.checkResumeAndSelectPlayer(
            this,
            st.url,
            st.fileName.ifBlank { fallbackTitle },
            fallbackPoster,
            st.id,
            st.fileName,
            skipSplitCheck = isZip
        )
    }

    private fun handleStreamDownload(
        st: StreamSource,
        fallbackTitle: String = itemTitle,
        fallbackPoster: String = itemPosterUrl
    ) {
        val isZip = st.isZip || 
                    st.id.startsWith("zip_") || 
                    st.fileName.endsWith(".zip", ignoreCase = true) || 
                    st.rawName.endsWith(".zip", ignoreCase = true) || 
                    TelegramRepository.isZipArchiveFilename(st.fileName) || 
                    TelegramRepository.isZipArchiveFilename(st.rawName)

        if (!isZip && st.isSplit) {
            val cachedParts = TelegramRepository.groupPartsCache[st.id]
            if (cachedParts != null && cachedParts.size > 1) {
                val hasZipPart = cachedParts.any { TelegramRepository.isZipArchiveFilename(it.fileName) || it.fileName.endsWith(".zip", ignoreCase = true) }
                if (!hasZipPart) {
                    val cleanBase = st.rawName.ifBlank { st.fileName }.removePrefix("📦 ").removePrefix("🗄️ ").trim()
                    PlayerHelper.showGroupDownloadOptionsDialog(
                        this,
                        st.id,
                        cleanBase,
                        st.fileName,
                        fallbackPoster,
                        cachedParts,
                        isZip = false
                    )
                    return
                }
            }
        }
        val fileId = st.url.substringAfter("/file/").substringBefore("/").substringBefore("?").toIntOrNull() ?: 0
        DownloadManager.startDownload(
            this,
            st.fileName.ifBlank { fallbackTitle },
            st.fileName,
            fileId,
            st.chatId,
            0L,
            fallbackPoster,
            0L
        )
        Toast.makeText(this, "Download initiated 📥", Toast.LENGTH_SHORT).show()
    }

    private fun handlePrimaryPlay() {
        if (resolvedStreams.isNotEmpty()) {
            val topStream = resolvedStreams.first()
            handleStreamPlay(topStream, itemTitle, itemPosterUrl)
            return
        }

        // On-demand search and play
        Toast.makeText(this, "Searching streams for '$itemTitle'...", Toast.LENGTH_SHORT).show()
        val yearInt = itemYear.filter { it.isDigit() }.take(4).toIntOrNull()
        val s = if (isSeries) currentSeason else null
        val ep = if (isSeries) 1 else null

        lifecycleScope.launch(Dispatchers.IO) {
            val streams = try {
                TdlibManager.resolveStreams(itemTitle, s, ep, yearInt)
            } catch (_: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                if (streams.isNotEmpty()) {
                    resolvedStreams = streams
                    renderStreamsList(streams)
                    val best = streams.first()
                    handleStreamPlay(best, itemTitle, itemPosterUrl)
                } else {
                    AlertDialog.Builder(this@MediaDetailsActivity)
                        .setTitle("No Streams Found")
                        .setMessage("No matching streams found for '$itemTitle' in connected Telegram channels.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun handlePrimaryDownload() {
        if (resolvedStreams.isNotEmpty()) {
            val topStream = resolvedStreams.first()
            handleStreamDownload(topStream, itemTitle, itemPosterUrl)
        } else {
            Toast.makeText(this, "Please select an available stream below to download", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playEpisode(ep: EpisodeItem) {
        val epDisplayTitle = "$itemTitle S${String.format("%02d", ep.season)}E${String.format("%02d", ep.episode)}"
        Toast.makeText(this, "Finding streams for $epDisplayTitle...", Toast.LENGTH_SHORT).show()
        val yearInt = itemYear.filter { it.isDigit() }.take(4).toIntOrNull()

        lifecycleScope.launch(Dispatchers.IO) {
            val streams = try {
                TdlibManager.resolveStreams(itemTitle, ep.season, ep.episode, yearInt)
            } catch (_: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                if (streams.isNotEmpty()) {
                    renderStreamsList(streams)
                    val best = streams.first()
                    PlayerHelper.checkResumeAndSelectPlayer(
                        this@MediaDetailsActivity,
                        best.url,
                        best.fileName.ifBlank { epDisplayTitle },
                        ep.thumbnail.ifBlank { itemPosterUrl },
                        best.id,
                        best.fileName
                    )
                } else {
                    AlertDialog.Builder(this@MediaDetailsActivity)
                        .setTitle("No Streams Found")
                        .setMessage("No matching stream found for $epDisplayTitle in your Telegram channels.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }
}
