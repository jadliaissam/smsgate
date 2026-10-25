package com.jassam.smsgate.journal

import com.jassam.smsgate.api.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalConvertersTest {
    private val c = JournalConverters()

    @Test
    fun roundTrips() {
        assertEquals("", c.fromStatuses(emptySet()))
        assertEquals("sent,failed", c.fromStatuses(setOf(Status.FAILED, Status.SENT)))
        assertEquals("sent,delivered,failed", c.fromStatuses(Status.entries.toSet()))
        for (set in listOf(emptySet(), setOf(Status.DELIVERED), Status.entries.toSet())) {
            assertEquals(set, c.toStatuses(c.fromStatuses(set)))
        }
    }

    @Test
    fun unknownTokensIgnored() {
        assertEquals(setOf(Status.SENT), c.toStatuses("sent,bogus,,SENT"))
        assertEquals(emptySet<Status>(), c.toStatuses("queued"))
    }
}
