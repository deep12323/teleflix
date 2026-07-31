package com.teleflix.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TeleflixDownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "TELEFLIX_DOWNLOAD_CHANNEL_V1"
        private const val NOTIFICATION_ID = 2002

        const val ACTION_PAUSE_ALL = "com.teleflix.app.ACTION_PAUSE_ALL"
        const val ACTION_CANCEL_ALL = "com.teleflix.app.ACTION_CANCEL_ALL"

        fun start(context: Context) {
            try {
                val intent = Intent(context, TeleflixDownloadService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TeleflixDownloadService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TeleflixDownloadService::class.java))
            } catch (_: Exception) {}
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing download...", 0, 0, ""))
        startObserving()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                val active = DownloadManager.downloadsFlow.value.filter { it.status == DownloadStatus.DOWNLOADING }
                for (item in active) {
                    DownloadManager.pauseDownload(this, item.id)
                }
                stopSelfIfNoActive()
            }
            ACTION_CANCEL_ALL -> {
                val active = DownloadManager.downloadsFlow.value.filter { it.status == DownloadStatus.DOWNLOADING }
                for (item in active) {
                    DownloadManager.cancelDownload(this, item.id)
                }
                stopSelfIfNoActive()
            }
        }

        if (!DownloadManager.hasActiveDownloads()) {
            stopSelfIfNoActive()
        }

        return START_STICKY
    }

    private fun startObserving() {
        observeJob?.cancel()
        observeJob = serviceScope.launch {
            DownloadManager.downloadsFlow.collectLatest { downloads ->
                val activeList = downloads.filter { it.status == DownloadStatus.DOWNLOADING }
                if (activeList.isEmpty()) {
                    stopSelfIfNoActive()
                } else {
                    val activeItem = activeList.first()
                    val totalDownloaded = activeList.sumOf { it.downloadedBytes }
                    val totalSize = activeList.sumOf { it.totalBytes }
                    val overallProgress = if (totalSize > 0) ((totalDownloaded * 100) / totalSize).toInt() else 0

                    val titleText = if (activeList.size == 1) {
                        "Downloading ${activeItem.title}"
                    } else {
                        "Downloading ${activeList.size} files (${activeItem.title})"
                    }

                    val subText = "${activeItem.getFormattedSpeed()} • ${activeItem.progressPercent}%"

                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(titleText, overallProgress, activeList.size, subText))
                }
            }
        }
    }

    private fun stopSelfIfNoActive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        observeJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Teleflix File Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress for media files being downloaded via Teleflix"
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, progress: Int, activeCount: Int, speedText: String): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("OPEN_DOWNLOADS_TAB", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseIntent = Intent(this, TeleflixDownloadService::class.java).apply {
            action = ACTION_PAUSE_ALL
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 1, pauseIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = Intent(this, TeleflixDownloadService::class.java).apply {
            action = ACTION_CANCEL_ALL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 2, cancelIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(speedText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()
    }
}
