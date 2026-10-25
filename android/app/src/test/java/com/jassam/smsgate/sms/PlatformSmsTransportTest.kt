package com.jassam.smsgate.sms

import android.app.Application
import android.content.Context
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
class PlatformSmsTransportTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    /**
     * On API 31+ SmsManager carries the Context it was created for: createForSubscriptionId
     * keeps the factory's context, the deprecated static getSmsManagerForSubscriptionId has
     * none. Below 31 there is no such field (and no createForSubscriptionId).
     */
    private fun contextOf(manager: SmsManager): Context? =
        SmsManager::class.java.declaredFields.firstOrNull { it.name == "mContext" }
            ?.apply { isAccessible = true }?.get(manager) as Context?

    @Test
    @Config(sdk = [34])
    fun api31PlusUsesCreateForSubscriptionId() {
        val t = PlatformSmsTransport(app)
        val m = t.manager(7)!!
        assertEquals(7, m.subscriptionId)
        assertNotNull("bound through createForSubscriptionId", contextOf(m))
        @Suppress("DEPRECATION")
        assertNull("the static path has no context", contextOf(SmsManager.getSmsManagerForSubscriptionId(7)))
        assertEquals(9, t.manager(9)!!.subscriptionId)
    }

    @Test
    @Config(sdk = [30])
    fun belowApi31UsesGetSmsManagerForSubscriptionId() {
        val t = PlatformSmsTransport(app)
        assertEquals(7, t.manager(7)!!.subscriptionId)
        assertEquals(9, t.manager(9)!!.subscriptionId)
        assertNull(contextOf(t.manager(7)!!))
    }

    @Test
    @Config(sdk = [34])
    fun defaultSmsSubscriptionComesFromSubscriptionManager() {
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(4)
        assertEquals(4, PlatformSmsTransport.defaultSmsSubscriptionId())
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        assertEquals(SubscriptionManager.INVALID_SUBSCRIPTION_ID, PlatformSmsTransport.defaultSmsSubscriptionId())
    }

    @Test
    @Config(sdk = [34])
    fun channelSendsMultipartThroughTheSubscriptionManager() {
        val t = PlatformSmsTransport(app)
        val manager = t.manager(7)!!
        // divideMessage needs the telephony service, which Robolectric doesn't provide.
        val parts = listOf("part one ", "part two")
        val sent = parts.indices.map { SmsIntents.pendingIntent(app, PartRef("m1", it, PartKind.SENT)) }
        val delivered = parts.indices.map { SmsIntents.pendingIntent(app, PartRef("m1", it, PartKind.DELIVERED)) }
        t.channel(7)!!.sendMultipartTextMessage("+15550001", parts, sent, delivered)
        // createForSubscriptionId returns the cached per-subscription instance.
        assertSame(manager, t.manager(7))
        val params = shadowOf(manager).lastSentMultipartTextMessageParams
        assertEquals("+15550001", params.destinationAddress)
        assertEquals(parts, params.parts)
        assertEquals(sent, params.sentIntents)
        assertEquals(delivered, params.deliveryIntents)
    }
}
