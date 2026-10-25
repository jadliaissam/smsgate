package com.jassam.smsgate.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.jassam.smsgate.R

/**
 * The quiet notification WorkManager shows while it runs expedited claim-and-send
 * work as a short foreground service (API < 31 only; newer releases use expedited
 * jobs without a notification). Low importance: no sound, no heads-up.
 */
internal object SendingNotification {
    private const val CHANNEL_ID = "sending"
    private const val NOTIFICATION_ID = 0x5347 // "SG"

    fun foregroundInfo(context: Context): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_sending), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms)
            .setContentTitle(context.getString(R.string.notification_sending_title))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
