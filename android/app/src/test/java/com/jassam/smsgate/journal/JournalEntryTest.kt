package com.jassam.smsgate.journal

import com.jassam.smsgate.api.ClaimedMessage
import com.jassam.smsgate.api.Status
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalEntryTest {
    @Test
    fun toStringRedactsNumberAndBody() {
        val e = JournalEntry(
            id = "m1", to = "+212600000001", body = "secret code 4242", simSlot = 1, expiresAt = 5,
            createdAt = 1, updatedAt = 2, reported = setOf(Status.SENT),
        )
        val s = e.toString()
        assertFalse(s.contains("+212600000001"))
        assertFalse(s.contains("600000001"))
        assertFalse(s.contains("secret"))
        assertFalse(s.contains("4242"))
        assertTrue(s.contains("id=m1"))
        assertTrue(s.contains("to=***"))
        assertTrue(s.contains("body=<16 chars>"))
        assertTrue(e.copy(body = null).toString().contains("body=null"))
    }

    @Test
    fun claimedFromClaimResponse() {
        val msg = ClaimedMessage(
            id = "m1", to = "+1555", body = "hi", simSlot = null,
            expiresAt = Instant.fromEpochMilliseconds(9_000), serverTime = Instant.fromEpochMilliseconds(1_000),
        )
        assertEquals(
            JournalEntry(id = "m1", to = "+1555", body = "hi", simSlot = null, expiresAt = 9_000, createdAt = 42, updatedAt = 42),
            JournalEntry.claimed(msg, now = 42),
        )
        assertEquals(JournalState.CLAIMED, JournalEntry.claimed(msg, 42).state)
    }

    @Test
    fun allPartsMask() {
        assertEquals(0b1L, JournalEntry.allParts(1))
        assertEquals(0b111L, JournalEntry.allParts(3))
        assertEquals(Long.MAX_VALUE, JournalEntry.allParts(63))
        assertEquals(-1L, JournalEntry.allParts(64))
        assertTrue(runCatching { JournalEntry.allParts(0) }.isFailure)
        assertTrue(runCatching { JournalEntry.allParts(65) }.isFailure)
    }
}
