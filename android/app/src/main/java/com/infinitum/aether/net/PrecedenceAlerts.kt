package com.infinitum.aether.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.infinitum.aether.MainActivity
import com.infinitum.aether.R
import com.infinitum.aether.data.StoredMessage
import uniffi.aether_core.Precedence

/**
 * Immediate and Flash traffic: a high-priority notification so it is seen at once. The lock
 * screen shows only "FLASH traffic", never the sender or the text.
 */
object PrecedenceAlerts {
    private const val CHANNEL_FLASH = "aether.flash"
    private const val CHANNEL_IMMEDIATE = "aether.immediate"

    /** Conversation on screen right now: its traffic needs no notification. */
    @Volatile var openConvo: String? = null

    fun post(context: Context, convo: String, chat: String, sender: String, m: StoredMessage) {
        if (openConvo == convo) return
        val flash = m.precedence == Precedence.FLASH
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_FLASH, "Flash traffic", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Flash precedence messages."
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 400)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_IMMEDIATE, "Immediate traffic", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Immediate precedence messages."
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }
        val channel = if (flash) CHANNEL_FLASH else CHANNEL_IMMEDIATE
        val label = if (flash) "FLASH" else "IMMEDIATE"
        val open = PendingIntent.getActivity(
            context, convo.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_CONVO, convo),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun builder() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channel)
        else @Suppress("DEPRECATION") Notification.Builder(context).setPriority(Notification.PRIORITY_MAX)
        val public = builder().setSmallIcon(R.drawable.ic_aether).setContentTitle("$label traffic").setContentText("Unlock to read.").build()
        val text = if (m.fileId != null) "Attachment" + if (m.body.isNotBlank()) " · ${m.body}" else "" else m.body
        val n = builder().setSmallIcon(R.drawable.ic_aether)
            .setContentTitle("$label · $chat")
            .setContentText(if (chat == sender) text else "$sender: $text")
            .setStyle(Notification.BigTextStyle().bigText(if (chat == sender) text else "$sender: $text"))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(convo.hashCode(), n) } // no POST_NOTIFICATIONS grant: the chat list still sorts it first
    }

    const val EXTRA_CONVO = "aether.convo"
}
