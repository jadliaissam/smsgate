package com.jassam.smsgate.sms

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/** Thin seam over SmsManager so the sending logic is testable without telephony. */
fun interface SmsTransport {
    /** The channel for [subscriptionId], or null without an SMS service. */
    fun channel(subscriptionId: Int): SmsChannel?
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
 * The real SmsManager, always bound to an explicit subscription: API 31+
 * `createForSubscriptionId` (the system-service instance is only the factory and
 * never sends), API 24–30 `getSmsManagerForSubscriptionId`. Nothing sends through
 * the default-subscription manager, which may pop the system SIM picker when no
 * default SMS SIM is set; SmsSender resolves the subscription first.
 */
class PlatformSmsTransport(context: Context) : SmsTransport {
    private val app = context.applicationContext

    override fun channel(subscriptionId: Int): SmsChannel? = manager(subscriptionId)?.let(::PlatformChannel)

    internal fun manager(subscriptionId: Int): SmsManager? =
        if (Build.VERSION.SDK_INT >= 31) {
            app.getSystemService(SmsManager::class.java)?.createForSubscriptionId(subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }

    companion object {
        /** The default SMS subscription, or a negative/invalid id when none is set. */
        fun defaultSmsSubscriptionId(): Int = SubscriptionManager.getDefaultSmsSubscriptionId()
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
