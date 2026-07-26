package com.example.frontend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class HostForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "neurovault_host_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_HOST"
        const val ACTION_STOP = "ACTION_STOP_HOST"
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            isRunning = false
            return START_NOT_STICKY
        }

        val reservedGb = intent?.getIntExtra("reservedGb", 10) ?: 10
        val containerPath = intent?.getStringExtra("containerPath") ?: "Default"

        val notification = buildNotification(reservedGb, containerPath)
        startForeground(NOTIFICATION_ID, notification)
        isRunning = true

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NeuroVault Micro-Server Host",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps NeuroVault Host Node online 24/7 in the background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(reservedGb: Int, containerPath: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NeuroVault Host Active (24/7)")
            .setContentText("Contributing $reservedGb GB storage node | Container locked")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
