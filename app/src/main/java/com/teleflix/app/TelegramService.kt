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
        private const val CHANNEL_ID = "TELEFLIX_TDLIB_CHANNEL"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
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
                val intent = Intent(context, TelegramService::class.java)
                context.stopService(intent)
                Log.d(TAG, "Requested TelegramService stop")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop TelegramService: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("TDLib streaming & search engine active in background"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        Log.d(TAG, "TelegramService stopped")
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Teleflix Streaming Engine")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_preferences, "Settings", settingsPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Teleflix TDLib Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Telegram streaming engine and local HTTP proxy active in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
