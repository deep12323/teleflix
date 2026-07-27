package com.teleflix.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners

import android.view.Gravity
import android.view.View

class MediaAdapter(
    private val items: List<MediaItem>,
    private val onClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<MediaAdapter.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_CHANNEL = 1
        private const val VIEW_TYPE_TELEGRAM_MEDIA = 2
        private const val VIEW_TYPE_DEFAULT = 3
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position].type) {
            "channel" -> VIEW_TYPE_CHANNEL
            "telegram_media" -> VIEW_TYPE_TELEGRAM_MEDIA
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

        fun dpToPx(dp: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        when (viewType) {
            VIEW_TYPE_CHANNEL -> {
                // List structure (Horizontal Row) for Telegram Channels
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val shape = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.parseColor("#161B28"))
                        cornerRadius = dpToPx(10).toFloat()
                        setStroke(1, Color.parseColor("#334155"))
                    }
                    background = shape
                    setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(8))
                    }
                }

                val iconView = TextView(context).apply {
                    text = "📢"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    val iconShape = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#1E293B"))
                    }
                    background = iconShape
                    layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                        setMargins(0, 0, dpToPx(16), 0)
                    }
                }

                val textContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val titleView = TextView(context).apply {
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val descView = TextView(context).apply {
                    textSize = 12f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val arrowView = TextView(context).apply {
                    text = "➔"
                    textSize = 18f
                    setTextColor(Color.parseColor("#3B82F6"))
                }

                textContainer.addView(titleView)
                textContainer.addView(descView)

                row.addView(iconView)
                row.addView(textContainer)
                row.addView(arrowView)

                return ViewHolder(layout = row, titleText = titleView, overviewText = descView, iconText = iconView)
            }

            VIEW_TYPE_TELEGRAM_MEDIA -> {
                // Landscape Poster Card (16:9 Widescreen Aspect Ratio)
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val shape = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.parseColor("#161B28"))
                        cornerRadius = dpToPx(12).toFloat()
                    }
                    background = shape
                    setPadding(0, 0, 0, dpToPx(10))
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(12))
                    }
                }

                val posterView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(115)
                    )
                    setBackgroundColor(Color.parseColor("#1E293B"))
                }

                val textContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpToPx(10), dpToPx(8), dpToPx(10), 0)
                }

                val titleView = TextView(context).apply {
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val yearView = TextView(context).apply {
                    textSize = 11f
                    setTextColor(Color.parseColor("#10B981"))
                    setPadding(0, dpToPx(2), 0, dpToPx(2))
                }

                val overviewView = TextView(context).apply {
                    textSize = 10f
                    setTextColor(Color.parseColor("#9CA3AF"))
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
                // Standard Portrait Movie / Series Card (~2:3 aspect ratio)
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val shape = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.parseColor("#161B28"))
                        cornerRadius = dpToPx(12).toFloat()
                    }
                    background = shape
                    setPadding(0, 0, 0, dpToPx(12))
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(12))
                    }
                }

                val posterView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(240)
                    )
                    setBackgroundColor(Color.parseColor("#1F2937"))
                }

                val textContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpToPx(10), dpToPx(8), dpToPx(10), 0)
                }

                val titleView = TextView(context).apply {
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val yearView = TextView(context).apply {
                    textSize = 11f
                    setTextColor(Color.parseColor("#F59E0B"))
                    setPadding(0, dpToPx(2), 0, dpToPx(4))
                }

                val overviewView = TextView(context).apply {
                    textSize = 10f
                    setTextColor(Color.parseColor("#9CA3AF"))
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
        holder.yearText?.text = "${item.year} • ⭐ ${item.rating}"
        holder.overviewText?.text = item.overview

        if (holder.posterView != null) {
            if (item.type == "telegram_media" && item.posterUrl.isNotBlank()) {
                Glide.with(holder.itemView.context)
                    .load(item.posterUrl)
                    .transform(RoundedCorners(12))
                    .placeholder(android.R.drawable.ic_media_video_poster)
                    .error(android.R.drawable.ic_media_video_poster)
                    .into(holder.posterView)
            } else if (item.posterUrl.isNotBlank() && !item.posterUrl.startsWith("http://127.0.0.1")) {
                Glide.with(holder.itemView.context)
                    .load(item.posterUrl)
                    .transform(RoundedCorners(16))
                    .error(android.R.drawable.ic_dialog_alert)
                    .into(holder.posterView)
            } else {
                holder.posterView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        holder.layout.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
