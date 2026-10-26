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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TelegramService : Service() {

    companion object {
        private const val TAG = "TelegramService"
        private const val CHANNEL_ID = "TELEFLIX_SERVICE_CHANNEL_V2"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STREAMING_ACTIVE = "com.teleflix.app.ACTION_STREAMING_ACTIVE"
        const val ACTION_STOP_SERVICE = "com.teleflix.app.ACTION_STOP_SERVICE"
        const val ACTION_EXIT_APP = "ACTION_EXIT_APP"

        private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var autoStopJob: Job? = null

        @Volatile
        var isAppInForeground: Boolean = false
            private set

        fun onAppForegrounded(context: Context) {
            isAppInForeground = true
            autoStopJob?.cancel()
            TelegramClient.setNetworkSuspended(false)
            Log.d(TAG, "onAppForegrounded: TDLib network active, auto-stop cancelled")
        }

        fun onAppBackgrounded(context: Context) {
            isAppInForeground = false
            Log.d(TAG, "onAppBackgrounded: scheduling auto-stop check")
            scheduleAutoStopCheck(context, delayMs = 30_000L)
        }

        fun startForStreaming(context: Context) {
            autoStopJob?.cancel()
            TelegramClient.setNetworkSuspended(false)
            val prefs = context.getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("pref_run_in_background", true)) {
                return
            }
            try {
                val intent = Intent(context, TelegramService::class.java).apply {
                    action = ACTION_STREAMING_ACTIVE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Requested TelegramService startForStreaming")
            } catch (e: Exception) {
                Log.e(TAG, "Failed startForStreaming: ${e.message}")
            }
        }

        fun checkAutoStop(context: Context) {
            scheduleAutoStopCheck(context, delayMs = 30_000L)
        }

        private fun scheduleAutoStopCheck(context: Context, delayMs: Long) {
            autoStopJob?.cancel()
            val appContext = context.applicationContext
            autoStopJob = serviceScope.launch {
                delay(delayMs)
                if (isAppInForeground) {
                    Log.d(TAG, "Auto-stop check: App is in foreground, keeping alive")
                    return@launch
                }
                if (TelegramStreamingProxy.isStreamingRecentlyActive()) {
                    Log.d(TAG, "Auto-stop check: Streaming is still active or recently active, keeping alive")
                    return@launch
                }
                if (DownloadManager.hasActiveDownloads()) {
                    Log.d(TAG, "Auto-stop check: Offline downloads are active, keeping alive")
                    return@launch
                }
                val prefs = appContext.getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
                if (prefs.getBoolean("pref_always_run_background", false)) {
                    Log.d(TAG, "Auto-stop check: 'Always Run 24/7' enabled by user, keeping alive")
                    return@launch
                }

                Log.d(TAG, "Auto-stop check: Idle in background. Stopping service & suspending TDLib network for battery saving")
                stopServiceOnly(appContext)
                TelegramClient.setNetworkSuspended(true)
            }
        }

        private fun stopServiceOnly(context: Context) {
            try {
                val intent = Intent(context, TelegramService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun start(context: Context) {
            try {
                autoStopJob?.cancel()
                TelegramClient.setNetworkSuspended(false)
                val prefs = context.getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("pref_run_in_background", true)) {
                    Log.d(TAG, "TelegramService start ignored: disabled by user in settings")
                    return
                }
                val intent = Intent(context, TelegramService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Requested TelegramService start")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TelegramService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                autoStopJob?.cancel()
                val prefs = context.getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("pref_run_in_background", false).apply()

                stopServiceOnly(context)
                TelegramClient.setNetworkSuspended(true)

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancel(NOTIFICATION_ID)

                Log.d(TAG, "Requested TelegramService stop and notification canceled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop TelegramService: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("TDLib streaming engine active in background"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed startForeground in onCreate: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("pref_run_in_background", true)

        if (intent?.action == ACTION_EXIT_APP || intent?.action == ACTION_STOP_SERVICE || !isEnabled) {
            Log.d(TAG, "Stopping service session: enabled=$isEnabled, action=${intent?.action}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}
            if (intent?.action == ACTION_EXIT_APP) {
                try { TelegramClient.clearMediaCacheSync(this) } catch (_: Exception) {}
                try { TelegramStreamingProxy.stop() } catch (_: Exception) {}
                TelegramClient.setNetworkSuspended(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notifText = if (intent?.action == ACTION_STREAMING_ACTIVE) {
            "Streaming media to external player in background"
        } else {
            "TDLib streaming engine active in background"
        }
        startForeground(NOTIFICATION_ID, buildNotification(notifText))

        try {
            TelegramRepository.initialize(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing repository in service: ${e.message}")
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed by user. Purging temporary media cache...")
        try { TelegramClient.clearMediaCache(this) } catch (_: Exception) {}
        try { TelegramStreamingProxy.stop() } catch (_: Exception) {}
        TelegramClient.setNetworkSuspended(true)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try { TelegramClient.clearMediaCache(this) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        Log.d(TAG, "TelegramService stopped and notification removed")
    }

    private fun buildNotification(statusText: String): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val settingsIntent = Intent(this, SettingsActivity::class.java)
        val settingsPendingIntent = PendingIntent.getActivity(
            this, 1, settingsIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val exitIntent = Intent(this, TelegramService::class.java).apply {
            action = ACTION_EXIT_APP
        }
        val exitPendingIntent = PendingIntent.getService(
            this, 2, exitIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Teleflix Streaming Engine")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_preferences, "Settings", settingsPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close App", exitPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Teleflix Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Telegram streaming engine and local HTTP proxy active while streaming"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
