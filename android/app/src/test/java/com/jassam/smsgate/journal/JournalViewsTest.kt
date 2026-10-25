package com.jassam.smsgate.journal

import com.jassam.smsgate.journal.JournalState.CLAIMED
import com.jassam.smsgate.journal.JournalState.DELIVERED
import com.jassam.smsgate.journal.JournalState.FAILED
import com.jassam.smsgate.journal.JournalState.SENDING
import com.jassam.smsgate.journal.JournalState.SENT
import com.jassam.smsgate.journal.JournalState.SKIPPED_EXPIRED
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The log and home queries: observeRecent, observe, observeCounts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JournalViewsTest {
    private lateinit var db: JournalDb
    private lateinit var dao: JournalDao

    @Before
    fun setUp() {
        db = inMemoryJournal()
        dao = db.journalDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun put(id: String, createdAt: Long, state: JournalState = CLAIMED) {
        dao.insertClaimed(claimedEntry(id, now = createdAt))
        if (state != CLAIMED) db.force(id, state)
    }

    @Test
    fun recentIsNewestFirstWithIdAsTieBreakAndBounded() = runBlocking {
        put("a", 1_000)
        put("b", 3_000)
        put("c", 2_000)
        put("d", 3_000) // same time as b: the larger (later UUIDv7) id first
        assertEquals(listOf("d", "b", "c", "a"), dao.observeRecent(10).first().map { it.id })
        assertEquals(listOf("d", "b"), dao.observeRecent(2).first().map { it.id })
    }

    @Test
    fun recentRowsCarryMetadataButNoBody() = runBlocking {
        put("a", 1_000, SENT)
        val row = dao.observeRecent(10).first().single()
        assertEquals("+212600000001", row.to)
        assertEquals(SENT, row.state)
        assertEquals(1_000, row.createdAt)
        // JournalSummary has no body field at all; its toString hides the number.
        assertEquals(false, row.toString().contains("+212"))
    }

    @Test
    fun recentReEmitsOnChange() = runBlocking {
        put("a", 1_000)
        val flow = dao.observeRecent(10)
        assertEquals(CLAIMED, flow.first().single().state)
        dao.markSending("a", 2_000)
        withTimeout(5_000) { flow.first { it.single().state == SENDING } }
        Unit
    }

    @Test
    fun observeOneRowWithItsBodyAndNullOnceDeleted() = runBlocking {
        put("a", 1_000)
        assertEquals("hello", dao.observe("a").first()?.body)
        dao.purgeBodies(2_000)
        assertNull(dao.observe("a").first()?.body)
        dao.deleteOlderThan(2_000)
        assertNull(dao.observe("a").first())
    }

    @Test
    fun countsUseTheHalfOpenWindowAndGroupStates() = runBlocking {
        val start = 100_000L
        val end = 200_000L
        put("before", start - 1, DELIVERED)
        put("first", start, DELIVERED)
        put("sent", 150_000, SENT)
        put("failed", 150_001, FAILED)
        put("expired", 150_002, SKIPPED_EXPIRED)
        put("inflight", 150_003, SENDING)
        put("claimed", 150_004, CLAIMED)
        put("lastMs", end - 1, SENT)
        put("atEnd", end, FAILED)
        // sent = SENT + DELIVERED; failed = FAILED + SKIPPED_EXPIRED; in-flight rows count nowhere.
        assertEquals(DayCounts(sent = 3, delivered = 1, failed = 2), dao.observeCounts(start, end).first())
    }

    @Test
    fun countsAreZeroForAnEmptyWindow() = runBlocking {
        assertEquals(DayCounts.ZERO, dao.observeCounts(0, 1_000).first())
    }
}
