package com.jassam.smsgate.sms

import android.app.Application
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSmsManager

@RunWith(RobolectricTestRunner::class)
class PlatformSmsTransportTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    private fun checkSubscriptionSelection() {
        ShadowSmsManager.setDefaultSmsSubscriptionId(5)
        val t = PlatformSmsTransport(app)
        assertEquals(7, t.manager(7)!!.subscriptionId)
        assertEquals(9, t.manager(9)!!.subscriptionId)
        assertNotNull(t.manager(null))
    }

    @Test
    @Config(sdk = [34])
    fun api31PlusUsesCreateForSubscriptionId() = checkSubscriptionSelection()

    @Test
    @Config(sdk = [30])
    fun belowApi31UsesGetSmsManagerForSubscriptionId() = checkSubscriptionSelection()

    @Test
    @Config(sdk = [34])
    fun channelDividesAndSendsMultipart() {
        val t = PlatformSmsTransport(app)
        val channel = t.channel(null)!!
        // divideMessage needs the telephony service, which Robolectric doesn't provide.
        val parts = listOf("part one ", "part two")
        val sent = parts.indices.map { SmsIntents.pendingIntent(app, PartRef("m1", it, PartKind.SENT)) }
        val delivered = parts.indices.map { SmsIntents.pendingIntent(app, PartRef("m1", it, PartKind.DELIVERED)) }
        channel.sendMultipartTextMessage("+15550001", parts, sent, delivered)
        @Suppress("DEPRECATION")
        val params = shadowOf(app.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()).lastSentMultipartTextMessageParams
        assertEquals("+15550001", params.destinationAddress)
        assertEquals(parts, params.parts)
        assertEquals(sent, params.sentIntents)
        assertEquals(delivered, params.deliveryIntents)
    }
}
