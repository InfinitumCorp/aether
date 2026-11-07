package com.infinitum.aether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the ÆTHER node listening while the app is in the background. Without it Android
 * freezes the process as soon as another app is in front, and teammates can no longer reach
 * this device (TCP accepts, but the handshake is never answered).
 */
class NodeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as AetherApp
        if (app.node == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        return START_STICKY
    }

    companion object {
        private const val ID = 7337
        private const val CHANNEL = "aether.node"

        fun start(context: Context) {
            val i = Intent(context, NodeService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            }
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "ÆTHER Mesh node", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while this device can be reached by teammates."
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
            return b.setSmallIcon(R.drawable.ic_aether)
                .setContentTitle("ÆTHER MIL is reachable")
                .setContentText("Listening for teammates on the mesh.")
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(open)
                .build()
        }
    }
}
