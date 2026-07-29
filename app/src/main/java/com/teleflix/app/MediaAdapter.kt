package com.teleflix.app

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners

class MediaAdapter(
    private val items: List<MediaItem>,
    private val onClick: (MediaItem) -> Unit,
    private val onLongClick: ((MediaItem) -> Boolean)? = null
) : RecyclerView.Adapter<MediaAdapter.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_CHANNEL = 1
        private const val VIEW_TYPE_TELEGRAM_MEDIA = 2
        private const val VIEW_TYPE_DEFAULT = 3
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position].type) {
            "channel" -> VIEW_TYPE_CHANNEL
            "telegram_media", "history_group" -> VIEW_TYPE_TELEGRAM_MEDIA
            else -> VIEW_TYPE_DEFAULT
        }
    }

    class ViewHolder(
        val layout: View,
        val posterView: ImageView? = null,
        val titleText: TextView,
        val yearText: TextView? = null,
        val overviewText: TextView? = null,
        val iconText: TextView? = null
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

                val iconView = TextView(context).apply {
                    text = "💬"
                    textSize = 20f
                    gravity = Gravity.CENTER
                    background = UITheme.createCardShape(context, UITheme.SECONDARY, 14, UITheme.STROKE_COLOR, 1)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                        setMargins(0, 0, dp(14), 0)
                    }
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

                row.addView(iconView)
                row.addView(textContainer)
                row.addView(arrowView)

                return ViewHolder(layout = row, titleText = titleView, overviewText = descView, iconText = iconView)
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

                val posterView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(125)
                    )
                    setBackgroundColor(Color.parseColor(UITheme.SURFACE))
                }

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

                card.addView(posterView)
                card.addView(textContainer)

                return ViewHolder(card, posterView, titleView, yearView, overviewView)
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

                val posterView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(245)
                    )
                    setBackgroundColor(Color.parseColor(UITheme.SURFACE))
                }

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

                card.addView(posterView)
                card.addView(textContainer)

                return ViewHolder(card, posterView, titleView, yearView, overviewView)
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
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
            val cornerPx = UITheme.dpToPx(holder.itemView.context, 16)
            if (refreshedPoster.isNotBlank()) {
                val defaultPlaceholder = if (item.type == "telegram_media") android.R.drawable.ic_media_play else android.R.drawable.ic_menu_gallery
                Glide.with(holder.itemView.context)
                    .load(refreshedPoster)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .transform(CenterCrop(), RoundedCorners(cornerPx))
                    .placeholder(defaultPlaceholder)
                    .error(defaultPlaceholder)
                    .into(holder.posterView)
            } else {
                holder.posterView.setImageResource(if (item.type == "telegram_media") android.R.drawable.ic_media_play else android.R.drawable.ic_menu_gallery)
            }
        }

        holder.layout.setOnClickListener { onClick(item) }
        holder.layout.setOnLongClickListener {
            onLongClick?.invoke(item) ?: false
        }
    }

    override fun getItemCount(): Int = items.size
}
