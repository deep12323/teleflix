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
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
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
            text = "VLC"
            textSize = 11f
            setBackgroundColor(android.graphics.Color.parseColor("#EA580C"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { openExternalPlayer("org.videolan.vlc") }
        }

        topBar.addView(titleView)
        topBar.addView(speedButton)
        topBar.addView(resizeButton)
        topBar.addView(vlcButton)
        rootView.addView(topBar)

        setContentView(rootView)

        hideSystemUI()
        initializePlayer()
    }

    private fun initializePlayer() {
        if (videoUrl.isEmpty()) return
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            playerView.player = exoPlayer
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("PlayerActivity", "ExoPlayer error: ${error.message}", error)
                    Toast.makeText(this@PlayerActivity, "Codec or format issue in Internal Player. Try opening in VLC!", Toast.LENGTH_LONG).show()
                    androidx.appcompat.app.AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("Stream Format Not Supported by ExoPlayer")
                        .setMessage("This Telegram file contains audio/video codecs (such as AC3, Dolby, or certain MKV profiles) that require VLC Player to decode.\n\nWould you like to switch to VLC Player now?")
                        .setPositiveButton("Open in VLC") { _, _ -> openExternalPlayer("org.videolan.vlc") }
                        .setNeutralButton("Open in MPV") { _, _ -> openExternalPlayer("is.xyz.mpv") }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    private fun cyclePlaybackSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speeds.size
        val newSpeed = speeds[currentSpeedIndex]
        player?.playbackParameters = PlaybackParameters(newSpeed)
        speedButton.text = "Speed: ${speedLabels[currentSpeedIndex]}"
    }

    private fun cycleResizeMode() {
        currentResizeModeIndex = (currentResizeModeIndex + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[currentResizeModeIndex]
        resizeButton.text = "Aspect: ${resizeLabels[currentResizeModeIndex]}"
    }

    private fun openExternalPlayer(packageName: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(videoUrl), "video/*")
            if (packageName.isNotEmpty()) setPackage(packageName)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "External player not installed", Toast.LENGTH_SHORT).show()
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

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
