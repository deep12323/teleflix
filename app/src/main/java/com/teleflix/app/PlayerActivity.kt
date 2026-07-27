package com.teleflix.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import `is`.xyz.mpv.BaseMPVView

class TeleflixMpvView(context: Context, attrs: AttributeSet? = null) : BaseMPVView(context, attrs) {
    override fun initOptions() {
        try {
            mpv.setPropertyString("vo", "gpu")
            mpv.setPropertyString("hwdec", "auto")
        } catch (_: Exception) {}
    }
    override fun postInitOptions() {}
    override fun observeProperties() {}
}

class PlayerActivity : AppCompatActivity() {

    private lateinit var mpvView: TeleflixMpvView
    private lateinit var controlsOverlay: FrameLayout
    private lateinit var playPauseButton: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var aspectButton: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var areControlsVisible = true
    private var streamUrl: String = ""
    private var videoTitle: String = ""
    private var resumeMs: Long = 0L

    private val updateRunnable = object : Runnable {
        override fun run() {
            try {
                val currentSec = (mpvView.mpv.getPropertyDouble("time-pos") ?: 0.0).toDouble()
                val totalSec = (mpvView.mpv.getPropertyDouble("duration") ?: 0.0).toDouble()
                val isPaused = (mpvView.mpv.getPropertyBoolean("pause") ?: false)
                playPauseButton.text = if (isPaused) "►  PLAY" else "❚❚  PAUSE"

                if (totalSec > 0.0) {
                    seekBar.max = 1000
                    if (!isUserSeeking) {
                        val progress = ((currentSec / totalSec) * 1000).toInt()
                        seekBar.progress = progress
                    }
                    currentTimeText.text = formatTime(currentSec.toLong())
                    totalTimeText.text = formatTime(totalSec.toLong())

                    // Save resume point automatically if beyond first 5 seconds
                    if (currentSec > 5.0 && (totalSec - currentSec) > 5.0) {
                        saveResumePosition((currentSec * 1000.0).toLong())
                    } else if (totalSec > 0.0 && (totalSec - currentSec) <= 5.0) {
                        clearResumePosition()
                    }
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fullscreen & landscape setup
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        streamUrl = intent.getStringExtra("url") ?: ""
        videoTitle = intent.getStringExtra("title") ?: "Video Stream"
        resumeMs = intent.getLongExtra("resumeMs", 0L)
        if (streamUrl.isBlank()) {
            Toast.makeText(this, "Error: Empty video URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Root container
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // MPV Video Surface View
        mpvView = TeleflixMpvView(this, null).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { toggleControls() }
        }
        rootLayout.addView(mpvView)

        // Build Custom Touch Controls Overlay
        controlsOverlay = buildControlsOverlay()
        rootLayout.addView(controlsOverlay)
        setContentView(rootLayout)

        // Initialize engine and start playback
        try {
            mpvView.initialize(filesDir.path, cacheDir.path)
            
            // Critical Android Subtitle Rendering & Font Config
            try {
                mpvView.mpv.setPropertyString("sub-visibility", "yes")
                mpvView.mpv.setPropertyString("sub-auto", "fuzzy")
                mpvView.mpv.setPropertyString("sub-font", "sans-serif")
                mpvView.mpv.setPropertyString("sub-font-size", "52")
                mpvView.mpv.setPropertyString("sub-color", "#FFFFFFFF")
                mpvView.mpv.setPropertyString("sub-border-color", "#FF000000")
                mpvView.mpv.setPropertyString("sub-border-size", "3")
                mpvView.mpv.setPropertyString("sub-shadow-color", "#80000000")
                mpvView.mpv.setPropertyString("sub-shadow-offset", "2")
                mpvView.mpv.setPropertyString("sub-use-margins", "yes")
                mpvView.mpv.setPropertyString("slang", "en,eng,english")
                mpvView.mpv.setPropertyString("sid", "auto")
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error setting subtitle defaults: ${e.message}")
            }

            if (resumeMs > 0) {
                mpvView.mpv.setPropertyDouble("start", resumeMs / 1000.0)
            }
            mpvView.playFile(streamUrl)
            handler.post(updateRunnable)
            scheduleHideControls()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch video in MPV: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildControlsOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { toggleControls() }
        }

        fun dpToPx(dp: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                resources.displayMetrics
            ).toInt()
        }

        // TOP BAR
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#D9000000"))
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val backButton = TextView(this).apply {
            text = "⬅"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(16), dpToPx(4))
            setOnClickListener { finish() }
        }

        val titleText = TextView(this).apply {
            text = videoTitle
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val subButton = TextView(this).apply {
            text = "💬 Subtitles"
            textSize = 14f
            setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#334155"))
                cornerRadius = dpToPx(6).toFloat()
            }
            background = bg
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            setOnClickListener { showSubtitlesDialog() }
        }

        val audioButton = TextView(this).apply {
            text = "🎵 Audio"
            textSize = 14f
            setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#334155"))
                cornerRadius = dpToPx(6).toFloat()
            }
            background = bg
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dpToPx(8)
            }
            setOnClickListener { showAudioDialog() }
        }

        aspectButton = TextView(this).apply {
            text = "🖼️ Fit"
            textSize = 14f
            setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = dpToPx(6).toFloat()
            }
            background = bg
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dpToPx(8)
            }
            setOnClickListener { showAspectRatioDialog(this) }
        }

        topBar.addView(backButton)
        topBar.addView(titleText)
        topBar.addView(subButton)
        topBar.addView(audioButton)
        topBar.addView(aspectButton)

        // BOTTOM BAR WITH CLEAN PLAY/PAUSE BUTTON
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#D9000000"))
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        playPauseButton = TextView(this).apply {
            text = "❚❚  PAUSE"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#334155"))
                cornerRadius = dpToPx(6).toFloat()
            }
            background = bg
            setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dpToPx(12)
            }
            setOnClickListener {
                try {
                    val isPaused = (mpvView.mpv.getPropertyBoolean("pause") ?: false)
                    mpvView.mpv.setPropertyBoolean("pause", !isPaused)
                    text = if (!isPaused) "►  PLAY" else "❚❚  PAUSE"
                    scheduleHideControls()
                } catch (_: Exception) {}
            }
        }

        currentTimeText = TextView(this).apply {
            text = "00:00"
            textSize = 14f
            setTextColor(Color.WHITE)
        }

        seekBar = SeekBar(this).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dpToPx(12), 0, dpToPx(12), 0)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserSeeking = true
                    handler.removeCallbacksAndMessages(null)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    try {
                        val totalSec = (mpvView.mpv.getPropertyDouble("duration") ?: 0.0).toDouble()
                        if (totalSec > 0.0 && seekBar != null) {
                            val targetSec = (seekBar.progress / 1000.0) * totalSec
                            mpvView.mpv.setPropertyDouble("time-pos", targetSec)
                        }
                    } catch (_: Exception) {}
                    isUserSeeking = false
                    handler.post(updateRunnable)
                    scheduleHideControls()
                }
            })
        }

        totalTimeText = TextView(this).apply {
            text = "00:00"
            textSize = 14f
            setTextColor(Color.WHITE)
        }

        bottomBar.addView(playPauseButton)
        bottomBar.addView(currentTimeText)
        bottomBar.addView(seekBar)
        bottomBar.addView(totalTimeText)

        overlay.addView(topBar)
        overlay.addView(bottomBar)
        return overlay
    }

    private fun showSubtitlesDialog() {
        try {
            val count = (mpvView.mpv.getPropertyInt("track-list/count") ?: 0).toInt()
            val subTracks = mutableListOf<Pair<Int, String>>()
            for (i in 0 until count) {
                val type = mpvView.mpv.getPropertyString("track-list/$i/type")
                if (type == "sub") {
                    val id = (mpvView.mpv.getPropertyInt("track-list/$i/id") ?: 0).toInt()
                    val lang = mpvView.mpv.getPropertyString("track-list/$i/lang") ?: ""
                    val title = mpvView.mpv.getPropertyString("track-list/$i/title") ?: ""
                    val label = buildString {
                        append("Subtitle #$id")
                        if (lang.isNotBlank()) append(" [$lang]")
                        if (title.isNotBlank() && title != lang) append(" - $title")
                    }
                    subTracks.add(Pair(id, label))
                }
            }

            val labels = mutableListOf<String>()
            labels.add("❌ Disable Subtitles (Off)")
            labels.addAll(subTracks.map { it.second })

            AlertDialog.Builder(this)
                .setTitle("Select Subtitle Track")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which == 0) {
                        mpvView.mpv.setPropertyString("sub-visibility", "no")
                        mpvView.mpv.setPropertyString("sid", "no")
                        Toast.makeText(this, "Subtitles Disabled", Toast.LENGTH_SHORT).show()
                    } else {
                        val selectedId = subTracks[which - 1].first
                        mpvView.mpv.setPropertyString("sub-visibility", "yes")
                        try { mpvView.mpv.setPropertyInt("sid", selectedId) } catch (_: Exception) {
                            mpvView.mpv.setPropertyString("sid", selectedId.toString())
                        }
                        Toast.makeText(this, "Selected: ${subTracks[which - 1].second}", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load subtitles: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAudioDialog() {
        try {
            val count = (mpvView.mpv.getPropertyInt("track-list/count") ?: 0).toInt()
            val audioTracks = mutableListOf<Pair<Int, String>>()
            for (i in 0 until count) {
                val type = mpvView.mpv.getPropertyString("track-list/$i/type")
                if (type == "audio") {
                    val id = (mpvView.mpv.getPropertyInt("track-list/$i/id") ?: 0).toInt()
                    val lang = mpvView.mpv.getPropertyString("track-list/$i/lang") ?: ""
                    val title = mpvView.mpv.getPropertyString("track-list/$i/title") ?: ""
                    val label = buildString {
                        append("Audio #$id")
                        if (lang.isNotBlank()) append(" [$lang]")
                        if (title.isNotBlank() && title != lang) append(" - $title")
                    }
                    audioTracks.add(Pair(id, label))
                }
            }

            if (audioTracks.isEmpty()) {
                Toast.makeText(this, "No audio tracks detected", Toast.LENGTH_SHORT).show()
                return
            }

            val labels = audioTracks.map { it.second }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Select Audio Track")
                .setItems(labels) { _, which ->
                    val selectedId = audioTracks[which].first
                    mpvView.mpv.setPropertyInt("aid", selectedId)
                    Toast.makeText(this, "Selected: ${audioTracks[which].second}", Toast.LENGTH_SHORT).show()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load audio tracks: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAspectRatioDialog(button: TextView) {
        val options = arrayOf(
            "Fit (Proportional Default)",
            "Zoom (Crop & Fill Screen)",
            "Stretched (Stretch to Screen Dimensions)"
        )
        AlertDialog.Builder(this)
            .setTitle("Select Aspect Ratio & Scaling")
            .setItems(options) { _, which ->
                try {
                    when (which) {
                        0 -> {
                            mpvView.mpv.setPropertyDouble("panscan", 0.0)
                            mpvView.mpv.setPropertyString("video-aspect-override", "-1")
                            button.text = "🖼️ Fit"
                            Toast.makeText(this, "Aspect Ratio: Fit (Proportional)", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            mpvView.mpv.setPropertyDouble("panscan", 1.0)
                            mpvView.mpv.setPropertyString("video-aspect-override", "-1")
                            button.text = "🖼️ Zoom"
                            Toast.makeText(this, "Aspect Ratio: Zoomed (Fill Screen)", Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            mpvView.mpv.setPropertyDouble("panscan", 0.0)
                            val w = mpvView.width.toDouble()
                            val h = mpvView.height.toDouble()
                            val ratio = if (h > 0) w / h else 1.7778
                            mpvView.mpv.setPropertyDouble("video-aspect-override", ratio)
                            button.text = "🖼️ Stretched"
                            Toast.makeText(this, "Aspect Ratio: Stretched to Screen", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to update scaling: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun toggleControls() {
        if (areControlsVisible) {
            controlsOverlay.visibility = View.GONE
            areControlsVisible = false
        } else {
            controlsOverlay.visibility = View.VISIBLE
            areControlsVisible = true
            scheduleHideControls()
        }
        hideSystemUI()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 4000)
    }

    private val hideControlsRunnable = Runnable {
        controlsOverlay.visibility = View.GONE
        areControlsVisible = false
        hideSystemUI()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    private fun formatTime(seconds: Long): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format("%02d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    private fun saveResumePosition(ms: Long) {
        if (streamUrl.isBlank()) return
        val prefs = getSharedPreferences("teleflix_resume_points", Context.MODE_PRIVATE)
        prefs.edit().putLong(streamUrl, ms).apply()
    }

    private fun clearResumePosition() {
        if (streamUrl.isBlank()) return
        val prefs = getSharedPreferences("teleflix_resume_points", Context.MODE_PRIVATE)
        prefs.edit().remove(streamUrl).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            mpvView.destroy()
        } catch (_: Exception) {}
    }
}
