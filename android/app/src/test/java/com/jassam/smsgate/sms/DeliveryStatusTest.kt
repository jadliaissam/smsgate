package com.jassam.smsgate.sms

import android.app.Activity
import com.jassam.smsgate.sms.DeliveryOutcome.DELIVERED
import com.jassam.smsgate.sms.DeliveryOutcome.FAILED
import com.jassam.smsgate.sms.DeliveryOutcome.PENDING
import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryStatusTest {
    @Test
    fun gsmTpStatus() {
        for (s in 0x00..0x1F) assertEquals(DELIVERED, DeliveryStatus.classify("3gpp", s))
        for (s in 0x20..0x3F) assertEquals(PENDING, DeliveryStatus.classify("3gpp", s))
        for (s in 0x40..0xFF) assertEquals(FAILED, DeliveryStatus.classify("3gpp", s))
        assertEquals(DELIVERED, DeliveryStatus.classify(null, 0))
    }

    @Test
    fun cdmaErrorClass() {
        assertEquals(DELIVERED, DeliveryStatus.classify("3gpp2", 0x0002 shl 16))
        assertEquals(PENDING, DeliveryStatus.classify("3gpp2", (2 shl 8 or 0x04) shl 16))
        assertEquals(FAILED, DeliveryStatus.classify("3gpp2", (3 shl 8 or 0x04) shl 16))
    }

    @Test
    fun withoutStatusUsesResultCode() {
        assertEquals(DELIVERED, DeliveryStatus.outcome(Activity.RESULT_OK, null, null))
        assertEquals(PENDING, DeliveryStatus.outcome(Activity.RESULT_CANCELED, null, null))
        assertEquals(FAILED, DeliveryStatus.outcome(Activity.RESULT_OK, "3gpp", 0x41))
    }
}
