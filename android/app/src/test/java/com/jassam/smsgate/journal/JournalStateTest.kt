package com.jassam.smsgate.journal

import com.jassam.smsgate.api.Status
import com.jassam.smsgate.journal.JournalState.CLAIMED
import com.jassam.smsgate.journal.JournalState.DELIVERED
import com.jassam.smsgate.journal.JournalState.FAILED
import com.jassam.smsgate.journal.JournalState.SENDING
import com.jassam.smsgate.journal.JournalState.SENT
import com.jassam.smsgate.journal.JournalState.SKIPPED_EXPIRED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalStateTest {
    /** The full table; also matches backend allowedFrom after SENDING (sent←dispatched, delivered/failed←dispatched|sent). */
    private val legal = setOf(
        CLAIMED to SENDING, CLAIMED to FAILED, CLAIMED to SKIPPED_EXPIRED,
        SENDING to SENT, SENDING to DELIVERED, SENDING to FAILED,
        SENT to DELIVERED, SENT to FAILED,
    )

    @Test
    fun everyPairMatchesTheTable() {
        for (from in JournalState.entries) for (to in JournalState.entries) {
            assertEquals("$from -> $to", (from to to) in legal, JournalState.canMove(from, to))
        }
    }

    @Test
    fun terminalStatesNeverMove() {
        val terminal = JournalState.entries.filter { it.isTerminal }
        assertEquals(listOf(DELIVERED, FAILED, SKIPPED_EXPIRED), terminal)
        for (from in terminal) for (to in JournalState.entries) assertFalse(JournalState.canMove(from, to))
    }

    @Test
    fun noSendingAgainOnceSentOrLater() {
        for (from in JournalState.entries - CLAIMED) assertFalse(JournalState.canMove(from, SENDING))
        assertTrue(JournalState.canMove(CLAIMED, SENDING))
    }

    @Test
    fun reportsPerTarget() {
        assertNull(JournalState.reportFor(CLAIMED))
        assertNull(JournalState.reportFor(SENDING))
        assertEquals(Status.SENT, JournalState.reportFor(SENT))
        assertEquals(Status.DELIVERED, JournalState.reportFor(DELIVERED))
        assertEquals(Status.FAILED, JournalState.reportFor(FAILED))
        assertEquals(Status.FAILED, JournalState.reportFor(SKIPPED_EXPIRED))
    }
}
