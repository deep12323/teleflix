package com.teleflix.app

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners

class MediaAdapter(
    private val items: List<MediaItem>,
    private val onClick: (MediaItem) -> Unit,
    private val onLongClick: ((MediaItem) -> Boolean)? = null,
    private val onBookmarkToggle: ((MediaItem, Boolean) -> Unit)? = null,
    private val onDownloadClick: ((MediaItem) -> Unit)? = null,
    private val onSeeAllClick: ((title: String, catalogTarget: String) -> Unit)? = null
) : RecyclerView.Adapter<MediaAdapter.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HERO_BILLBOARD = 0
        const val VIEW_TYPE_CHANNEL = 1
        const val VIEW_TYPE_TELEGRAM_MEDIA = 2
        const val VIEW_TYPE_DEFAULT = 3
        const val VIEW_TYPE_HORIZONTAL_ROW = 4
    }

    private val sharedViewPool = RecyclerView.RecycledViewPool()

    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        return when (item.type) {
            "hero_billboard" -> VIEW_TYPE_HERO_BILLBOARD
            "horizontal_row" -> VIEW_TYPE_HORIZONTAL_ROW
            "channel" -> VIEW_TYPE_CHANNEL
            "telegram_media", "history_group", "topic" -> VIEW_TYPE_TELEGRAM_MEDIA
            else -> VIEW_TYPE_DEFAULT
        }
    }

    class ViewHolder(
        val layout: View,
        val posterView: ImageView? = null,
        val titleText: TextView,
        val yearText: TextView? = null,
        val overviewText: TextView? = null,
        val iconText: TextView? = null,
        val bookmarkButton: ImageView? = null,
        val mylistTextButton: TextView? = null,
        val downloadButton: TextView? = null,
        val playButton: TextView? = null,
        val topBadge: TextView? = null,
        val rowRecyclerView: RecyclerView? = null,
        val seeAllButton: TextView? = null
    ) : RecyclerView.ViewHolder(layout)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context

        fun dp(value: Int): Int = UITheme.dpToPx(context, value)

        when (viewType) {
            VIEW_TYPE_HERO_BILLBOARD -> {
                // Netflix Featured Billboard (Top Hero Card)
                val heroCard = FrameLayout(context).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(380)
                    ).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(16))
                    }
                    background = UITheme.createCardShape(context, "#141414", 16, "#222736", 1)
                    isClickable = true
                    isFocusable = true
                }

                val posterView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#141414"))
                        cornerRadius = dp(16).toFloat()
                    }
                    clipToOutline = true
                }

                // Netflix Deep Bottom Gradient
                val gradientOverlay = View(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    background = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            Color.TRANSPARENT,
                            Color.parseColor("#26000000"),
                            Color.parseColor("#B30B0B0F"),
                            Color.parseColor("#FC0B0B0F")
                        )
                    ).apply {
                        cornerRadius = dp(16).toFloat()
                    }
                }

                // Top-Left Netflix Red "TOP 10" Badge
                val topBadge = TextView(context).apply {
                    text = "🔥 #1 IN MOVIES TODAY"
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = UITheme.createBadgeDrawable(context, UITheme.PRIMARY, 6)
                    val pV = dp(4)
                    val pH = dp(8)
                    setPadding(pH, pV, pH, pV)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        setMargins(dp(12), dp(12), 0, 0)
                    }
                }

                // Bottom Content Container (Title, Metadata, Overview, Action Buttons)
                val contentContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.BOTTOM
                        setMargins(dp(16), 0, dp(16), dp(16))
                    }
                }

                val titleView = TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setShadowLayer(dp(4).toFloat(), 0f, dp(2).toFloat(), Color.BLACK)
                }

                val metaView = TextView(context).apply {
                    setTextColor(Color.parseColor("#E5E7EB"))
                    textSize = 11.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(4), 0, dp(4))
                }

                val overviewView = TextView(context).apply {
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 11f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, 0, 0, dp(12))
                }

                // Netflix Action Buttons Row: [ ▶ Play ] [ + My List ] [ 📥 Download ]
                val buttonRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val playBtn = TextView(context).apply {
                    text = "▶  Play"
                    textSize = 13.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.BLACK)
                    background = UITheme.createCardShape(context, "#FFFFFF", 8, "#FFFFFF", 0)
                    gravity = Gravity.CENTER
                    val pV = dp(8)
                    val pH = dp(22)
                    setPadding(pH, pV, pH, pV)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, dp(10), 0) }
                    isClickable = true
                    isFocusable = true
                }

                val mylistBtn = TextView(context).apply {
                    text = "+  My List"
                    textSize = 12.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = UITheme.createCardShape(context, "#33FFFFFF", 8, "#40FFFFFF", 1)
                    gravity = Gravity.CENTER
                    val pV = dp(8)
                    val pH = dp(14)
                    setPadding(pH, pV, pH, pV)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, dp(10), 0) }
                    isClickable = true
                    isFocusable = true
                }

                val dlBtn = TextView(context).apply {
                    text = "📥  Download"
                    textSize = 12.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = UITheme.createCardShape(context, "#33FFFFFF", 8, "#40FFFFFF", 1)
                    gravity = Gravity.CENTER
                    val pV = dp(8)
                    val pH = dp(14)
                    setPadding(pH, pV, pH, pV)
                    isClickable = true
                    isFocusable = true
                }

                buttonRow.addView(playBtn)
                buttonRow.addView(mylistBtn)
                buttonRow.addView(dlBtn)

                contentContainer.addView(titleView)
                contentContainer.addView(metaView)
                contentContainer.addView(overviewView)
                contentContainer.addView(buttonRow)

                heroCard.addView(posterView)
                heroCard.addView(gradientOverlay)
                heroCard.addView(topBadge)
                heroCard.addView(contentContainer)

                return ViewHolder(
                    layout = heroCard,
                    posterView = posterView,
                    titleText = titleView,
                    yearText = metaView,
                    overviewText = overviewView,
                    playButton = playBtn,
                    mylistTextButton = mylistBtn,
                    downloadButton = dlBtn,
                    topBadge = topBadge
                )
            }

            VIEW_TYPE_CHANNEL -> {
                // Premium List Card for Telegram Monitored Channels
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = UITheme.createRippleCardShape(context, UITheme.CARD, 16, UITheme.STROKE_COLOR)
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dp(6), dp(6), dp(6), dp(8))
                    }
                }

                val posterView = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                        setMargins(0, 0, dp(14), 0)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }

                val textContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val titleView = TextView(context).apply {
                    UITheme.applyCardTitleStyle(this)
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val descView = TextView(context).apply {
                    UITheme.applyMetadataStyle(this)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val arrowView = TextView(context).apply {
                    text = "➔"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
                    background = UITheme.createCardShape(context, UITheme.SURFACE, 10, UITheme.STROKE_COLOR, 1)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }

                textContainer.addView(titleView)
                textContainer.addView(descView)

                row.addView(posterView)
                row.addView(textContainer)
                row.addView(arrowView)

                return ViewHolder(layout = row, posterView = posterView, titleText = titleView, overviewText = descView)
            }

            VIEW_TYPE_TELEGRAM_MEDIA -> {
                // 16:9 Landscape Poster Card for Telegram channel video files
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dp(2), dp(4), dp(2), dp(12))
                    }
                }

                val posterFrame = object : FrameLayout(context) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        val width = MeasureSpec.getSize(widthMeasureSpec)
                        val height = (width * 9) / 16
                        super.onMeasure(
                            widthMeasureSpec,
                            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                        )
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val posterView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#171B26"))
                        cornerRadius = dp(14).toFloat()
                    }
                    clipToOutline = true
                }

                val bookmarkButton = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        dp(26),
                        dp(38)
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                        setMargins(0, 0, dp(8), 0)
                    }
                    isClickable = true
                    isFocusable = true
                }

                val downloadButton = TextView(context).apply {
                    text = "📥"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    background = UITheme.createCardShape(context, "#CC0F0F1A", 10, UITheme.STROKE_COLOR, 1)
                    layoutParams = FrameLayout.LayoutParams(dp(30), dp(30)).apply {
                        gravity = Gravity.TOP or Gravity.START
                        setMargins(dp(6), dp(6), 0, 0)
                    }
                    isClickable = true
                    isFocusable = true
                }

                posterFrame.addView(posterView)
                posterFrame.addView(bookmarkButton)
                posterFrame.addView(downloadButton)

                val textContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(8), dp(4), 0)
                }

                val titleView = TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val yearView = TextView(context).apply {
                    setTextColor(Color.parseColor(UITheme.SUCCESS))
                    textSize = 11f
                    setPadding(0, dp(2), 0, dp(2))
                }

                val overviewView = TextView(context).apply {
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                textContainer.addView(titleView)
                textContainer.addView(yearView)
                textContainer.addView(overviewView)

                card.addView(posterFrame)
                card.addView(textContainer)

                return ViewHolder(card, posterView, titleView, yearView, overviewView, bookmarkButton = bookmarkButton, downloadButton = downloadButton)
            }

            VIEW_TYPE_HORIZONTAL_ROW -> {
                // Netflix Horizontal Scrolling Row (Shelf / Swimlane)
                val rowContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, dp(8), 0, dp(14))
                    }
                }

                val headerRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dp(14), 0, dp(14), dp(8))
                    }
                }

                val titleView = TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 17.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    letterSpacing = 0.02f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val seeAllBtn = TextView(context).apply {
                    text = "See All ›"
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(8), dp(4), dp(4), dp(4))
                    isClickable = true
                    isFocusable = true
                }

                headerRow.addView(titleView)
                headerRow.addView(seeAllBtn)

                val rowRv = RecyclerView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    clipToPadding = false
                    setPadding(dp(10), 0, dp(10), 0)
                    layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                    setHasFixedSize(true)
                    setRecycledViewPool(sharedViewPool)
                }

                rowContainer.addView(headerRow)
                rowContainer.addView(rowRv)

                return ViewHolder(
                    layout = rowContainer,
                    titleText = titleView,
                    seeAllButton = seeAllBtn,
                    rowRecyclerView = rowRv
                )
            }

            else -> {
                // Netflix Modern Borderless Poster Card (2:3 Aspect Ratio, 3-column compact grid)
                val card = object : FrameLayout(context) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        val width = MeasureSpec.getSize(widthMeasureSpec)
                        val height = (width * 3) / 2
                        super.onMeasure(
                            widthMeasureSpec,
                            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                        )
                    }
                }.apply {
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(6))
                    }
                    background = UITheme.createCardShape(context, "#141414", 10, "#1E222D", 1)
                    isClickable = true
                    isFocusable = true
                }

                val posterView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#141414"))
                        cornerRadius = dp(10).toFloat()
                    }
                    clipToOutline = true
                }

                // Netflix Smooth Dark Gradient Overlay
                val gradientOverlay = View(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    background = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            Color.TRANSPARENT,
                            Color.TRANSPARENT,
                            Color.parseColor("#80000000"),
                            Color.parseColor("#F00A0A0E")
                        )
                    ).apply {
                        cornerRadius = dp(10).toFloat()
                    }
                }

                // Top-Left Rating Pill (Netflix Gold on Matte Black)
                val ratingBadge = TextView(context).apply {
                    textSize = 9.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(UITheme.WARNING))
                    background = UITheme.createBadgeDrawable(context, "#CC0A0A0E", 5)
                    val pV = dp(2)
                    val pH = dp(5)
                    setPadding(pH, pV, pH, pV)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        setMargins(dp(6), dp(6), 0, 0)
                    }
                }

                // Top-Right Bookmark Button
                val bookmarkButton = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(dp(22), dp(32)).apply {
                        gravity = Gravity.TOP or Gravity.END
                        setMargins(0, 0, dp(6), 0)
                    }
                    isClickable = true
                    isFocusable = true
                }

                // Bottom Content Container (Title + Year/Details)
                val bottomContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.BOTTOM
                        setMargins(dp(6), 0, dp(6), dp(6))
                    }
                }

                val titleView = TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
                }

                val yearView = TextView(context).apply {
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 9.5f
                    maxLines = 1
                    setPadding(0, dp(1), 0, 0)
                }

                bottomContainer.addView(titleView)
                bottomContainer.addView(yearView)

                card.addView(posterView)
                card.addView(gradientOverlay)
                card.addView(ratingBadge)
                card.addView(bookmarkButton)
                card.addView(bottomContainer)

                return ViewHolder(
                    layout = card,
                    posterView = posterView,
                    titleText = titleView,
                    yearText = yearView,
                    bookmarkButton = bookmarkButton,
                    topBadge = ratingBadge
                )
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        // Handle Horizontal Row binding
        if (holder.rowRecyclerView != null) {
            holder.titleText.text = item.title

            if (item.catalogTarget.isNotBlank() && onSeeAllClick != null) {
                holder.seeAllButton?.visibility = View.VISIBLE
                holder.seeAllButton?.setOnClickListener {
                    onSeeAllClick.invoke(item.title, item.catalogTarget)
                }
                holder.titleText.setOnClickListener {
                    onSeeAllClick.invoke(item.title, item.catalogTarget)
                }
            } else {
                holder.seeAllButton?.visibility = View.GONE
                holder.titleText.setOnClickListener(null)
            }

            val rowAdapter = HorizontalMediaCardAdapter(
                items = item.horizontalItems,
                isTop10 = item.isTop10Row,
                onClick = onClick,
                onLongClick = onLongClick,
                onBookmarkToggle = onBookmarkToggle
            )
            holder.rowRecyclerView.adapter = rowAdapter
            return
        }

        holder.titleText.text = item.title

        // Bind Year and Metadata line
        if (holder.yearText != null) {
            when (item.type) {
                "hero_billboard" -> {
                    val y = if (item.year.isNotBlank()) item.year else "2024"
                    val r = if (item.rating.isNotBlank() && item.rating != "—") "⭐ ${item.rating}" else "⭐ 8.8"
                    holder.yearText.text = "$y  •  $r  •  4K Ultra HD  •  5.1 Surround"
                    holder.yearText.setTextColor(Color.parseColor("#E5E7EB"))
                }
                "telegram_media" -> {
                    holder.yearText.text = "${item.year}  •  ${item.rating}"
                    holder.yearText.setTextColor(Color.parseColor(UITheme.SUCCESS))
                }
                else -> {
                    val y = if (item.year.isNotBlank()) item.year else ""
                    val r = if (item.rating.isNotBlank() && item.rating != "—") "⭐ ${item.rating}" else ""
                    holder.yearText.text = listOf(y, r).filter { it.isNotBlank() }.joinToString("  •  ")
                    holder.yearText.setTextColor(Color.parseColor("#9CA3AF"))
                }
            }
        }

        // Bind Top Badge
        if (holder.topBadge != null) {
            if (item.type == "hero_billboard") {
                holder.topBadge.text = "🔥 #1 IN MOVIES TODAY"
                holder.topBadge.visibility = View.VISIBLE
            } else if (item.rating.isNotBlank() && item.rating != "—") {
                holder.topBadge.text = "⭐ ${item.rating}"
                holder.topBadge.visibility = View.VISIBLE
            } else {
                holder.topBadge.visibility = View.GONE
            }
        }

        // Bind Overview Synopsis
        holder.overviewText?.text = item.overview
        if (item.type == "history_group") {
            holder.overviewText?.maxLines = 5
        } else if (item.type == "hero_billboard") {
            holder.overviewText?.maxLines = 2
        } else if (item.type == "telegram_media") {
            holder.overviewText?.maxLines = 1
        }

        // Bind Poster Image with Glide
        if (holder.posterView != null) {
            val refreshedPoster = TelegramStreamingProxy.refreshUrl(item.posterUrl)
            val cornerPx = UITheme.dpToPx(context, if (item.type == "hero_billboard") 16 else 10)
            if (refreshedPoster.isNotBlank()) {
                val defaultPlaceholder = if (item.type == "telegram_media") android.R.drawable.ic_media_play else android.R.drawable.ic_menu_gallery
                val cacheKey = if (refreshedPoster.contains("/thumbnail/")) {
                    "thumb_" + refreshedPoster.substringAfter("/thumbnail/").substringBefore("?")
                } else {
                    refreshedPoster.substringBefore("?")
                }
                val glideUrl = object : com.bumptech.glide.load.model.GlideUrl(refreshedPoster) {
                    override fun getCacheKey(): String = cacheKey
                }
                Glide.with(context)
                    .load(glideUrl)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .transform(CenterCrop(), RoundedCorners(cornerPx))
                    .placeholder(defaultPlaceholder)
                    .error(defaultPlaceholder)
                    .into(holder.posterView)
            } else {
                holder.posterView.setImageResource(if (item.type == "telegram_media") android.R.drawable.ic_media_play else android.R.drawable.ic_menu_gallery)
            }
        }

        // Bind Play Button (Hero Billboard)
        holder.playButton?.setOnClickListener {
            onClick(item)
        }

        // Bind Bookmark Button
        val isBookmarked = LibraryManager.isBookmarked(context, item.id)

        if (holder.bookmarkButton != null) {
            if (item.type == "channel" || item.id == "watch_history") {
                holder.bookmarkButton.visibility = View.GONE
            } else {
                holder.bookmarkButton.visibility = View.VISIBLE
                bindBookmarkBadge(holder.bookmarkButton, isBookmarked)
                holder.bookmarkButton.setOnClickListener {
                    val nowBookmarked = LibraryManager.toggleBookmark(context, item)
                    bindBookmarkBadge(holder.bookmarkButton, nowBookmarked)
                    val toastMsg = if (nowBookmarked) "Saved '${item.title}' to My List 📚" else "Removed '${item.title}' from My List"
                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                    onBookmarkToggle?.invoke(item, nowBookmarked)
                }
            }
        }

        if (holder.mylistTextButton != null) {
            bindMyListButton(holder.mylistTextButton, isBookmarked)
            holder.mylistTextButton.setOnClickListener {
                val nowBookmarked = LibraryManager.toggleBookmark(context, item)
                bindMyListButton(holder.mylistTextButton, nowBookmarked)
                val toastMsg = if (nowBookmarked) "Saved '${item.title}' to My List 📚" else "Removed '${item.title}' from My List"
                Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                onBookmarkToggle?.invoke(item, nowBookmarked)
            }
        }

        // Bind Download Button
        if (holder.downloadButton != null) {
            if (item.type == "channel" || item.id == "watch_history") {
                holder.downloadButton.visibility = View.GONE
            } else {
                holder.downloadButton.visibility = View.VISIBLE
                holder.downloadButton.setOnClickListener {
                    onDownloadClick?.invoke(item)
                }
            }
        }

        holder.layout.setOnClickListener { onClick(item) }
        holder.layout.setOnLongClickListener {
            onLongClick?.invoke(item) ?: false
        }
    }

    private fun bindBookmarkBadge(button: ImageView, isBookmarked: Boolean) {
        button.setImageResource(if (isBookmarked) android.R.drawable.star_big_on else android.R.drawable.star_big_off)
    }

    private fun bindMyListButton(button: TextView, isBookmarked: Boolean) {
        button.text = if (isBookmarked) "✓  In List" else "+  My List"
        button.setTextColor(if (isBookmarked) Color.parseColor(UITheme.SUCCESS) else Color.WHITE)
    }

    override fun getItemCount(): Int = items.size
}

class HorizontalMediaCardAdapter(
    private val items: List<MediaItem>,
    private val isTop10: Boolean = false,
    private val onClick: (MediaItem) -> Unit,
    private val onLongClick: ((MediaItem) -> Boolean)? = null,
    private val onBookmarkToggle: ((MediaItem, Boolean) -> Unit)? = null
) : RecyclerView.Adapter<HorizontalMediaCardAdapter.CardViewHolder>() {

    class CardViewHolder(
        val layout: View,
        val posterView: ImageView,
        val titleText: TextView,
        val badgeText: TextView,
        val starBtn: ImageView,
        val progressBar: View? = null
    ) : RecyclerView.ViewHolder(layout)

    override fun getItemViewType(position: Int): Int {
        val item = items.getOrNull(position)
        return if (item?.type == "telegram_media") 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val context = parent.context
        fun dp(v: Int): Int = UITheme.dpToPx(context, v)

        val isLandscape = viewType == 1
        val cardWidth = if (isLandscape) dp(190) else dp(116)
        val cardHeight = if (isLandscape) dp(107) else dp(172)

        val card = FrameLayout(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(cardWidth, cardHeight).apply {
                setMargins(dp(4), dp(2), dp(6), dp(4))
            }
            background = UITheme.createCardShape(context, "#141414", 10, "#262C3A", 1)
            clipToOutline = true
            isClickable = true
            isFocusable = true
        }

        val posterView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#181B22"))
                cornerRadius = dp(10).toFloat()
            }
            clipToOutline = true
        }

        val bottomGradient = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(55)
            ).apply {
                gravity = Gravity.BOTTOM
            }
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#E60B0B0F"))
            )
        }

        val badge = TextView(context).apply {
            textSize = 9.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            val pV = dp(2)
            val pH = dp(6)
            setPadding(pH, pV, pH, pV)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(6), dp(6), 0, 0)
            }
        }

        val starBtn = ImageView(context).apply {
            val starSize = dp(26)
            layoutParams = FrameLayout.LayoutParams(starSize, starSize).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(6), dp(6), 0)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#80000000"))
            }
            setImageResource(android.R.drawable.star_big_off)
        }

        val titleText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(6), 0, dp(6), dp(6))
            }
        }

        val progressBar = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                0,
                dp(3)
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
            setBackgroundColor(Color.parseColor(UITheme.PRIMARY)) // Netflix Red
            visibility = View.GONE
        }

        card.addView(posterView)
        card.addView(bottomGradient)
        card.addView(badge)
        card.addView(starBtn)
        card.addView(titleText)
        card.addView(progressBar)

        return CardViewHolder(card, posterView, titleText, badge, starBtn, progressBar)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        fun dp(v: Int): Int = UITheme.dpToPx(context, v)

        holder.titleText.text = item.title

        // Badge: Top 10 or Rating
        if (isTop10) {
            holder.badgeText.text = "TOP ${position + 1}"
            holder.badgeText.background = UITheme.createBadgeDrawable(context, UITheme.PRIMARY, 4)
            holder.badgeText.visibility = View.VISIBLE
        } else if (item.type == "telegram_media") {
            holder.starBtn.visibility = View.GONE
            val badgeLabel = item.rating.ifBlank { item.year }
            if (badgeLabel.isNotBlank()) {
                holder.badgeText.text = badgeLabel
                holder.badgeText.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#B3000000"))
                    setStroke(dp(1), Color.parseColor("#40FFFFFF"))
                    cornerRadius = dp(4).toFloat()
                }
                holder.badgeText.visibility = View.VISIBLE
            } else {
                holder.badgeText.visibility = View.GONE
            }
        } else if (item.rating.isNotBlank() && item.rating != "—") {
            holder.badgeText.text = "⭐ ${item.rating}"
            holder.badgeText.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#8C000000"))
                setStroke(dp(1), Color.parseColor("#40FFFFFF"))
                cornerRadius = dp(4).toFloat()
            }
            holder.badgeText.visibility = View.VISIBLE
        } else {
            holder.badgeText.visibility = View.GONE
        }

        // Bookmark state
        val isBookmarked = LibraryManager.isBookmarked(context, item.id)
        holder.starBtn.setImageResource(if (isBookmarked) android.R.drawable.star_big_on else android.R.drawable.star_big_off)
        holder.starBtn.setOnClickListener {
            val nowBookmarked = LibraryManager.toggleBookmark(context, item)
            holder.starBtn.setImageResource(if (nowBookmarked) android.R.drawable.star_big_on else android.R.drawable.star_big_off)
            val toast = if (nowBookmarked) "Saved '${item.title}' to My List 📚" else "Removed '${item.title}' from My List"
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            onBookmarkToggle?.invoke(item, nowBookmarked)
        }

        // Watch History Progress
        val prefs = context.getSharedPreferences("teleflix_resume_points", android.content.Context.MODE_PRIVATE)
        val pos = prefs.getLong("pos_${item.id}", 0L)
        val dur = prefs.getLong("dur_${item.id}", 0L)
        if (pos > 0 && dur > 0 && holder.progressBar != null) {
            val percent = (pos.toFloat() / dur.toFloat()).coerceIn(0.05f, 1f)
            val cardWidthPx = dp(116)
            holder.progressBar.layoutParams = FrameLayout.LayoutParams(
                (cardWidthPx * percent).toInt(),
                dp(3)
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
            holder.progressBar.visibility = View.VISIBLE
        } else {
            holder.progressBar?.visibility = View.GONE
        }

        // Poster Image
        val refreshedPoster = TelegramStreamingProxy.refreshUrl(item.posterUrl)
        if (refreshedPoster.isNotBlank()) {
            val cacheKey = if (refreshedPoster.contains("/thumbnail/")) {
                "thumb_" + refreshedPoster.substringAfter("/thumbnail/").substringBefore("?")
            } else {
                refreshedPoster.substringBefore("?")
            }
            val glideUrl = object : com.bumptech.glide.load.model.GlideUrl(refreshedPoster) {
                override fun getCacheKey(): String = cacheKey
            }
            Glide.with(context)
                .load(glideUrl)
                .transform(CenterCrop(), RoundedCorners(dp(10)))
                .into(holder.posterView)
        } else {
            holder.posterView.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        // Click actions
        holder.layout.setOnClickListener {
            onClick(item)
        }
        holder.layout.setOnLongClickListener {
            onLongClick?.invoke(item) ?: false
        }
    }

    override fun getItemCount(): Int = items.size
}
