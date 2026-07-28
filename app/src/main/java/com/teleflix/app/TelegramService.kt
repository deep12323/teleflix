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

class TelegramService : Service() {

    companion object {
        private const val TAG = "TelegramService"
        private const val CHANNEL_ID = "TELEFLIX_SERVICE_CHANNEL_V2"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
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
                val prefs = context.getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("pref_run_in_background", false).apply()

                try {
                    context.stopService(Intent(context, TelegramService::class.java))
                } catch (_: Exception) {}

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
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
            startForeground(NOTIFICATION_ID, buildNotification("TDLib streaming & search engine active in background"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed startForeground in onCreate: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("pref_run_in_background", true)

        if (intent?.action == "ACTION_EXIT_APP" || intent?.action == "ACTION_STOP_SERVICE" || !isEnabled) {
            Log.d(TAG, "Stopping service session: enabled=$isEnabled, action=${intent?.action}")
            // Do NOT alter pref_run_in_background setting when user closes app via notification button!
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                manager?.cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("TDLib streaming & search engine active in background"))
        // Ensure TDLib and local streaming proxy remain awake and ready
        try {
            TelegramRepository.initialize(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing repository in service: ${e.message}")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
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
            action = "ACTION_EXIT_APP"
        }
        val exitPendingIntent = PendingIntent.getService(
            this, 2, exitIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Teleflix Streaming Engine")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_preferences, "Settings", settingsPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close App", exitPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Teleflix Background Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Keeps Telegram streaming engine and local HTTP proxy active in background"
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
