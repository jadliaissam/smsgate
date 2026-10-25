package com.jassam.smsgate.journal

import com.jassam.smsgate.journal.JournalState.DELIVERED
import com.jassam.smsgate.journal.JournalState.FAILED
import com.jassam.smsgate.journal.JournalState.SENDING
import com.jassam.smsgate.journal.JournalState.SENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartAggregatorTest {
    private fun entry(state: JournalState = SENDING, parts: Int = 3, sent: Long = 0, delivered: Long = 0) = JournalEntry(
        id = "m", to = "+1", body = "b", simSlot = null, expiresAt = 0, state = state, parts = parts,
        partsSent = sent, partsDelivered = delivered, createdAt = 0, updatedAt = 0,
    )

    private fun JournalEntry.with(c: PartChange?) =
        copy(state = c!!.state, partsSent = c.partsSent, partsDelivered = c.partsDelivered, errorCode = c.errorCode, errorMessage = c.errorMessage)

    @Test
    fun allPartsSentInAnyOrderMovesToSentOnce() {
        var e = entry()
        e = e.with(PartAggregator.apply(e, PartEvent.Sent(2)))
        assertEquals(SENDING, e.state)
        e = e.with(PartAggregator.apply(e, PartEvent.Sent(0)))
        assertEquals(SENDING, e.state)
        assertNull("duplicate", PartAggregator.apply(e, PartEvent.Sent(0)))
        e = e.with(PartAggregator.apply(e, PartEvent.Sent(1)))
        assertEquals(SENT, e.state)
        assertEquals(0b111L, e.partsSent)
        assertNull("duplicate after SENT", PartAggregator.apply(e, PartEvent.Sent(1)))
    }

    @Test
    fun anyFailedPartFails() {
        val e = entry(sent = 0b001)
        val c = PartAggregator.apply(e, PartEvent.SendFailed(1, "generic_failure", "m"))!!
        assertEquals(FAILED, c.state)
        assertEquals("generic_failure", c.errorCode)
        assertNull("terminal", PartAggregator.apply(e.with(c), PartEvent.SendFailed(2, "radio_off", "m")))
        assertNull("terminal", PartAggregator.apply(e.with(c), PartEvent.Sent(2)))
    }

    @Test
    fun failureForAnAlreadySentPartIsIgnored() {
        assertNull(PartAggregator.apply(entry(sent = 0b010), PartEvent.SendFailed(1, "generic_failure", "m")))
    }

    @Test
    fun deliveredImpliesSentAndCompletes() {
        var e = entry(parts = 2)
        e = e.with(PartAggregator.apply(e, PartEvent.Delivered(1)))
        assertEquals(SENDING, e.state)
        assertEquals(0b10L, e.partsSent)
        e = e.with(PartAggregator.apply(e, PartEvent.Sent(0)))
        assertEquals(SENT, e.state)
        assertNull(PartAggregator.apply(e, PartEvent.Delivered(1)))
        e = e.with(PartAggregator.apply(e, PartEvent.Delivered(0)))
        assertEquals(DELIVERED, e.state)
        assertNull(PartAggregator.apply(e, PartEvent.Delivered(0)))
    }

    @Test
    fun allDeliveredWhileSendingGoesStraightToDelivered() {
        val c = PartAggregator.apply(entry(parts = 1), PartEvent.Delivered(0))!!
        assertEquals(DELIVERED, c.state)
    }

    @Test
    fun pendingDeliveryMarksSentOnly() {
        val c = PartAggregator.apply(entry(parts = 1), PartEvent.DeliveryPending(0))!!
        assertEquals(SENT, c.state)
        assertEquals(0L, c.partsDelivered)
        assertNull(PartAggregator.apply(entry(parts = 1, state = SENT, sent = 1), PartEvent.DeliveryPending(0)))
    }

    @Test
    fun deliveryFailureFailsFromSent() {
        val c = PartAggregator.apply(entry(state = SENT, sent = 0b111, delivered = 0b001), PartEvent.DeliveryFailed(2, "delivery_failed", "m"))!!
        assertEquals(FAILED, c.state)
        assertEquals("delivery_failed", c.errorCode)
        assertNull(PartAggregator.apply(entry(state = SENT, sent = 0b111, delivered = 0b001), PartEvent.DeliveryFailed(0, "delivery_failed", "m")))
    }

    @Test
    fun ignoredOutsideSendingOrSentOrOutOfRange() {
        for (s in listOf(JournalState.CLAIMED, DELIVERED, FAILED, JournalState.SKIPPED_EXPIRED)) {
            assertNull(PartAggregator.apply(entry(state = s), PartEvent.Sent(0)))
        }
        assertNull(PartAggregator.apply(entry(parts = 0), PartEvent.Sent(0)))
        assertNull(PartAggregator.apply(entry(parts = 3), PartEvent.Sent(3)))
        assertNull(PartAggregator.apply(entry(parts = 3), PartEvent.Sent(-1)))
    }

    @Test
    fun sixtyFourParts() {
        var e = entry(parts = 64)
        for (p in 63 downTo 0) e = e.with(PartAggregator.apply(e, PartEvent.Sent(p)))
        assertEquals(SENT, e.state)
        assertEquals(-1L, e.partsSent)
    }
}
