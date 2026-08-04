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
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners

class MediaAdapter(
    private val items: List<MediaItem>,
    private val onClick: (MediaItem) -> Unit,
    private val onLongClick: ((MediaItem) -> Boolean)? = null,
    private val onBookmarkToggle: ((MediaItem, Boolean) -> Unit)? = null,
    private val onDownloadClick: ((MediaItem) -> Unit)? = null
) : RecyclerView.Adapter<MediaAdapter.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_CHANNEL = 1
        private const val VIEW_TYPE_TELEGRAM_MEDIA = 2
        private const val VIEW_TYPE_DEFAULT = 3
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position].type) {
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
        val downloadButton: TextView? = null
    ) : RecyclerView.ViewHolder(layout)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context

        fun dp(value: Int): Int = UITheme.dpToPx(context, value)

        when (viewType) {
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
                // Landscape Cinematic Card (16:9 Widescreen Aspect Ratio)
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = UITheme.createRippleCardShape(context, UITheme.CARD, 18, UITheme.STROKE_COLOR)
                    setPadding(0, 0, 0, dp(12))
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dp(6), dp(6), dp(6), dp(12))
                    }
                }

                val posterFrame = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(125)
                    )
                }

                val posterView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.parseColor(UITheme.SURFACE))
                }

                val bookmarkButton = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        dp(26),
                        dp(38)
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                        setMargins(0, 0, dp(10), 0)
                    }
                    isClickable = true
                    isFocusable = true
                }

                val downloadButton = TextView(context).apply {
                    text = "📥"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    background = UITheme.createCardShape(context, "#CC0F0F1A", 10, UITheme.STROKE_COLOR, 1)
                    layoutParams = FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                        gravity = Gravity.TOP or Gravity.START
                        setMargins(dp(8), dp(8), 0, 0)
                    }
                    isClickable = true
                    isFocusable = true
                }

                posterFrame.addView(posterView)
                posterFrame.addView(bookmarkButton)
                posterFrame.addView(downloadButton)

                val textContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), 0)
                }

                val titleView = TextView(context).apply {
                    UITheme.applyCardTitleStyle(this)
                    textSize = 13f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val yearView = TextView(context).apply {
                    UITheme.applyMetadataStyle(this)
                    setTextColor(Color.parseColor(UITheme.SUCCESS))
                    setPadding(0, dp(3), 0, dp(3))
                }

                val overviewView = TextView(context).apply {
                    UITheme.applyCaptionStyle(this)
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

            else -> {
                // Standard Portrait Movie / Series Card (~2:3 Aspect Ratio)
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = UITheme.createRippleCardShape(context, UITheme.CARD, 18, UITheme.STROKE_COLOR)
                    setPadding(0, 0, 0, dp(12))
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dp(6), dp(6), dp(6), dp(12))
                    }
                }

                val posterFrame = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(245)
                    )
                }

                val posterView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.parseColor(UITheme.SURFACE))
                }

                val bookmarkButton = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        dp(28),
                        dp(42)
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                        setMargins(0, 0, dp(12), 0)
                    }
                    isClickable = true
                    isFocusable = true
                }

                val downloadButton = TextView(context).apply {
                    text = "📥"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    background = UITheme.createCardShape(context, "#CC0F0F1A", 10, UITheme.STROKE_COLOR, 1)
                    layoutParams = FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                        gravity = Gravity.TOP or Gravity.START
                        setMargins(dp(8), dp(8), 0, 0)
                    }
                    isClickable = true
                    isFocusable = true
                }

                posterFrame.addView(posterView)
                posterFrame.addView(bookmarkButton)
                posterFrame.addView(downloadButton)

                val textContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), 0)
                }

                val titleView = TextView(context).apply {
                    UITheme.applyCardTitleStyle(this)
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val yearView = TextView(context).apply {
                    UITheme.applyMetadataStyle(this)
                    setPadding(0, dp(3), 0, dp(4))
                }

                val overviewView = TextView(context).apply {
                    UITheme.applyCaptionStyle(this)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                textContainer.addView(titleView)
                textContainer.addView(yearView)
                textContainer.addView(overviewView)

                card.addView(posterFrame)
                card.addView(textContainer)

                return ViewHolder(card, posterView, titleView, yearView, overviewView, bookmarkButton = bookmarkButton, downloadButton = downloadButton)
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.titleText.text = item.title

        if (holder.yearText != null) {
            if (item.type == "telegram_media") {
                holder.yearText.text = "${item.year}  •  ${item.rating}"
                holder.yearText.setTextColor(Color.parseColor(UITheme.SUCCESS))
            } else {
                holder.yearText.text = "${item.year}  •  ⭐ ${item.rating}"
                holder.yearText.setTextColor(Color.parseColor(UITheme.WARNING))
            }
        }

        holder.overviewText?.text = item.overview
        if (item.type == "history_group") {
            holder.overviewText?.maxLines = 5
        } else if (item.type == "telegram_media") {
            holder.overviewText?.maxLines = 1
        }

        if (holder.posterView != null) {
            val refreshedPoster = TelegramStreamingProxy.refreshUrl(item.posterUrl)
            val cornerPx = UITheme.dpToPx(context, 16)
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

        if (holder.bookmarkButton != null) {
            if (item.type == "channel" || item.id == "watch_history") {
                holder.bookmarkButton.visibility = View.GONE
            } else {
                holder.bookmarkButton.visibility = View.VISIBLE
                val isBookmarked = LibraryManager.isBookmarked(context, item.id)
                bindBookmarkBadge(holder.bookmarkButton, isBookmarked)

                holder.bookmarkButton.setOnClickListener {
                    val nowBookmarked = LibraryManager.toggleBookmark(context, item)
                    bindBookmarkBadge(holder.bookmarkButton, nowBookmarked)
                    val toastMsg = if (nowBookmarked) {
                        "Saved '${item.title}' to Library 📚"
                    } else {
                        "Removed '${item.title}' from Library"
                    }
                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                    onBookmarkToggle?.invoke(item, nowBookmarked)
                }
            }
        }

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
        if (isBookmarked) {
            button.setImageResource(android.R.drawable.star_big_on)
        } else {
            button.setImageResource(android.R.drawable.star_big_off)
        }
    }

    override fun getItemCount(): Int = items.size
}
