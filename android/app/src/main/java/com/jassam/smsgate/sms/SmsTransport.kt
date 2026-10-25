package com.jassam.smsgate.sms

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.telephony.SmsManager

/** Thin seam over SmsManager so the sending logic is testable without telephony. */
fun interface SmsTransport {
    /** The channel for [subscriptionId] (null = the default SMS SIM), or null without an SMS service. */
    fun channel(subscriptionId: Int?): SmsChannel?
}

interface SmsChannel {
    fun divideMessage(body: String): List<String>

    /** One sent and one delivery PendingIntent per part. */
    fun sendMultipartTextMessage(
        destination: String,
        parts: List<String>,
        sentIntents: List<PendingIntent>,
        deliveryIntents: List<PendingIntent>,
    )
}

/**
 * The real SmsManager. API 31+: the system service (default SMS subscription) or
 * `createForSubscriptionId`; API 24–30: `getDefault` / `getSmsManagerForSubscriptionId`.
 */
class PlatformSmsTransport(context: Context) : SmsTransport {
    private val app = context.applicationContext

    override fun channel(subscriptionId: Int?): SmsChannel? = manager(subscriptionId)?.let(::PlatformChannel)

    internal fun manager(subscriptionId: Int?): SmsManager? =
        if (Build.VERSION.SDK_INT >= 31) {
            val default = app.getSystemService(SmsManager::class.java)
            if (subscriptionId == null) default else default?.createForSubscriptionId(subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            if (subscriptionId == null) SmsManager.getDefault() else SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }

    private class PlatformChannel(private val manager: SmsManager) : SmsChannel {
        override fun divideMessage(body: String): List<String> = manager.divideMessage(body).orEmpty()

        override fun sendMultipartTextMessage(
            destination: String,
            parts: List<String>,
            sentIntents: List<PendingIntent>,
            deliveryIntents: List<PendingIntent>,
        ) = manager.sendMultipartTextMessage(
            destination,
            null,
            ArrayList(parts),
            ArrayList(sentIntents),
            ArrayList(deliveryIntents),
        )
    }
}
