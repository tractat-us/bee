package us.tractat.bee.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * SPIKE, throwaway. Does nothing but hold the process at foreground priority, so the cached-app
 * freezer leaves the host's sockets alone while the board is off screen. The app never asks for
 * POST_NOTIFICATIONS, so its notification stays out of the shade (Android 13+): the question is
 * whether the service still keeps the host up.
 */
class KeepHosting : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Board hosting", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("bee board is open")
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_STICKY
    }

    private companion object {
        const val CHANNEL = "hosting"
    }
}
