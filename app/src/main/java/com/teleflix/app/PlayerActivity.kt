package com.teleflix.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.jdtech.mpv.MPVLib

/**
 * Netflix-Style Fullscreen Video Player Activity
 * Embedded with MPV native playback engine (libmpv), Auto-Crop black bars,
 * unified Netflix Audio & Subtitles dialog, and zero annoying popups.
 */
class PlayerActivity : AppCompatActivity(), SurfaceHolder.Callback, MPVLib.EventObserver {

    companion object {
        const val NETFLIX_RED = "#E50914"
        const val DRC_FILTER = "lavfi=[acompressor=threshold=-20dB:ratio=4:attack=5:release=50:makeup=2]"

        fun launch(activity: Activity, streamUrl: String, title: String, resumeMs: Long = 0L, mediaId: String = "") {
            val intent = Intent(activity, PlayerActivity::class.java).apply {
                putExtra("stream_url", streamUrl)
                putExtra("title", title)
                putExtra("resume_ms", resumeMs)
                putExtra("media_id", mediaId)
                if (resumeMs <= 0L) {
                    putExtra("from_start", true)
                }
            }
            activity.startActivity(intent)
        }
    }

    private var mpv: MPVLib? = null
    private lateinit var surfaceView: SurfaceView
    private var surfaceHolder: SurfaceHolder? = null

    // Overlay Containers
    private lateinit var controlsOverlay: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var centerControls: LinearLayout
    private lateinit var bottomContainer: LinearLayout

    // Header items
    private lateinit var backBtn: TextView
    private lateinit var titleText: TextView

    // Center Controls (Netflix 10s Rewind, Play/Pause, 10s Forward)
    private lateinit var rewindBtn: TextView
    private lateinit var playPauseBtn: TextView
    private lateinit var forwardBtn: TextView

    // Bottom Bar (Netflix Scrubber + Actions)
    private lateinit var timeText: TextView
    private lateinit var remainingTimeText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var bufferingProgress: ProgressBar

    // Bottom Action Buttons (Netflix style)
    private lateinit var speedBtn: TextView
    private lateinit var lockBtn: TextView
    private lateinit var drcBtn: TextView
    private lateinit var audioSubBtn: TextView
    private lateinit var cropBtn: TextView
    private lateinit var rotateBtn: TextView

    // Screen Lock Overlay (Netflix Locked HUD)
    private lateinit var lockedHud: LinearLayout
    private lateinit var unlockPromptBtn: TextView

    // Sleek Minimalist Top HUD Pill (Volume & Brightness)
    private lateinit var hudPill: LinearLayout
    private lateinit var hudIcon: TextView
    private lateinit var hudBar: ProgressBar
    private lateinit var hudText: TextView

    // Subtle Double-tap Seek Badges
    private lateinit var seekFeedbackLeft: TextView
    private lateinit var seekFeedbackRight: TextView

    private lateinit var gestureDetector: GestureDetector

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    private val unlockHandler = Handler(Looper.getMainLooper())
    private val unlockHideRunnable = Runnable {
        if (isLocked) {
            lockedHud.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    lockedHud.visibility = View.GONE
                    lockedHud.alpha = 1f
                }
                .start()
        }
    }

    private val hudHandler = Handler(Looper.getMainLooper())
    private val hudHideRunnable = Runnable { hudPill.visibility = View.GONE }

    private var isDrcEnabled = false
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null

    private var streamUrl = ""
    private var videoTitle = ""
    private var resumeMs = 0L
    private var mediaId = ""
    private var pendingResumeSeekSec = 0.0
    private var isPlaybackCompleted = false
    private var isMediaLoaded = false
    private var trackPreferencesRestored = false
    private val mediaKey: String
        get() {
            if (mediaId.isNotBlank()) return "media_$mediaId"
            val clean = (streamUrl + "_" + videoTitle).replace(Regex("[^a-zA-Z0-9_-]"), "_")
            return "media_" + (if (clean.length > 50) clean.take(50) + "_" + Math.abs(clean.hashCode()) else clean)
        }

    private var isPlaying = false
    private var durationSec = 0.0
    private var currentPosSec = 0.0
    private var isUserSeeking = false
    private var areControlsVisible = true
    private var isLocked = false

    private val speedOptions = listOf(0.75, 1.0, 1.25, 1.5, 2.0)
    private var currentSpeedIndex = 1

    // Aspect & Crop Modes (Default: Fit Original - Clean, No Zoom, No Side Crop)
    private val aspectRatios = listOf(
        "fit" to "Fit",
        "autocrop" to "Auto Crop",
        "fill" to "Fill (Zoom)",
        "16:9" to "16:9",
        "stretch" to "Stretch"
    )
    private var currentAspectIndex = 0

    private var audioManager: AudioManager? = null
    private var maxVolume = 1
    private var volumeAccumulator = 0f
    private var currentBrightness = -1f
    private var isLandscape = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Extend window past camera cutout / notch (No black space on camera side)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // 2. Fullscreen window & Keep screen awake
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1

        streamUrl = intent.getStringExtra("stream_url") ?: ""
        videoTitle = intent.getStringExtra("title") ?: "Video Player"
        mediaId = intent.getStringExtra("media_id") ?: ""

        resumeMs = intent.getLongExtra("resume_ms", 0L)
        if (resumeMs <= 0L) {
            resumeMs = intent.getIntExtra("position", 0).toLong()
        }
        if (resumeMs <= 0L) {
            resumeMs = intent.getLongExtra("extra_position", 0L)
        }
        if (resumeMs <= 0L) {
            resumeMs = intent.getLongExtra("resume_position", 0L)
        }
        if (resumeMs <= 0L) {
            resumeMs = intent.getLongExtra("position_ms", 0L)
        }
        if (resumeMs <= 0L) {
            resumeMs = intent.getLongExtra("start_position", 0L)
        }

        val fromStart = intent.getBooleanExtra("from_start", false)
        if (!fromStart && resumeMs <= 3000L) {
            val prefsLink = getSharedPreferences("teleflix_resume_points", Context.MODE_PRIVATE)
            val prefsTitle = getSharedPreferences("TeleflixResume", Context.MODE_PRIVATE)
            if (mediaId.isNotBlank()) {
                val p = prefsLink.getLong("id_$mediaId", 0L).takeIf { it > 3000L } ?: prefsLink.getLong(mediaId, 0L)
                if (p > 3000L) resumeMs = p
            }
            if (resumeMs <= 3000L && streamUrl.isNotBlank()) {
                val p = prefsLink.getLong(streamUrl, 0L)
                if (p > 3000L) resumeMs = p
            }
            if (resumeMs <= 3000L && videoTitle.isNotBlank()) {
                val p = prefsTitle.getLong("resume_$videoTitle", 0L)
                if (p > 3000L) resumeMs = p
            }
            if (resumeMs <= 3000L) {
                val prefsTeleflix = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
                val p = (if (mediaId.isNotBlank()) prefsTeleflix.getLong("resume_pos_$mediaId", 0L) else 0L).takeIf { it > 3000L }
                    ?: (if (streamUrl.isNotBlank()) prefsTeleflix.getLong("resume_pos_$streamUrl", 0L) else 0L).takeIf { it > 3000L }
                    ?: (if (videoTitle.isNotBlank()) prefsTeleflix.getLong("resume_pos_$videoTitle", 0L) else 0L).takeIf { it > 3000L }
                    ?: 0L
                if (p > 3000L) resumeMs = p
            }
        }

        pendingResumeSeekSec = if (resumeMs > 3000L) resumeMs / 1000.0 else 0.0

        if (streamUrl.isBlank()) {
            finish()
            return
        }

        isDrcEnabled = getSharedPreferences("teleflix_player_prefs", Context.MODE_PRIVATE)
            .getBoolean("drc_enabled", false)

        buildNetflixUI()
        hideSystemUI()
        initMPV()
    }

    private fun dp(v: Int): Int = UITheme.dpToPx(this, v)

    private fun hideSystemUI() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val decorView = window.peekDecorView() ?: window.decorView
            val controller = WindowCompat.getInsetsController(window, decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Throwable) {
            try {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            } catch (_: Throwable) {}
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun buildNetflixUI() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── 1. Video Surface (Full physical screen edge-to-edge) ──
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        surfaceHolder = surfaceView.holder
        surfaceHolder?.addCallback(this)
        root.addView(surfaceView)

        // ── 2. Netflix Buffering Spinner ──
        bufferingProgress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(NETFLIX_RED))
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER)
        }
        root.addView(bufferingProgress)

        // ── 3. Subtle Double-Tap Seek Badges ──
        seekFeedbackLeft = TextView(this).apply {
            text = "⏪ 10"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@PlayerActivity, "#80000000", 18, "#33FFFFFF", 1)
            val pV = dp(10)
            val pH = dp(18)
            setPadding(pH, pV, pH, pV)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START
            ).apply { setMargins(dp(54), 0, 0, 0) }
        }
        root.addView(seekFeedbackLeft)

        seekFeedbackRight = TextView(this).apply {
            text = "10 ⏩"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@PlayerActivity, "#80000000", 18, "#33FFFFFF", 1)
            val pV = dp(10)
            val pH = dp(18)
            setPadding(pH, pV, pH, pV)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.END
            ).apply { setMargins(0, 0, dp(54), 0) }
        }
        root.addView(seekFeedbackRight)

        // ── 4. Sleek Minimalist Top HUD Pill (Volume & Brightness) ──
        hudPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UITheme.createCardShape(this@PlayerActivity, "#E6111827", 16, "#40FFFFFF", 1)
            val pV = dp(6)
            val pH = dp(14)
            setPadding(pH, pV, pH, pV)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { setMargins(0, dp(22), 0, 0) }
        }

        hudIcon = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        hudPill.addView(hudIcon)

        hudBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val w = dp(84)
            val h = dp(4)
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                setMargins(dp(8), 0, dp(8), 0)
            }
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(NETFLIX_RED))
        }
        hudPill.addView(hudBar)

        hudText = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        hudPill.addView(hudText)
        root.addView(hudPill)

        // ── 5. Netflix Locked Mode Floating Unlock Prompt ──
        lockedHud = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        unlockPromptBtn = TextView(this).apply {
            text = "🔒 Screen Locked  •  Tap to Unlock"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@PlayerActivity, "#E6181818", 22, NETFLIX_RED, 1)
            val pV = dp(12)
            val pH = dp(24)
            setPadding(pH, pV, pH, pV)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                unlockControls()
            }
        }
        lockedHud.addView(unlockPromptBtn)

        // ── 6. Netflix Controls Overlay (Always View.VISIBLE for continuous gestures) ──
        controlsOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
        }

        // Top Header Bar
        val topGradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#E6000000"), Color.TRANSPARENT)
        )
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = topGradient
            val pV = dp(12)
            val pH = dp(20)
            setPadding(pH, pV, pH, pV * 2)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        backBtn = TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val s = dp(38)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                setMargins(0, 0, dp(14), 0)
            }
            background = UITheme.createCardShape(this@PlayerActivity, "#26FFFFFF", 19, "#20FFFFFF", 1)
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)

        val cleanTitle = videoTitle.replace(Regex("""^[📦🗄️⚡🎬🎥📽️\s]+"""), "").trim()
        titleText = TextView(this).apply {
            text = cleanTitle
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(titleText)

        controlsOverlay.addView(topBar)

        // ── Netflix Center Controls: [ ⏪ 10 ]   [ ▶ / ⏸ ]   [ 10 ⏩ ] ──
        centerControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        rewindBtn = TextView(this).apply {
            text = "⏪ 10"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@PlayerActivity, "#80181818", 24, "#33FFFFFF", 1)
            val pV = dp(12)
            val pH = dp(18)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(38), 0) }
            setOnClickListener {
                seekRelative(-10.0)
                resetHideTimer()
            }
        }
        centerControls.addView(rewindBtn)

        playPauseBtn = TextView(this).apply {
            text = "⏸"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val s = dp(68)
            layoutParams = LinearLayout.LayoutParams(s, s)
            background = UITheme.createCardShape(this@PlayerActivity, "#CC181818", 34, "#FFFFFF", 1)
            setOnClickListener {
                togglePlayPause()
                resetHideTimer()
            }
        }
        centerControls.addView(playPauseBtn)

        forwardBtn = TextView(this).apply {
            text = "10 ⏩"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@PlayerActivity, "#80181818", 24, "#33FFFFFF", 1)
            val pV = dp(12)
            val pH = dp(18)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(38), 0, 0, 0) }
            setOnClickListener {
                seekRelative(10.0)
                resetHideTimer()
            }
        }
        centerControls.addView(forwardBtn)

        controlsOverlay.addView(centerControls)

        // ── Netflix Bottom Container (Scrubber + Action Bar) ──
        val bottomGradient = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.parseColor("#E6000000"), Color.TRANSPARENT)
        )
        bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bottomGradient
            val pV = dp(14)
            val pH = dp(20)
            setPadding(pH, pV * 2, pH, pV)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        // 1. Netflix Scrubber Bar Row
        val scrubberRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        timeText = TextView(this).apply {
            text = "00:00"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        scrubberRow.addView(timeText)

        seekBar = SeekBar(this).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(12), 0, dp(12), 0)
            }
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(NETFLIX_RED))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(NETFLIX_RED))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && durationSec > 0) {
                        val target = (progress / 1000.0) * durationSec
                        timeText.text = formatSecToTime(target.toLong())
                        val rem = maxOf(0.0, durationSec - target)
                        remainingTimeText.text = "-${formatSecToTime(rem.toLong())}"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = true
                    hideHandler.removeCallbacks(hideRunnable)
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    if (durationSec > 0) {
                        val target = ((sb?.progress ?: 0) / 1000.0) * durationSec
                        seekAbsolute(target)
                    }
                    isUserSeeking = false
                    resetHideTimer()
                }
            })
        }
        scrubberRow.addView(seekBar)

        remainingTimeText = TextView(this).apply {
            text = "-00:00"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#9CA3AF"))
        }
        scrubberRow.addView(remainingTimeText)

        bottomContainer.addView(scrubberRow)

        // 2. Netflix Bottom Action Row (Speed, Lock, Audio & Subtitles, Crop, Rotate)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(12), 0, 0) }
            layoutParams = lp
        }

        // Speed Pill
        speedBtn = TextView(this).apply {
            text = "⚡ Speed (1.0X)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@PlayerActivity, "#26FFFFFF", 14, "#20FFFFFF", 1)
            val pV = dp(6)
            val pH = dp(12)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, dp(6), 0) }
            setOnClickListener {
                cycleSpeed()
                resetHideTimer()
            }
        }
        actionsRow.addView(speedBtn)

        // Screen Lock Pill
        lockBtn = TextView(this).apply {
            text = "🔒 Lock"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@PlayerActivity, "#26FFFFFF", 14, "#20FFFFFF", 1)
            val pV = dp(6)
            val pH = dp(12)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, dp(6), 0) }
            setOnClickListener {
                lockControls()
            }
        }
        actionsRow.addView(lockBtn)

        // Dynamic Range Compression (DRC) Pill (Mpv-infinity Night Mode / Loudness EQ)
        drcBtn = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            val pV = dp(6)
            val pH = dp(12)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, dp(6), 0) }
            setOnClickListener {
                toggleDrc()
                resetHideTimer()
            }
        }
        updateDrcButtonVisual()
        actionsRow.addView(drcBtn)

        // Unified Audio & Subtitles Pill (Signature Netflix UI)
        audioSubBtn = TextView(this).apply {
            text = "💬 Audio & Subtitles"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@PlayerActivity, "#26FFFFFF", 14, "#20FFFFFF", 1)
            val pV = dp(6)
            val pH = dp(14)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, dp(6), 0) }
            setOnClickListener {
                showNetflixAudioSubDialog()
                resetHideTimer()
            }
        }
        actionsRow.addView(audioSubBtn)

        val prefs = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
        val initialCropMode = prefs.getString("crop_mode_$mediaKey", null)
            ?: prefs.getString("pref_global_crop_mode", "autocrop")
            ?: "autocrop"
        currentAspectIndex = aspectRatios.indexOfFirst { it.first == initialCropMode }.let { if (it >= 0) it else 1 }
        val initialCropLabel = aspectRatios[currentAspectIndex].second

        // Aspect / Auto Crop Pill
        cropBtn = TextView(this).apply {
            text = "🔲 $initialCropLabel"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@PlayerActivity, "#26FFFFFF", 14, "#20FFFFFF", 1)
            val pV = dp(6)
            val pH = dp(12)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, dp(6), 0) }
            setOnClickListener {
                cycleCropMode()
                resetHideTimer()
            }
        }
        actionsRow.addView(cropBtn)

        // Screen Rotation Pill
        rotateBtn = TextView(this).apply {
            text = "🔄 Rotate"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UITheme.createCardShape(this@PlayerActivity, "#26FFFFFF", 14, "#20FFFFFF", 1)
            val pV = dp(6)
            val pH = dp(12)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, dp(6), 0) }
            setOnClickListener {
                toggleRotation()
                resetHideTimer()
            }
        }
        actionsRow.addView(rotateBtn)

        bottomContainer.addView(actionsRow)
        controlsOverlay.addView(bottomContainer)

        root.addView(controlsOverlay)
        root.addView(lockedHud)
        setContentView(root)

        // Setup gestures (Single tap show/hide, double tap seek, vertical volume/brightness swipe)
        setupTouchGestures()
        resetHideTimer()
    }

    private fun setupTouchGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) {
                    showUnlockPrompt()
                    return true
                }
                val width = resources.displayMetrics.widthPixels
                if (e.x < width * 0.35f) {
                    seekRelative(-10.0)
                    showSeekFeedback(isForward = false)
                } else if (e.x > width * 0.65f) {
                    seekRelative(10.0)
                    showSeekFeedback(isForward = true)
                } else {
                    togglePlayPause()
                }
                resetHideTimer()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isLocked || e1 == null) return false
                val width = resources.displayMetrics.widthPixels
                val height = resources.displayMetrics.heightPixels

                if (kotlin.math.abs(distanceY) > kotlin.math.abs(distanceX)) {
                    if (e1.x < width * 0.5f) {
                        // Left side vertical swipe -> Brightness
                        adjustBrightness(distanceY / (height * 1.5f))
                    } else {
                        // Right side vertical swipe -> Volume
                        adjustVolume(distanceY / (height * 1.5f))
                    }
                    return true
                }
                return false
            }
        })

        controlsOverlay.setOnTouchListener { _, event ->
            if (isLocked) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (lockedHud.visibility == View.VISIBLE) {
                        val rect = android.graphics.Rect()
                        unlockPromptBtn.getGlobalVisibleRect(rect)
                        if (rect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                            unlockControls()
                            return@setOnTouchListener true
                        }
                    }
                    showUnlockPrompt()
                }
                return@setOnTouchListener true
            }
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private val seekFeedbackHandler = Handler(Looper.getMainLooper())
    private val seekFeedbackLeftRunnable = Runnable { seekFeedbackLeft.visibility = View.GONE }
    private val seekFeedbackRightRunnable = Runnable { seekFeedbackRight.visibility = View.GONE }

    private fun showSeekFeedback(isForward: Boolean) {
        val target = if (isForward) seekFeedbackRight else seekFeedbackLeft
        val runnable = if (isForward) seekFeedbackRightRunnable else seekFeedbackLeftRunnable
        target.visibility = View.VISIBLE
        seekFeedbackHandler.removeCallbacks(runnable)
        seekFeedbackHandler.postDelayed(runnable, 600)
    }

    private fun adjustVolume(deltaRatio: Float) {
        val am = audioManager ?: return
        val max = maxVolume
        if (max <= 0) return
        volumeAccumulator += deltaRatio * max
        if (kotlin.math.abs(volumeAccumulator) >= 1.0f) {
            val change = volumeAccumulator.toInt()
            volumeAccumulator -= change
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = (current + change).coerceIn(0, max)
            if (target != current) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
            val percent = ((target.toFloat() / max) * 100).toInt()
            val icon = if (target == 0) "🔇" else "🔊"
            showHUD(icon, percent)
        }
    }

    private fun adjustBrightness(deltaRatio: Float) {
        if (currentBrightness < 0f) {
            val lp = window.attributes
            currentBrightness = if (lp.screenBrightness >= 0f) {
                lp.screenBrightness
            } else {
                try {
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                } catch (_: Exception) {
                    0.5f
                }
            }
        }
        currentBrightness = (currentBrightness + deltaRatio).coerceIn(0.01f, 1.0f)
        val lp = window.attributes
        lp.screenBrightness = currentBrightness
        window.attributes = lp
        val percent = (currentBrightness * 100).toInt()
        showHUD("☀️", percent)
    }

    private fun showHUD(icon: String, percent: Int) {
        hudIcon.text = icon
        hudBar.visibility = View.VISIBLE
        hudBar.progress = percent
        hudText.text = "$percent%"
        hudPill.visibility = View.VISIBLE
        hudHandler.removeCallbacks(hudHideRunnable)
        hudHandler.postDelayed(hudHideRunnable, 1000)
    }

    private fun showHudMessage(icon: String, message: String) {
        hudIcon.text = icon
        hudBar.visibility = View.GONE
        hudText.text = message
        hudPill.visibility = View.VISIBLE
        hudHandler.removeCallbacks(hudHideRunnable)
        hudHandler.postDelayed(hudHideRunnable, 1600)
    }

    // ── Dynamic Range Compression (DRC / Night Mode / Audio Compressor) ──
    private fun toggleDrc() {
        applyDrc(!isDrcEnabled, notifyUser = true)
    }

    private fun applyDrc(enable: Boolean, notifyUser: Boolean) {
        isDrcEnabled = enable
        try {
            getSharedPreferences("teleflix_player_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("drc_enabled", isDrcEnabled)
                .apply()
        } catch (_: Exception) {}

        val player = mpv
        if (player != null) {
            try {
                if (isDrcEnabled) {
                    player.setPropertyDouble("volume-gain", 7.0)
                    player.setPropertyString("ad-lavc-ac3drc", "1.0")
                    player.setPropertyString("audio-normalize-downmix", "yes")
                } else {
                    player.setPropertyDouble("volume-gain", 0.0)
                    player.setPropertyString("ad-lavc-ac3drc", "0.0")
                    player.setPropertyString("audio-normalize-downmix", "no")
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Failed to apply MPV DRC properties: ${e.message}")
            }
        }

        try {
            if (isDrcEnabled) {
                loudnessEnhancer?.setTargetGain(600)
                loudnessEnhancer?.enabled = true
            } else {
                loudnessEnhancer?.enabled = false
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Failed to apply LoudnessEnhancer: ${e.message}")
        }

        updateDrcButtonVisual()

        if (notifyUser) {
            if (isDrcEnabled) {
                showHudMessage("🎚️", "DRC: ON (Dialogue Boosted +7dB)")
            } else {
                showHudMessage("🎚️", "DRC: OFF (Original Sound)")
            }
        }
    }

    private fun updateDrcButtonVisual() {
        if (::drcBtn.isInitialized) {
            if (isDrcEnabled) {
                drcBtn.text = "🎚️ DRC ON"
                drcBtn.setTextColor(Color.WHITE)
                drcBtn.background = UITheme.createCardShape(this, NETFLIX_RED, 14, "#FFFFFF", 0)
            } else {
                drcBtn.text = "🎚️ DRC"
                drcBtn.setTextColor(Color.parseColor("#E5E7EB"))
                drcBtn.background = UITheme.createCardShape(this, "#26FFFFFF", 14, "#20FFFFFF", 1)
            }
        }
    }

    private fun toggleControls() {
        if (isLocked) {
            showUnlockPrompt()
            return
        }
        if (areControlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        if (isLocked) {
            showUnlockPrompt()
            return
        }
        areControlsVisible = true
        topBar.visibility = View.VISIBLE
        centerControls.visibility = View.VISIBLE
        bottomContainer.visibility = View.VISIBLE
        resetHideTimer()
    }

    private fun hideControls() {
        if (!isUserSeeking) {
            areControlsVisible = false
            topBar.visibility = View.GONE
            centerControls.visibility = View.GONE
            bottomContainer.visibility = View.GONE
            hideSystemUI()
        }
    }

    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        if (areControlsVisible && !isLocked) {
            val delay = if (isPlaying) 3500L else 6000L
            hideHandler.postDelayed(hideRunnable, delay)
        }
    }

    private fun lockControls() {
        isLocked = true
        areControlsVisible = false
        topBar.visibility = View.GONE
        centerControls.visibility = View.GONE
        bottomContainer.visibility = View.GONE
        hideSystemUI()
        showUnlockPrompt()
    }

    private fun unlockControls() {
        isLocked = false
        unlockHandler.removeCallbacks(unlockHideRunnable)
        lockedHud.animate().cancel()
        lockedHud.visibility = View.GONE
        showControls()
    }

    private fun showUnlockPrompt() {
        unlockHandler.removeCallbacks(unlockHideRunnable)
        lockedHud.animate().cancel()
        lockedHud.alpha = 1f
        lockedHud.visibility = View.VISIBLE
        lockedHud.bringToFront()
        unlockHandler.postDelayed(unlockHideRunnable, 2500)
    }

    // ── Unified Netflix Audio & Subtitles Modal (Silent, Clean, Zero Popups) ──
    data class TrackInfo(
        val id: Int,
        val type: String,
        val title: String,
        val lang: String,
        val codec: String,
        val isSelected: Boolean
    )

    private fun getTracks(type: String): List<TrackInfo> {
        val player = mpv ?: return emptyList()
        val count = player.getPropertyInt("track-list/count")
            ?: player.getPropertyString("track-list/count")?.toIntOrNull()
            ?: 0
        if (count <= 0) return emptyList()

        val list = mutableListOf<TrackInfo>()
        for (i in 0 until count) {
            val tType = player.getPropertyString("track-list/$i/type") ?: ""
            if (tType.equals(type, ignoreCase = true)) {
                val id = player.getPropertyInt("track-list/$i/id")
                    ?: player.getPropertyString("track-list/$i/id")?.toIntOrNull()
                    ?: (i + 1)
                val title = player.getPropertyString("track-list/$i/title") ?: ""
                val lang = player.getPropertyString("track-list/$i/lang") ?: ""
                val codec = player.getPropertyString("track-list/$i/codec") ?: ""
                val selected = player.getPropertyBoolean("track-list/$i/selected")
                    ?: (player.getPropertyString("track-list/$i/selected") == "yes")
                list.add(TrackInfo(id, tType, title, lang, codec, selected))
            }
        }
        return list
    }

    private fun showNetflixAudioSubDialog() {
        val player = mpv ?: return
        val audioTracks = getTracks("audio")
        val subTracks = getTracks("sub")

        val currentAid = player.getPropertyInt("aid")
            ?: player.getPropertyString("aid")?.toIntOrNull()
            ?: -1
        val currentSidStr = player.getPropertyString("sid") ?: "no"
        val currentSid = currentSidStr.toIntOrNull() ?: -1
        val isSubOff = currentSidStr.equals("no", ignoreCase = true) || currentSid <= 0

        // Custom Netflix Dark Modal Layout
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181818"))
            val pad = dp(18)
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Left Column: AUDIO
        val audioColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(12), 0)
            }
        }
        val audioHeader = TextView(this).apply {
            text = "AUDIO"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            val pB = dp(10)
            setPadding(0, 0, 0, pB)
        }
        audioColumn.addView(audioHeader)

        val audioScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220))
        }
        val audioListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        var alertDialog: AlertDialog? = null

        audioTracks.forEach { track ->
            val langName = formatLanguage(track.lang)
            val title = if (track.title.isNotBlank()) " (${track.title})" else ""
            val codec = if (track.codec.isNotBlank()) " [${track.codec.uppercase()}]" else ""
            val label = if (langName.isNotBlank()) "$langName$title$codec" else "Audio #${track.id}$title$codec"
            val isCurrent = track.id == currentAid || track.isSelected

            val row = TextView(this).apply {
                text = if (isCurrent) "✓  $label" else "    $label"
                textSize = 13f
                setTextColor(if (isCurrent) Color.parseColor(NETFLIX_RED) else Color.parseColor("#E5E7EB"))
                setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                val pV = dp(10)
                setPadding(dp(4), pV, dp(4), pV)
                setOnClickListener {
                    try {
                        player.setPropertyInt("aid", track.id)
                    } catch (_: Throwable) {
                        player.setPropertyString("aid", track.id.toString())
                    }
                    val prefs = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putInt("audio_track_id_$mediaKey", track.id)
                        .putString("audio_track_lang_$mediaKey", track.lang)
                        .putString("audio_track_title_$mediaKey", track.title)
                        .putString("pref_global_audio_lang", track.lang)
                        .putString("pref_global_audio_title", track.title)
                        .apply()
                    alertDialog?.dismiss()
                    resetHideTimer()
                }
            }
            audioListContainer.addView(row)
        }
        if (audioTracks.isEmpty()) {
            val emptyAudio = TextView(this).apply {
                text = "Default Audio Stream"
                textSize = 13f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            audioListContainer.addView(emptyAudio)
        }
        audioScroll.addView(audioListContainer)
        audioColumn.addView(audioScroll)

        // Dynamic Range Compression (DRC) Toggle Card (Mpv-infinity)
        val drcCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UITheme.createCardShape(this@PlayerActivity, if (isDrcEnabled) "#33E50914" else "#20FFFFFF", 12, if (isDrcEnabled) NETFLIX_RED else "#33FFFFFF", 1)
            val pV = dp(8)
            val pH = dp(10)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, 0) }
            isClickable = true
            isFocusable = true
        }

        val drcHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val drcTitle = TextView(this).apply {
            text = "🎚️ Dynamic Range (DRC)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        drcHeaderRow.addView(drcTitle)

        val drcBadge = TextView(this).apply {
            text = if (isDrcEnabled) "ON" else "OFF"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = UITheme.createCardShape(this@PlayerActivity, if (isDrcEnabled) NETFLIX_RED else "#40FFFFFF", 8, "#FFFFFF", 0)
            val pBadgeH = dp(8)
            val pBadgeV = dp(3)
            setPadding(pBadgeH, pBadgeV, pBadgeH, pBadgeV)
        }
        drcHeaderRow.addView(drcBadge)
        drcCard.addView(drcHeaderRow)

        val drcDesc = TextView(this).apply {
            text = "Boost dialogue (+7dB) & tame loud sounds without distortion"
            textSize = 10f
            setTextColor(Color.parseColor("#9CA3AF"))
            val pT = dp(3)
            setPadding(0, pT, 0, 0)
        }
        drcCard.addView(drcDesc)

        drcCard.setOnClickListener {
            toggleDrc()
            drcBadge.text = if (isDrcEnabled) "ON" else "OFF"
            drcBadge.background = UITheme.createCardShape(this@PlayerActivity, if (isDrcEnabled) NETFLIX_RED else "#40FFFFFF", 8, "#FFFFFF", 0)
            drcCard.background = UITheme.createCardShape(this@PlayerActivity, if (isDrcEnabled) "#33E50914" else "#20FFFFFF", 12, if (isDrcEnabled) NETFLIX_RED else "#33FFFFFF", 1)
        }
        audioColumn.addView(drcCard)

        dialogView.addView(audioColumn)

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialogView.addView(divider)

        // Right Column: SUBTITLES
        val subColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(12), 0, 0, 0)
            }
        }
        val subHeader = TextView(this).apply {
            text = "SUBTITLES"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            val pB = dp(10)
            setPadding(0, 0, 0, pB)
        }
        subColumn.addView(subHeader)

        val subScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220))
        }
        val subListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Off option
        val offRow = TextView(this).apply {
            text = if (isSubOff) "✓  Off" else "    Off"
            textSize = 13f
            setTextColor(if (isSubOff) Color.parseColor(NETFLIX_RED) else Color.parseColor("#E5E7EB"))
            setTypeface(null, if (isSubOff) Typeface.BOLD else Typeface.NORMAL)
            val pV = dp(10)
            setPadding(dp(4), pV, dp(4), pV)
            setOnClickListener {
                player.setPropertyString("sid", "no")
                val prefs = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("sub_track_id_$mediaKey", "no")
                    .putString("sub_track_lang_$mediaKey", "no")
                    .putBoolean("pref_global_sub_enabled", false)
                    .putString("pref_global_sub_lang", "no")
                    .apply()
                alertDialog?.dismiss()
                resetHideTimer()
            }
        }
        subListContainer.addView(offRow)

        subTracks.forEach { track ->
            val langName = formatLanguage(track.lang)
            val title = if (track.title.isNotBlank()) " (${track.title})" else ""
            val codec = if (track.codec.isNotBlank()) " [${track.codec.uppercase()}]" else ""
            val label = if (langName.isNotBlank()) "$langName$title$codec" else "Subtitle #${track.id}$title$codec"
            val isCurrent = !isSubOff && (track.id == currentSid || track.isSelected)

            val row = TextView(this).apply {
                text = if (isCurrent) "✓  $label" else "    $label"
                textSize = 13f
                setTextColor(if (isCurrent) Color.parseColor(NETFLIX_RED) else Color.parseColor("#E5E7EB"))
                setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                val pV = dp(10)
                setPadding(dp(4), pV, dp(4), pV)
                setOnClickListener {
                    try {
                        player.setPropertyInt("sid", track.id)
                    } catch (_: Throwable) {
                        player.setPropertyString("sid", track.id.toString())
                    }
                    val prefs = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("sub_track_id_$mediaKey", track.id.toString())
                        .putString("sub_track_lang_$mediaKey", track.lang)
                        .putString("sub_track_title_$mediaKey", track.title)
                        .putBoolean("pref_global_sub_enabled", true)
                        .putString("pref_global_sub_lang", track.lang)
                        .putString("pref_global_sub_title", track.title)
                        .apply()
                    alertDialog?.dismiss()
                    resetHideTimer()
                }
            }
            subListContainer.addView(row)
        }
        subScroll.addView(subListContainer)
        subColumn.addView(subScroll)
        dialogView.addView(subColumn)

        alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        alertDialog.show()
    }

    private fun formatLanguage(code: String): String {
        if (code.isBlank()) return ""
        return when (code.lowercase().trim()) {
            "hin", "hi" -> "Hindi"
            "eng", "en" -> "English"
            "tam", "ta" -> "Tamil"
            "tel", "te" -> "Telugu"
            "mal", "ml" -> "Malayalam"
            "kan", "kn" -> "Kannada"
            "ben", "bn" -> "Bengali"
            "pan", "pa" -> "Punjabi"
            "mar", "mr" -> "Marathi"
            "guj", "gu" -> "Gujarati"
            "urd", "ur" -> "Urdu"
            "spa", "es" -> "Spanish"
            "fra", "fre", "fr" -> "French"
            "deu", "ger", "de" -> "German"
            "ita", "it" -> "Italian"
            "por", "pt" -> "Portuguese"
            "rus", "ru" -> "Russian"
            "jpn", "ja" -> "Japanese"
            "kor", "ko" -> "Korean"
            "zho", "chi", "zh" -> "Chinese"
            "ara", "ar" -> "Arabic"
            "tur", "tr" -> "Turkish"
            "ind", "id" -> "Indonesian"
            "vie", "vi" -> "Vietnamese"
            "tha", "th" -> "Thai"
            "und" -> "Undetermined"
            else -> code.uppercase()
        }
    }

    // ── MPV Initialization & Setup ──
    private fun initMPV() {
        try {
            val player = MPVLib.create(applicationContext) ?: run {
                finish()
                return
            }
            player.setOptionString("vo", "gpu")
            player.setOptionString("hwdec", "auto")
            player.setOptionString("hwdec-codecs", "all")
            player.setOptionString("tls-verify", "no")
            player.setOptionString("demuxer-lavf-o", "reconnect_streamed=1,reconnect_delay_max=5")
            player.setOptionString("force-window", "yes")
            player.setOptionString("keep-open", "yes")
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val audioSessionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioManager?.generateAudioSessionId() ?: 0
            } else {
                0
            }

            if (audioSessionId > 0) {
                player.setOptionString("ao", "audiotrack:session-id=$audioSessionId,opensles")
                try {
                    loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(audioSessionId)
                } catch (e: Exception) {
                    android.util.Log.e("PlayerActivity", "Could not init LoudnessEnhancer: ${e.message}")
                }
            } else {
                player.setOptionString("ao", "audiotrack,opensles")
            }
            player.setOptionString("audio-client-name", "Teleflix")

            // ── Software Volume Limits & Dolby DRC Options ──
            player.setOptionString("volume-max", "200")
            player.setOptionString("volume-gain-max", "150")
            player.setOptionString("volume-gain-min", "-150")

            // ── Dynamic Range Compression (DRC) & Dialogue Boost ──
            if (isDrcEnabled) {
                player.setOptionString("volume-gain", "7.0")
                player.setOptionString("ad-lavc-ac3drc", "1.0")
                player.setOptionString("audio-normalize-downmix", "yes")
                try {
                    loudnessEnhancer?.setTargetGain(600)
                    loudnessEnhancer?.enabled = true
                } catch (_: Throwable) {}
            } else {
                player.setOptionString("volume-gain", "0.0")
                player.setOptionString("ad-lavc-ac3drc", "0.0")
                player.setOptionString("audio-normalize-downmix", "no")
            }

            // ── Subtitle Static Font & Netflix High-Legibility Styling (Fixed Android 14/15 Zero-Width Space Bug) ──
            setupSubtitleFonts(player)

            if (pendingResumeSeekSec > 3.0) {
                player.setOptionString("start", "$pendingResumeSeekSec")
            }

            player.init()

            player.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            player.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            player.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            player.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)

            player.addObserver(this)
            mpv = player

            surfaceHolder?.surface?.let { surf ->
                if (surf.isValid) {
                    player.attachSurface(surf)
                    if (!isMediaLoaded) {
                        loadMedia()
                    }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("PlayerActivity", "Failed to initialize MPV: ${e.message}", e)
        }
    }

    // ── Subtitle Static Font & Netflix High-Legibility Styling ──
    private fun setupSubtitleFonts(player: MPVLib) {
        val fontsDir = java.io.File(filesDir, "fonts").apply { mkdirs() }
        val rootSubfont = java.io.File(filesDir, "subfont.ttf")

        val standardFontAliases = listOf(
            "subfont.ttf",
            "SourceSansPro-SemiBold.ttf",
            "Source Sans Pro SemiBold.ttf",
            "Source Sans Pro.ttf",
            "sub.ttf",
            "Arial.ttf",
            "Helvetica.ttf",
            "sans-serif.ttf",
            "Roboto.ttf",
            "Default.ttf"
        )

        try {
            // 1. Ensure root subfont.ttf exists in filesDir (libmpv fallback font)
            if (!rootSubfont.exists() || rootSubfont.length() < 1000L) {
                val assetStream = try {
                    assets.open("subfont.ttf")
                } catch (_: Exception) {
                    assets.open("fonts/sub.ttf")
                }
                assetStream.use { input ->
                    rootSubfont.outputStream().use { output -> input.copyTo(output) }
                }
            }

            // 2. Populate fontsDir with standard fallback aliases matching the clean static TrueType font
            if (rootSubfont.exists() && rootSubfont.length() > 0L) {
                for (name in standardFontAliases) {
                    val target = java.io.File(fontsDir, name)
                    if (!target.exists() || target.length() != rootSubfont.length()) {
                        try {
                            rootSubfont.inputStream().use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Error setting up subtitle fonts: ${e.message}")
        }

        // 3. Scan system fonts for non-variable static fonts (e.g. CJK, Arabic, Devanagari) to preserve foreign script coverage
        try {
            val sysFontDirs = listOf("/system/fonts", "/system/font", "/product/fonts")
            for (dirPath in sysFontDirs) {
                val d = java.io.File(dirPath)
                if (d.exists() && d.isDirectory) {
                    val files = d.listFiles() ?: continue
                    for (f in files) {
                        if (f.isFile && (f.name.startsWith("NotoSans") || f.name.startsWith("DroidSansFallback")) && f.extension.equals("ttf", ignoreCase = true)) {
                            val dest = java.io.File(fontsDir, f.name)
                            if (!dest.exists()) {
                                try {
                                    android.system.Os.symlink(f.absolutePath, dest.absolutePath)
                                } catch (_: Throwable) {
                                    // Fallback if symlink is not permitted
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 4. Configure MPV native subtitle rendering engine
        player.setOptionString("config-dir", filesDir.absolutePath)
        player.setOptionString("sub-fonts-dir", fontsDir.absolutePath)
        player.setOptionString("sub-font", "Source Sans Pro SemiBold")
        player.setOptionString("secondary-sub-font", "Source Sans Pro SemiBold")
        player.setOptionString("sub-shaper", "complex")
        player.setOptionString("sub-font-provider", "none")
        player.setOptionString("sub-ass-override", "force")
        player.setOptionString("sub-ass-force-style", "FontName=Source Sans Pro SemiBold,FontSize=42,Bold=1,Outline=2.2,Shadow=1.2,MarginV=30")
        player.setOptionString("sub-ass-style-overrides", "FontName=Source Sans Pro SemiBold")
        player.setOptionString("sub-font-size", "42")
        player.setOptionString("sub-bold", "yes")
        player.setOptionString("sub-color", "#FFFFFFFF")
        player.setOptionString("sub-border-color", "#E6000000")
        player.setOptionString("sub-border-size", "2.2")
        player.setOptionString("sub-shadow-offset", "1.2")
        player.setOptionString("sub-shadow-color", "#80000000")
        player.setOptionString("sub-pos", "95")
        player.setOptionString("sub-auto", "fuzzy")
        player.setOptionString("sub-ass-justify", "yes")
        player.setOptionString("sub-spacing", "0.5")
        player.setOptionString("embeddedfonts", "yes")
        player.setOptionString("sub-codepage", "auto")
    }

    // ── Persistent Audio & Subtitle Selection ──
    private fun restoreTrackPreferences() {
        val player = mpv ?: return
        val prefs = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)

        val audioTracks = getTracks("audio")
        val subTracks = getTracks("sub")

        if (audioTracks.isEmpty() && subTracks.isEmpty()) {
            window.decorView.postDelayed({
                restoreTrackPreferences()
            }, 350)
            return
        }

        // 1. Restore Audio Track
        if (audioTracks.isNotEmpty()) {
            val savedAid = prefs.getInt("audio_track_id_$mediaKey", -1)
            val savedAudioLang = prefs.getString("audio_track_lang_$mediaKey", null)
            val savedAudioTitle = prefs.getString("audio_track_title_$mediaKey", null)
            val globalAudioLang = prefs.getString("pref_global_audio_lang", null)

            var targetAid: Int? = null

            // Priority 1: Exact saved track ID for this media
            if (savedAid > 0 && audioTracks.any { it.id == savedAid }) {
                targetAid = savedAid
            }
            // Priority 2: Saved language & title for this media
            else if (!savedAudioLang.isNullOrBlank() || !savedAudioTitle.isNullOrBlank()) {
                targetAid = audioTracks.firstOrNull {
                    (!savedAudioLang.isNullOrBlank() && it.lang.equals(savedAudioLang, ignoreCase = true)) ||
                    (!savedAudioTitle.isNullOrBlank() && it.title.equals(savedAudioTitle, ignoreCase = true))
                }?.id
            }
            // Priority 3: User's global preferred audio language
            else if (!globalAudioLang.isNullOrBlank()) {
                targetAid = audioTracks.firstOrNull {
                    it.lang.equals(globalAudioLang, ignoreCase = true)
                }?.id
            }

            if (targetAid != null) {
                try {
                    player.setPropertyInt("aid", targetAid)
                } catch (_: Throwable) {
                    player.setPropertyString("aid", targetAid.toString())
                }
            }
        }

        // 2. Restore Subtitle Track
        val savedSidStr = prefs.getString("sub_track_id_$mediaKey", null)
        val savedSubLang = prefs.getString("sub_track_lang_$mediaKey", null)
        val savedSubTitle = prefs.getString("sub_track_title_$mediaKey", null)
        val globalSubEnabled = prefs.getBoolean("pref_global_sub_enabled", true)
        val globalSubLang = prefs.getString("pref_global_sub_lang", null)

        if (savedSidStr == "no" || (!prefs.contains("sub_track_id_$mediaKey") && !globalSubEnabled)) {
            // User explicitly chose Off for this media, or globally disabled subtitles
            player.setPropertyString("sid", "no")
        } else if (subTracks.isNotEmpty()) {
            val savedSid = savedSidStr?.toIntOrNull()
            var targetSid: Int? = null

            // Priority 1: Exact saved subtitle ID for this media
            if (savedSid != null && savedSid > 0 && subTracks.any { it.id == savedSid }) {
                targetSid = savedSid
            }
            // Priority 2: Saved language & title for this media
            else if (!savedSubLang.isNullOrBlank() || !savedSubTitle.isNullOrBlank()) {
                targetSid = subTracks.firstOrNull {
                    (!savedSubLang.isNullOrBlank() && it.lang.equals(savedSubLang, ignoreCase = true)) ||
                    (!savedSubTitle.isNullOrBlank() && it.title.equals(savedSubTitle, ignoreCase = true))
                }?.id
            }
            // Priority 3: User's global preferred subtitle language
            else if (!globalSubLang.isNullOrBlank() && globalSubLang != "no") {
                targetSid = subTracks.firstOrNull {
                    it.lang.equals(globalSubLang, ignoreCase = true)
                }?.id
            }

            if (targetSid != null) {
                try {
                    player.setPropertyInt("sid", targetSid)
                } catch (_: Throwable) {
                    player.setPropertyString("sid", targetSid.toString())
                }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val player = mpv ?: return
        if (holder.surface.isValid) {
            try {
                player.attachSurface(holder.surface)
                if (!isMediaLoaded) {
                    loadMedia()
                }
            } catch (e: Throwable) {
                android.util.Log.e("PlayerActivity", "Error attaching surface: ${e.message}")
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try {
            mpv?.detachSurface()
        } catch (_: Throwable) {}
    }

    private fun loadMedia() {
        val player = mpv ?: return
        if (isMediaLoaded) return
        isMediaLoaded = true
        trackPreferencesRestored = false
        bufferingProgress.visibility = View.VISIBLE
        player.command(arrayOf("loadfile", streamUrl))
    }

    private fun togglePlayPause() {
        val player = mpv ?: return
        val currentPause = player.getPropertyBoolean("pause") ?: false
        player.setPropertyBoolean("pause", !currentPause)
    }

    private fun seekRelative(deltaSeconds: Double) {
        val player = mpv ?: return
        val target = maxOf(0.0, currentPosSec + deltaSeconds)
        player.command(arrayOf("seek", target.toString(), "absolute"))
    }

    private fun seekAbsolute(targetSeconds: Double) {
        val player = mpv ?: return
        player.command(arrayOf("seek", targetSeconds.toString(), "absolute"))
    }

    private fun cycleSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
        val speed = speedOptions[currentSpeedIndex]
        speedBtn.text = "⚡ Speed (${speed}X)"
        mpv?.setPropertyDouble("speed", speed)
    }

    private fun isAudioOnlyMedia(): Boolean {
        val player = mpv ?: return false

        // 1. Check if vid property is explicitly "no" or empty
        val vid = player.getPropertyString("vid")
        if (vid != null && (vid.equals("no", ignoreCase = true) || vid == "0")) {
            return true
        }

        // 2. Check if video is static album art
        val isAlbumArt = player.getPropertyBoolean("video-params/albumart")
            ?: (player.getPropertyString("video-params/albumart") == "yes")
        if (isAlbumArt) {
            return true
        }

        // 3. Inspect track list for actual video streams
        val trackCount = player.getPropertyInt("track-list/count")
            ?: player.getPropertyString("track-list/count")?.toIntOrNull()
            ?: 0
        if (trackCount > 0) {
            var hasRealVideo = false
            for (i in 0 until trackCount) {
                val tType = player.getPropertyString("track-list/$i/type") ?: ""
                if (tType.equals("video", ignoreCase = true)) {
                    val albumArt = player.getPropertyBoolean("track-list/$i/albumart")
                        ?: (player.getPropertyString("track-list/$i/albumart") == "yes")
                    if (!albumArt) {
                        hasRealVideo = true
                        break
                    }
                }
            }
            if (!hasRealVideo) return true
        }

        // 4. Check video dimensions
        val vw = player.getPropertyInt("video-params/w") ?: player.getPropertyInt("dwidth") ?: 0
        val vh = player.getPropertyInt("video-params/h") ?: player.getPropertyInt("dheight") ?: 0
        if (vw <= 0 || vh <= 0) {
            return true
        }

        // 5. Check URL/filename extension fallback
        val lower = (streamUrl + " " + videoTitle).lowercase()
        if (lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".flac") ||
            lower.contains(".aac") || lower.contains(".wav") || lower.contains(".ogg") ||
            lower.contains(".opus")) {
            if (isAlbumArt || vw <= 0 || vw == vh) {
                return true
            }
        }

        return false
    }

    private fun cycleCropMode() {
        if (isAudioOnlyMedia()) {
            showHudMessage("🎵", "Audio Track: Cropping Disabled")
            return
        }
        currentAspectIndex = (currentAspectIndex + 1) % aspectRatios.size
        val (mode, label) = aspectRatios[currentAspectIndex]
        cropBtn.text = "🔲 $label"

        // Persist crop mode selection per media and globally
        val prefs = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("crop_mode_$mediaKey", mode)
            .putString("pref_global_crop_mode", mode)
            .apply()

        applyCropMode(mode, notifyUser = true)
    }

    private fun restoreCropPreference() {
        if (isAudioOnlyMedia()) return
        val prefs = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
        val savedMode = prefs.getString("crop_mode_$mediaKey", null)
            ?: prefs.getString("pref_global_crop_mode", "autocrop")
            ?: "autocrop"

        val index = aspectRatios.indexOfFirst { it.first == savedMode }
        currentAspectIndex = if (index >= 0) index else 1
        val (mode, label) = aspectRatios[currentAspectIndex]
        if (::cropBtn.isInitialized) {
            cropBtn.text = "🔲 $label"
        }
        applyCropMode(mode, notifyUser = false)
    }

    // ── Auto Crop Black Bars Engine (Mpv-infinity architecture) ──
    // Strictly crops above and below (top & bottom letterbox bars) - NEVER crops left or right
    private fun applyCropMode(mode: String, notifyUser: Boolean = true) {
        val player = mpv ?: return

        // NEVER apply video crop or video filter on audio/music tracks - prevents pipeline stall
        if (isAudioOnlyMedia()) {
            player.setPropertyString("video-crop", "")
            player.command(arrayOf("vf", "clr", ""))
            player.setPropertyDouble("panscan", 0.0)
            player.setPropertyString("video-aspect-override", "-1")
            cropBtn.text = "🎵 Audio"
            return
        }

        when (mode) {
            "fit" -> {
                // Pristine original aspect ratio: ZERO zoom, ZERO crop
                player.setPropertyString("video-crop", "")
                player.command(arrayOf("vf", "clr", ""))
                player.setPropertyDouble("panscan", 0.0)
                player.setPropertyString("video-aspect-override", "-1")
                if (notifyUser) showHudMessage("🔲", "Fit: Original (No Zoom, No Crop)")
            }
            "autocrop" -> {
                // 1. Reset previous crop and ensure zero zoom
                player.setPropertyString("video-crop", "")
                player.command(arrayOf("vf", "clr", ""))
                player.setPropertyDouble("panscan", 0.0)
                player.setPropertyString("video-aspect-override", "-1")

                // 2. Add cropdetect filter
                player.command(arrayOf("vf", "set", "@cropdetect:lavfi=cropdetect=limit=24/255:round=2:reset=1"))

                // 3. Inspect detected non-black dimensions after 800ms
                hideHandler.postDelayed({
                    try {
                        if (isAudioOnlyMedia()) {
                            player.setPropertyString("video-crop", "")
                            player.command(arrayOf("vf", "clr", ""))
                            return@postDelayed
                        }

                        val cropH = player.getPropertyInt("vf-metadata/@cropdetect/lavfi.cropdetect.h") ?: 0
                        val cropY = player.getPropertyInt("vf-metadata/@cropdetect/lavfi.cropdetect.y") ?: 0
                        player.command(arrayOf("vf", "remove", "@cropdetect"))

                        val fullW = player.getPropertyInt("video-params/w")
                            ?: player.getPropertyInt("dwidth")
                            ?: 1920
                        val fullH = player.getPropertyInt("video-params/h")
                            ?: player.getPropertyInt("dheight")
                            ?: 1080

                        // STRICTLY crop above and below (top and bottom black bars). NEVER crop left or right!
                        // Only crop if there are real black bars on top/bottom (at least 12px)
                        if (cropH in 100 until (fullH - 12)) {
                            val evenH = (cropH / 2) * 2
                            val evenY = (cropY / 2) * 2
                            // w is fullW (0 left/right crop), x is 0, panscan is 0.0 (NO ZOOM)
                            player.setPropertyString("video-crop", "${fullW}x${evenH}+0+${evenY}")
                            player.setPropertyDouble("panscan", 0.0)
                            player.setPropertyString("video-aspect-override", "-1")
                            if (notifyUser) showHudMessage("🔲", "Auto Crop: Top & Bottom Bars Removed")
                        } else {
                            // No black bars: keep 100% original uncropped picture with zero zoom
                            player.setPropertyString("video-crop", "")
                            player.setPropertyDouble("panscan", 0.0)
                            player.setPropertyString("video-aspect-override", "-1")
                            if (notifyUser) showHudMessage("🔲", "Original Fit (No Black Bars)")
                        }
                    } catch (_: Throwable) {
                        player.setPropertyString("video-crop", "")
                        player.setPropertyDouble("panscan", 0.0)
                        player.setPropertyString("video-aspect-override", "-1")
                    }
                }, 800)
            }
            "fill" -> {
                // Zoom & crop to fill 100% of display
                player.setPropertyString("video-crop", "")
                player.command(arrayOf("vf", "clr", ""))
                player.setPropertyDouble("panscan", 1.0)
                player.setPropertyString("video-aspect-override", "-1")
                if (notifyUser) showHudMessage("🔲", "Fill: Zoom to Screen")
            }
            "16:9" -> {
                player.setPropertyString("video-crop", "")
                player.command(arrayOf("vf", "clr", ""))
                player.setPropertyDouble("panscan", 0.0)
                player.setPropertyString("video-aspect-override", "16:9")
                if (notifyUser) showHudMessage("🔲", "Aspect: 16:9")
            }
            "stretch" -> {
                player.setPropertyString("video-crop", "")
                player.command(arrayOf("vf", "clr", ""))
                player.setPropertyDouble("panscan", 0.0)
                val dm = resources.displayMetrics
                val w = maxOf(dm.widthPixels, dm.heightPixels).toDouble()
                val h = minOf(dm.widthPixels, dm.heightPixels).toDouble()
                val ratio = if (h > 0) w / h else (16.0 / 9.0)
                player.setPropertyString("video-aspect-override", String.format(java.util.Locale.US, "%.4f", ratio))
                if (notifyUser) showHudMessage("🔲", "Stretch to Screen")
            }
        }
    }

    private fun toggleRotation() {
        isLandscape = !isLandscape
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    // ── MPV Event Observer Callback Implementations ──
    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {}

    override fun eventProperty(property: String, value: Double) {
        runOnUiThread {
            when (property) {
                "time-pos" -> {
                    currentPosSec = value
                    if (!isUserSeeking) {
                        timeText.text = formatSecToTime(value.toLong())
                        if (durationSec > 0) {
                            val progress = ((value / durationSec) * 1000).toInt()
                            seekBar.progress = progress
                            val rem = maxOf(0.0, durationSec - value)
                            remainingTimeText.text = "-${formatSecToTime(rem.toLong())}"
                        }
                        if (value > 5.0 && value.toLong() % 15L == 0L) {
                            savePlaybackPosition()
                        }
                    }
                }
                "duration" -> {
                    durationSec = value
                    val rem = maxOf(0.0, durationSec - currentPosSec)
                    remainingTimeText.text = "-${formatSecToTime(rem.toLong())}"
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        runOnUiThread {
            when (property) {
                "pause" -> {
                    isPlaying = !value
                    playPauseBtn.text = if (value) "▶" else "⏸"
                    resetHideTimer()
                    if (value) {
                        savePlaybackPosition()
                    }
                }
                "paused-for-cache" -> {
                    bufferingProgress.visibility = if (value) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun eventProperty(property: String, value: String) {}

    override fun event(eventId: Int) {
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED || eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART) {
            runOnUiThread {
                bufferingProgress.visibility = View.GONE
                isPlaying = true
                playPauseBtn.text = "⏸"
                resetHideTimer()

                val player = mpv

                if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
                    if (pendingResumeSeekSec > 3.0) {
                        val target = pendingResumeSeekSec
                        pendingResumeSeekSec = 0.0
                        currentPosSec = target
                        timeText.text = formatSecToTime(target.toLong())
                        player?.command(arrayOf("seek", target.toString(), "absolute"))
                        TeleflixLogger.log("PlayerActivity", "Executed MPV seek to resume position ${target}s")
                    }
                    if (!trackPreferencesRestored) {
                        trackPreferencesRestored = true
                        restoreTrackPreferences()
                    }
                }

                if (isAudioOnlyMedia()) {
                    // For music / audio files: ensure video filters and crop are completely cleared so audio never stalls
                    player?.setPropertyString("video-crop", "")
                    player?.command(arrayOf("vf", "clr", ""))
                    player?.setPropertyDouble("panscan", 0.0)
                    player?.setPropertyString("video-aspect-override", "-1")
                    cropBtn.text = "🎵 Audio"
                } else {
                    restoreCropPreference()
                }
            }
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
            runOnUiThread {
                isPlaying = false
                playPauseBtn.text = "▶"
                showControls()
                isPlaybackCompleted = true
                clearResumePosition()
                prepareResultIntent()
            }
        }
    }

    private fun formatSecToTime(seconds: Long): String {
        val s = maxOf(0L, seconds)
        val hrs = s / 3600
        val mins = (s % 3600) / 60
        val secs = s % 60
        return if (hrs > 0) {
            String.format("%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    private fun clearResumePosition() {
        val prefsResume = getSharedPreferences("teleflix_resume_points", Context.MODE_PRIVATE)
        val prefsTitle = getSharedPreferences("TeleflixResume", Context.MODE_PRIVATE)
        val prefsTeleflix = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)

        val editResume = prefsResume.edit()
        val editTitle = prefsTitle.edit()
        val editTeleflix = prefsTeleflix.edit()

        if (mediaId.isNotBlank()) {
            editResume.remove("id_$mediaId").remove(mediaId).remove("dur_id_$mediaId").remove("dur_$mediaId")
            editTeleflix.remove("resume_pos_$mediaId")
        }
        if (streamUrl.isNotBlank()) {
            editResume.remove(streamUrl).remove("dur_$streamUrl")
            editTeleflix.remove("resume_pos_$streamUrl")
        }
        if (videoTitle.isNotBlank()) {
            editTitle.remove("resume_$videoTitle").remove("dur_$videoTitle")
            editTeleflix.remove("resume_pos_$videoTitle")
        }
        editResume.apply()
        editTitle.apply()
        editTeleflix.apply()
        TeleflixLogger.log("PlayerActivity", "Cleared resume point for '$videoTitle'")
    }

    private fun savePlaybackPosition() {
        if (isPlaybackCompleted) {
            clearResumePosition()
            return
        }

        val posMs = (currentPosSec * 1000).toLong()
        val durMs = (durationSec * 1000).toLong()

        // Guard against saving 0 or invalid position before initial resume seek takes effect
        if (pendingResumeSeekSec > 3.0) return
        if (posMs <= 3000L && currentPosSec <= 3.0) return

        val isCompleted = durMs > 20_000L && (posMs >= (durMs * 0.90).toLong() || posMs >= durMs - 15_000L)
        if (isCompleted) {
            isPlaybackCompleted = true
            clearResumePosition()
            return
        }

        val prefsResume = getSharedPreferences("teleflix_resume_points", Context.MODE_PRIVATE)
        val prefsTitle = getSharedPreferences("TeleflixResume", Context.MODE_PRIVATE)
        val prefsTeleflix = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)

        val editResume = prefsResume.edit()
        val editTitle = prefsTitle.edit()
        val editTeleflix = prefsTeleflix.edit()

        if (mediaId.isNotBlank()) {
            editResume.putLong("id_$mediaId", posMs)
            editResume.putLong(mediaId, posMs)
            if (durMs > 0L) {
                editResume.putLong("dur_id_$mediaId", durMs)
                editResume.putLong("dur_$mediaId", durMs)
            }
            editTeleflix.putLong("resume_pos_$mediaId", posMs)
        }
        if (streamUrl.isNotBlank()) {
            editResume.putLong(streamUrl, posMs)
            if (durMs > 0L) {
                editResume.putLong("dur_$streamUrl", durMs)
            }
            editTeleflix.putLong("resume_pos_$streamUrl", posMs)
        }
        if (videoTitle.isNotBlank()) {
            editTitle.putLong("resume_$videoTitle", posMs)
            if (durMs > 0L) {
                editTitle.putLong("dur_$videoTitle", durMs)
            }
            editTeleflix.putLong("resume_pos_$videoTitle", posMs)
        }

        editResume.apply()
        editTitle.apply()
        editTeleflix.apply()

        PlayerHelper.saveLinkToWatchHistory(this, streamUrl, videoTitle, mediaId = mediaId)
        TeleflixLogger.log("PlayerActivity", "Saved resume position $posMs ms (dur=$durMs ms) for '$videoTitle'")
    }

    private fun prepareResultIntent() {
        val posMs = (currentPosSec * 1000).toLong()
        val durMs = (durationSec * 1000).toLong()
        val resIntent = Intent().apply {
            putExtra("position", posMs)
            putExtra("position_ms", posMs)
            putExtra("extra_position", posMs)
            putExtra("duration", durMs)
            putExtra("duration_ms", durMs)
            putExtra("stream_url", streamUrl)
            putExtra("media_id", mediaId)
            putExtra("title", videoTitle)
            if (isPlaybackCompleted || (durMs > 20_000L && (posMs >= (durMs * 0.90).toLong() || posMs >= durMs - 15_000L))) {
                putExtra("completed", true)
                putExtra("playback_completed", true)
            }
        }
        setResult(Activity.RESULT_OK, resIntent)
    }

    override fun onPause() {
        super.onPause()
        savePlaybackPosition()
        prepareResultIntent()
        mpv?.setPropertyBoolean("pause", true)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onStop() {
        super.onStop()
        savePlaybackPosition()
        prepareResultIntent()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePlaybackPosition()
        prepareResultIntent()
        hideHandler.removeCallbacks(hideRunnable)
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        } catch (_: Throwable) {}
        loudnessEnhancer = null
        val player = mpv
        if (player != null) {
            try {
                player.removeObserver(this)
                player.command(arrayOf("stop"))
                player.destroy()
            } catch (_: Throwable) {}
            mpv = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isLocked) {
            showUnlockPrompt()
            return
        }
        savePlaybackPosition()
        prepareResultIntent()
        super.onBackPressed()
    }
}
