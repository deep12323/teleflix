package com.teleflix.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var speedButton: Button
    private lateinit var resizeButton: Button
    private lateinit var vlcButton: Button

    private var videoUrl: String = ""
    private var videoTitle: String = ""
    private var currentSpeedIndex = 1
    private val speeds = arrayOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val speedLabels = arrayOf("0.5x", "1.0x", "1.25x", "1.5x", "2.0x")

    private var currentResizeModeIndex = 0
    private val resizeModes = arrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
    )
    private val resizeLabels = arrayOf("Fit", "Fill", "Zoom", "Stretch")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        videoUrl = intent.getStringExtra("VIDEO_URL") ?: ""
        videoTitle = intent.getStringExtra("VIDEO_TITLE") ?: "Video Stream"

        val rootView = android.widget.RelativeLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 3000
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootView.addView(playerView)

        // Custom Overlay Bar with Full Controls
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#99000000"))
            setPadding(32, 24, 32, 24)
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(this).apply {
            text = videoTitle
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        speedButton = Button(this).apply {
            text = "Speed: 1.0x"
            textSize = 11f
            setBackgroundColor(android.graphics.Color.parseColor("#374151"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { cyclePlaybackSpeed() }
        }

        resizeButton = Button(this).apply {
            text = "Aspect: Fit"
            textSize = 11f
            setBackgroundColor(android.graphics.Color.parseColor("#374151"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { cycleResizeMode() }
        }

        vlcButton = Button(this).apply {
            text = "External 📱"
            textSize = 11f
            setBackgroundColor(android.graphics.Color.parseColor("#EA580C"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { showExternalPlayerPicker() }
        }

        topBar.addView(titleView)
        topBar.addView(speedButton)
        topBar.addView(resizeButton)
        topBar.addView(vlcButton)
        rootView.addView(topBar)

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topBar.visibility = visibility
        })

        setContentView(rootView)

        hideSystemUI()
        initPlayer()
    }

    private fun initPlayer() {
        if (videoUrl.isEmpty()) {
            Toast.makeText(this, "Error: Empty stream link", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val resumeMs = intent.getLongExtra("RESUME_MS", 0L)

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
            exo.setMediaItem(mediaItem)
            if (resumeMs > 0L) {
                exo.seekTo(resumeMs)
            }
            exo.prepare()
            exo.playWhenReady = true

            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("PlayerActivity", "ExoPlayer error: ${error.message}", error)
                    if (isDestroyed || isFinishing) return
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("Unsupported Video Codec")
                        .setMessage("Your hardware does not natively support decoding this Telegram video stream in ExoPlayer.\n\nWould you like to choose an external player on your phone?")
                        .setPositiveButton("Choose Player...") { _, _ ->
                            showExternalPlayerPicker()
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
            })
        }
    }

    private fun showExternalPlayerPicker() {
        val options = arrayOf(
            "🟢 Open in MPVEX Player",
            "🟣 Open in MPV Player",
            "🧡 Open in VLC Player",
            "📱 Choose From All Installed Video Players..."
        )
        AlertDialog.Builder(this)
            .setTitle("Switch to External Player")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openExternalPlayer("id.nzxm.mpvex")
                    1 -> openExternalPlayer("is.xyz.mpv")
                    2 -> openExternalPlayer("org.videolan.vlc")
                    3 -> openExternalPlayer("")
                }
            }
            .show()
    }

    private fun cyclePlaybackSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speeds.size
        player?.playbackParameters = PlaybackParameters(speeds[currentSpeedIndex])
        speedButton.text = "${speedLabels[currentSpeedIndex]}x"
    }

    private fun cycleResizeMode() {
        currentResizeModeIndex = (currentResizeModeIndex + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[currentResizeModeIndex]
        resizeButton.text = "Aspect: ${resizeLabels[currentResizeModeIndex]}"
    }

    private fun openExternalPlayer(packageName: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(videoUrl), "video/*")
            putExtra("title", videoTitle)
            putExtra("filename", videoTitle)
            if (packageName.isNotEmpty()) setPackage(packageName)
        }
        try {
            val finalIntent = if (packageName.isEmpty()) Intent.createChooser(intent, "Select Video Player") else intent
            startActivity(finalIntent)
        } catch (e: Exception) {
            if (packageName == "id.nzxm.mpvex") {
                try {
                    intent.setPackage("id.nzxm.mpvex.debug")
                    startActivity(intent)
                    return
                } catch (_: Exception) {}
            }
            Toast.makeText(this, "Selected video player not installed or found on phone", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun savePlaybackProgress() {
        val exo = player ?: return
        val pos = exo.currentPosition
        val duration = exo.duration
        val prefs = getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE).edit()
        if (duration > 0 && pos > 10_000L && pos < duration * 0.95) {
            prefs.putLong("resume_$videoTitle", pos).apply()
        } else if (duration > 0 && pos >= duration * 0.95) {
            prefs.remove("resume_$videoTitle").apply()
        } else if (duration <= 0 && pos > 10_000L) {
            prefs.putLong("resume_$videoTitle", pos).apply()
        }
    }

    override fun onPause() {
        super.onPause()
        savePlaybackProgress()
    }

    override fun onStop() {
        super.onStop()
        savePlaybackProgress()
    }

    override fun onDestroy() {
        savePlaybackProgress()
        super.onDestroy()
        player?.release()
        player = null
    }
}
