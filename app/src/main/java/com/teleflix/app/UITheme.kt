package com.teleflix.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.widget.TextView

object UITheme {
    // Exact requested color palette
    const val BACKGROUND = "#0B0B0F"
    const val SURFACE = "#15171D"
    const val CARD = "#1B1F29"
    const val PRIMARY = "#E50914"       // Netflix Red
    const val SECONDARY = "#2A2F3A"     // Surface pill/chip
    const val ACCENT_BLUE = "#3B82F6"   // Active blue accent
    const val TEXT_PRIMARY = "#FFFFFF"
    const val TEXT_SECONDARY = "#A3A3A3"
    const val SUCCESS = "#22C55E"
    const val WARNING = "#FACC15"
    const val STROKE_COLOR = "#282D3D"
    const val INPUT_BG = "#15171D"

    fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    // Shapes & Drawables (Rounded corners 16-20dp)
    fun createCardShape(
        context: Context,
        bgColorHex: String = CARD,
        cornerRadiusDp: Int = 18,
        strokeColorHex: String = STROKE_COLOR,
        strokeWidthDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(bgColorHex))
            cornerRadius = dpToPx(context, cornerRadiusDp).toFloat()
            if (strokeWidthDp > 0) {
                setStroke(dpToPx(context, strokeWidthDp), Color.parseColor(strokeColorHex))
            }
        }
    }

    fun createRippleCardShape(
        context: Context,
        bgColorHex: String = CARD,
        cornerRadiusDp: Int = 18,
        strokeColorHex: String = STROKE_COLOR
    ): RippleDrawable {
        val baseShape = createCardShape(context, bgColorHex, cornerRadiusDp, strokeColorHex, 1)
        val maskShape = createCardShape(context, "#FFFFFF", cornerRadiusDp, "#000000", 0)
        val rippleColor = ColorStateList.valueOf(Color.parseColor("#20FFFFFF"))
        return RippleDrawable(rippleColor, baseShape, maskShape)
    }

    fun createPillDrawable(
        context: Context,
        isSelected: Boolean,
        activeColorHex: String = PRIMARY,
        inactiveColorHex: String = SURFACE
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(if (isSelected) activeColorHex else inactiveColorHex))
            cornerRadius = dpToPx(context, 20).toFloat()
            if (!isSelected) {
                setStroke(dpToPx(context, 1), Color.parseColor(SECONDARY))
            }
        }
    }

    fun createBadgeDrawable(
        context: Context,
        bgColorHex: String,
        cornerRadiusDp: Int = 10
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(bgColorHex))
            cornerRadius = dpToPx(context, cornerRadiusDp).toFloat()
        }
    }

    fun createInputBackground(
        context: Context,
        focused: Boolean = false
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(INPUT_BG))
            cornerRadius = dpToPx(context, 16).toFloat()
            val strokeColor = if (focused) PRIMARY else SECONDARY
            setStroke(dpToPx(context, 1), Color.parseColor(strokeColor))
        }
    }

    // Typography Setup (Avoiding bold everywhere, using clean weight hierarchy)
    fun applyLargeTitleStyle(textView: TextView) {
        textView.textSize = 24f
        textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        textView.setTextColor(Color.parseColor(TEXT_PRIMARY))
    }

    fun applySectionTitleStyle(textView: TextView) {
        textView.textSize = 18f
        textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        textView.setTextColor(Color.parseColor(TEXT_PRIMARY))
    }

    fun applyCardTitleStyle(textView: TextView) {
        textView.textSize = 14f
        textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        textView.setTextColor(Color.parseColor(TEXT_PRIMARY))
    }

    fun applyMetadataStyle(textView: TextView) {
        textView.textSize = 12f
        textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
        textView.setTextColor(Color.parseColor(TEXT_SECONDARY))
    }

    fun applyCaptionStyle(textView: TextView) {
        textView.textSize = 11f
        textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
        textView.setTextColor(Color.parseColor(TEXT_SECONDARY))
    }
}
