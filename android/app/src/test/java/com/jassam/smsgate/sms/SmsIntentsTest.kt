package com.jassam.smsgate.sms

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsIntentsTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val id = "0b7e3c8a-5a1d-4f8e-9a51-6e0e7d2f9c11"

    @Test
    fun uriFormatAndRoundTrip() {
        val ref = PartRef(id, 2, PartKind.DELIVERED)
        assertEquals("smsgate://sms/$id/2/delivered", SmsIntents.uri(ref).toString())
        assertEquals(ref, SmsIntents.parse(SmsIntents.intent(app, ref)))
        assertEquals(PartRef(id, 0, PartKind.SENT), SmsIntents.parse(Uri.parse("smsgate://sms/$id/0/sent")))
        assertEquals(PartRef(id, 63, PartKind.SENT), SmsIntents.parse(Uri.parse("smsgate://sms/$id/63/sent")))
    }

    @Test
    fun malformedOrForeignIsRejected() {
        val bad = listOf(
            "smsgate://sms/$id/0", "smsgate://sms/$id/0/sent/x", "smsgate://sms/$id/01/sent", "smsgate://sms/$id/-1/sent",
            "smsgate://sms/$id/64/sent", "smsgate://sms/$id/a/sent", "smsgate://sms/$id/0/read", "smsgate://sms/$id/0/SENT",
            "smsgate://pair/$id/0/sent", "https://sms/$id/0/sent", "smsgate://sms/a%2Fb/0/sent", "smsgate://sms/$id/0/sent?x=1",
            "smsgate://sms/$id/0/sent#f", "smsgate://sms//$id/0/sent", "smsgate://sms/${"a".repeat(65)}/0/sent",
            "smsgate://sms/a b/0/sent", "smsgate://user@sms/$id/0/sent", "smsgate://sms:1/$id/0/sent", "smsgate:sms/$id/0/sent",
        )
        for (u in bad) assertNull(u, SmsIntents.parse(Uri.parse(u)))
        assertNull(SmsIntents.parse(null as Uri?))
        assertNull(SmsIntents.parse(null as Intent?))
        assertNull("no action", SmsIntents.parse(Intent().setData(Uri.parse("smsgate://sms/$id/0/sent"))))
        assertNull("wrong action", SmsIntents.parse(Intent("other", Uri.parse("smsgate://sms/$id/0/sent"))))
        assertNull("no data", SmsIntents.parse(Intent(SmsIntents.ACTION)))
    }

    @Test
    fun requestCodesAndUrisUniqueAcrossPartsKindsAndMessages() {
        val refs = listOf(id, "0b7e3c8a-5a1d-4f8e-9a51-6e0e7d2f9c12", "m3").flatMap { m ->
            (0 until 10).flatMap { p -> PartKind.entries.map { PartRef(m, p, it) } }
        }
        assertEquals(refs.size, refs.map(SmsIntents::requestCode).toSet().size)
        assertEquals(refs.size, refs.map { SmsIntents.uri(it).toString() }.toSet().size)
        assertEquals(SmsIntents.requestCode(refs[5]), SmsIntents.requestCode(refs[5].copy()))
    }

    @Test
    fun pendingIntentsAreExplicitBroadcastsWithPerKindFlags() {
        val sent = SmsIntents.pendingIntent(app, PartRef(id, 1, PartKind.SENT))
        val delivered = SmsIntents.pendingIntent(app, PartRef(id, 1, PartKind.DELIVERED))
        for ((pi, kind) in listOf(sent to PartKind.SENT, delivered to PartKind.DELIVERED)) {
            val shadow = shadowOf(pi)
            assertTrue(shadow.isBroadcast)
            assertEquals(SmsIntents.requestCode(PartRef(id, 1, kind)), shadow.requestCode)
            val intent = shadow.savedIntent
            assertEquals(ComponentName(app, SmsResultReceiver::class.java), intent.component)
            assertEquals(SmsIntents.ACTION, intent.action)
            assertEquals(SmsIntents.uri(PartRef(id, 1, kind)), intent.data)
        }
        assertTrue(shadowOf(sent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        // Delivery reports arrive as fill-in extras, which immutable PendingIntents drop.
        assertTrue(shadowOf(delivered).flags and PendingIntent.FLAG_MUTABLE != 0)
        assertEquals(0, shadowOf(delivered).flags and PendingIntent.FLAG_IMMUTABLE)
        assertTrue(sent != delivered)
    }

    @Test
    @Config(sdk = [30])
    fun deliveryIntentsMutableByDefaultBeforeApi31() {
        assertEquals(PendingIntent.FLAG_IMMUTABLE, SmsIntents.flags(PartKind.SENT))
        assertEquals(0, SmsIntents.flags(PartKind.DELIVERED))
    }
}
