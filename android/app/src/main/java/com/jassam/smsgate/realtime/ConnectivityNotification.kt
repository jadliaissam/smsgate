package com.jassam.smsgate.realtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jassam.smsgate.R
import com.jassam.smsgate.ui.MainActivity

/**
 * The quiet ongoing notification of [ConnectivityService]: "SMSGate is connected",
 * low importance (no sound, no heads-up, no badge), its own channel; a tap opens the
 * app. Without POST_NOTIFICATIONS (API 33+) Android hides it and the service still runs.
 */
internal object ConnectivityNotification {
    const val CHANNEL_ID = "connection"
    const val NOTIFICATION_ID = 0x5348

    fun build(context: Context): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel_connection),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.notification_channel_connection_description)
                        setShowBadge(false)
                    },
                )
            }
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms)
            .setContentTitle(context.getString(R.string.notification_connected_title))
            .setContentText(context.getString(R.string.notification_connected_text))
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }
}
