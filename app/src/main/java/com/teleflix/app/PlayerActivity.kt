package com.teleflix.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import io.github.abdallahmehiz.mpv.MPVLib
import io.github.abdallahmehiz.mpv.MPVView

class PlayerActivity : AppCompatActivity() {

    private lateinit var mpvView: MPVView
    private lateinit var controlsOverlay: FrameLayout
    private lateinit var playPauseButton: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var seekBar: SeekBar

    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var areControlsVisible = true
    private var streamUrl: String = ""
    private var videoTitle: String = ""
    private var resumeMs: Long = 0L

    private val updateRunnable = object : Runnable {
        override fun run() {
            try {
                val currentSec = (MPVLib.getPropertyDouble("time-pos") ?: 0.0).toDouble()
                val totalSec = (MPVLib.getPropertyDouble("duration") ?: 0.0).toDouble()
                val isPaused = (MPVLib.getPropertyBoolean("pause") ?: false)
                playPauseButton.text = if (isPaused) "▶️" else "⏸️"

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

        // Initialize MPV native engine
        try {
            MPVLib.create(applicationContext)
            MPVLib.init()
            MPVLib.setPropertyString("vo", "gpu")
            MPVLib.setPropertyString("hwdec", "auto")
        } catch (e: Exception) {
            Log.e("PlayerActivity", "MPVLib initialization failed: ${e.message}")
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
        mpvView = MPVView(this).apply {
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

        // Start playback
        try {
            if (resumeMs > 0) {
                MPVLib.setPropertyDouble("start", resumeMs / 1000.0)
            }
            MPVLib.command(arrayOf("loadfile", streamUrl))
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
            setBackgroundColor(Color.parseColor("#B3000000"))
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
            text = "💬 Sub"
            textSize = 14f
            setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#334155"))
                cornerRadius = dpToPx(6).toFloat()
            }
            background = bg
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            setOnClickListener {
                try {
                    MPVLib.command(arrayOf("cycle", "sub"))
                    Toast.makeText(context, "Cycled Subtitle Track", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
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
                leftMargin = dpToPx(12)
            }
            setOnClickListener {
                try {
                    MPVLib.command(arrayOf("cycle", "audio"))
                    Toast.makeText(context, "Cycled Audio Track", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        }

        topBar.addView(backButton)
        topBar.addView(titleText)
        topBar.addView(subButton)
        topBar.addView(audioButton)

        // CENTER PLAY/PAUSE BUTTON
        playPauseButton = TextView(this).apply {
            text = "⏸️"
            textSize = 48f
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#80000000"))
            }
            background = bg
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            layoutParams = FrameLayout.LayoutParams(dpToPx(80), dpToPx(80), Gravity.CENTER)
            setOnClickListener {
                try {
                    val isPaused = (MPVLib.getPropertyBoolean("pause") ?: false)
                    MPVLib.setPropertyBoolean("pause", !isPaused)
                    text = if (!isPaused) "▶️" else "⏸️"
                    scheduleHideControls()
                } catch (_: Exception) {}
            }
        }

        // BOTTOM BAR
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#B3000000"))
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
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
                        val totalSec = (MPVLib.getPropertyDouble("duration") ?: 0.0).toDouble()
                        if (totalSec > 0.0 && seekBar != null) {
                            val targetSec = (seekBar.progress / 1000.0) * totalSec
                            MPVLib.setPropertyDouble("time-pos", targetSec)
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

        bottomBar.addView(currentTimeText)
        bottomBar.addView(seekBar)
        bottomBar.addView(totalTimeText)

        overlay.addView(topBar)
        overlay.addView(playPauseButton)
        overlay.addView(bottomBar)
        return overlay
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
            MPVLib.command(arrayOf("stop"))
            MPVLib.destroy()
        } catch (_: Exception) {}
    }
}
