package com.personal.unifiedrecorder.adapter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

/**
 * Foreground service backing the active-recording indicator that must stay visible for the entire
 * capture and be removed shortly after it stops (Requirement 2.5). Declared with the microphone
 * foreground-service type in the manifest.
 *
 * Start it with [ACTION_START] while a capture is in progress and [ACTION_STOP] when it ends. The
 * connection to the capture lifecycle is performed by a later wiring task.
 *
 * Device-only / manual verification: notification visibility and the microphone FGS type can only
 * be confirmed on a device.
 */
class RecordingIndicatorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
            }
            else -> startIndicator()
        }
        return START_NOT_STICKY
    }

    private fun startIndicator() {
        ensureChannel(this)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording call")
            .setContentText("A call recording is in progress.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.personal.unifiedrecorder.action.START_INDICATOR"
        const val ACTION_STOP = "com.personal.unifiedrecorder.action.STOP_INDICATOR"

        private const val CHANNEL_ID = "recording_indicator"
        private const val NOTIFICATION_ID = 1001

        @RequiresApi(Build.VERSION_CODES.O)
        private fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while a call recording is in progress." }
            manager.createNotificationChannel(channel)
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createChannel(context)
        }
    }
}
