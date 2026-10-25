package com.jassam.smsgate.journal

import com.jassam.smsgate.api.Status
import com.jassam.smsgate.journal.JournalState.CLAIMED
import com.jassam.smsgate.journal.JournalState.DELIVERED
import com.jassam.smsgate.journal.JournalState.FAILED
import com.jassam.smsgate.journal.JournalState.SENDING
import com.jassam.smsgate.journal.JournalState.SENT
import com.jassam.smsgate.journal.JournalState.SKIPPED_EXPIRED
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JournalDaoTest {
    private lateinit var db: JournalDb
    private lateinit var dao: JournalDao

    @Before
    fun setUp() {
        db = inMemoryJournal()
        dao = db.journalDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertClaimedDedupesOnId() = runBlocking {
        assertTrue(dao.insertClaimed(claimedEntry("m1", body = "first")))
        assertFalse(dao.insertClaimed(claimedEntry("m1", body = "second", now = 5_000)))
        val row = dao.get("m1")!!
        assertEquals("first", row.body)
        assertEquals(CLAIMED, row.state)
        assertEquals(1_000, row.createdAt)
    }

    @Test
    fun insertClaimedRejectsNonFreshRows() = runBlocking {
        assertTrue(runCatching { dao.insertClaimed(claimedEntry("m1").copy(state = SENDING)) }.isFailure)
        assertTrue(runCatching { dao.insertClaimed(claimedEntry("m1").copy(reported = setOf(Status.SENT))) }.isFailure)
        assertNull(dao.get("m1"))
    }

    @Test
    fun markSendingOnlyFromClaimed() = runBlocking {
        dao.insertClaimed(claimedEntry("m1"))
        assertEquals(1, dao.markSending("m1", 2_000))
        assertEquals(SENDING, dao.get("m1")!!.state)
        assertEquals(2_000, dao.get("m1")!!.updatedAt)
        assertEquals(0, dao.markSending("m1", 3_000))
        assertEquals(0, dao.markSending("missing", 3_000))
        for (s in JournalState.entries - CLAIMED) {
            val id = "x-$s"
            dao.insertClaimed(claimedEntry(id))
            db.force(id, s)
            assertEquals("from $s", 0, dao.markSending(id, 3_000))
            assertEquals(s, dao.get(id)!!.state)
        }
    }

    @Test
    fun concurrentMarkSendingHasExactlyOneWinner() = runBlocking {
        dao.insertClaimed(claimedEntry("m1"))
        val callers = 8
        val ready = CountDownLatch(callers)
        val go = CountDownLatch(1)
        val results = (1..callers).map {
            async(Dispatchers.IO) {
                ready.countDown()
                go.await(5, TimeUnit.SECONDS)
                dao.markSending("m1", 2_000)
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        assertEquals(listOf(1) + List(callers - 1) { 0 }, results.awaitAll().sortedDescending())
    }

    @Test
    fun setPartsOnceWhileSending() = runBlocking {
        dao.insertClaimed(claimedEntry("m1"))
        assertEquals(0, dao.setParts("m1", 3, 2_000))
        dao.markSending("m1", 2_000)
        assertEquals(1, dao.setParts("m1", 3, 2_100))
        assertEquals(0, dao.setParts("m1", 4, 2_200))
        assertEquals(3, dao.get("m1")!!.parts)
    }

    @Test
    fun moveToEveryPair() = runBlocking {
        for (from in JournalState.entries) for (to in JournalState.entries) {
            val id = "m-$from-$to"
            dao.insertClaimed(claimedEntry(id))
            db.force(id, from, parts = 1)
            val result = dao.moveTo(id, to, 9_000, errorCode = "some_code", errorMessage = "msg")
            val row = dao.get(id)!!
            if (JournalState.canMove(from, to)) {
                assertTrue("$from -> $to", result.moved)
                assertEquals(to, row.state)
                assertEquals(9_000, row.updatedAt)
                val expected = JournalState.reportFor(to)
                assertEquals("$from -> $to", expected, result.report?.status)
                assertEquals(setOfNotNull(expected), row.reported)
            } else {
                assertEquals("$from -> $to", MoveResult.REFUSED, result)
                assertEquals(from, row.state)
                assertEquals(1_000, row.updatedAt)
                assertEquals(emptySet<Status>(), row.reported)
            }
        }
    }

    @Test
    fun moveToFailedCarriesErrorAndReportsOnce() = runBlocking {
        dao.insertClaimed(claimedEntry("m1"))
        dao.markSending("m1", 2_000)
        val r = dao.moveTo("m1", FAILED, 3_000, "sim_unavailable", "No active SIM in slot 1")
        assertEquals(PendingReport("m1", Status.FAILED, "sim_unavailable", "No active SIM in slot 1"), r.report)
        assertEquals(MoveResult.REFUSED, dao.moveTo("m1", FAILED, 3_000, "sim_unavailable", "again"))
        val row = dao.get("m1")!!
        assertEquals("sim_unavailable", row.errorCode)
        assertEquals("No active SIM in slot 1", row.errorMessage)
        assertEquals(setOf(Status.FAILED), row.reported)
    }

    @Test
    fun skippedExpiredReportsFailed() = runBlocking {
        dao.insertClaimed(claimedEntry("m1"))
        val r = dao.moveTo("m1", SKIPPED_EXPIRED, 2_000, "expired_on_device", null)
        assertEquals(PendingReport("m1", Status.FAILED, "expired_on_device", null), r.report)
        assertEquals(0, dao.markSending("m1", 2_000))
    }

    @Test
    fun moveToRequiresErrorCodeForFailures() = runBlocking {
        dao.insertClaimed(claimedEntry("m1"))
        assertTrue(runCatching { dao.moveTo("m1", FAILED, 2_000) }.isFailure)
        assertTrue(runCatching { dao.moveTo("m1", SKIPPED_EXPIRED, 2_000) }.isFailure)
        assertEquals(CLAIMED, dao.get("m1")!!.state)
        assertEquals(MoveResult.REFUSED, dao.moveTo("missing", SENT, 2_000))
    }

    private suspend fun sending(id: String, parts: Int) {
        dao.insertClaimed(claimedEntry(id))
        assertEquals(1, dao.markSending(id, 2_000))
        assertEquals(1, dao.setParts(id, parts, 2_000))
    }

    @Test
    fun threePartsOneFailsReportsFailedExactlyOnce() = runBlocking {
        sending("m1", 3)
        assertNull(dao.applyPartEvent("m1", PartEvent.Sent(0), 3_000))
        val failed = dao.applyPartEvent("m1", PartEvent.SendFailed(1, "generic_failure", "part 2"), 3_100)
        assertEquals(PendingReport("m1", Status.FAILED, "generic_failure", "part 2"), failed)
        assertNull(dao.applyPartEvent("m1", PartEvent.SendFailed(2, "radio_off", "part 3"), 3_200))
        assertNull(dao.applyPartEvent("m1", PartEvent.SendFailed(1, "generic_failure", "part 2"), 3_300))
        assertNull(dao.applyPartEvent("m1", PartEvent.Sent(2), 3_400))
        val row = dao.get("m1")!!
        assertEquals(FAILED, row.state)
        assertEquals("generic_failure", row.errorCode)
        assertEquals(setOf(Status.FAILED), row.reported)
        assertEquals(3_100, row.updatedAt)
    }

    @Test
    fun duplicateAndOutOfOrderSentResultsReportSentOnce() = runBlocking {
        sending("m1", 3)
        val reports = listOf(2, 2, 0, 2, 0, 1, 1, 0).mapNotNull { dao.applyPartEvent("m1", PartEvent.Sent(it), 3_000) }
        assertEquals(listOf(PendingReport("m1", Status.SENT)), reports)
        val row = dao.get("m1")!!
        assertEquals(SENT, row.state)
        assertEquals(0b111L, row.partsSent)
        assertEquals(setOf(Status.SENT), row.reported)
    }

    @Test
    fun deliveredAfterSent() = runBlocking {
        sending("m1", 2)
        val reports = listOf(
            PartEvent.Sent(0), PartEvent.Sent(1), PartEvent.Delivered(1), PartEvent.Delivered(1), PartEvent.Delivered(0),
            PartEvent.Delivered(0), PartEvent.DeliveryFailed(0, "delivery_failed", "late"),
        ).mapNotNull { dao.applyPartEvent("m1", it, 3_000) }
        assertEquals(listOf(PendingReport("m1", Status.SENT), PendingReport("m1", Status.DELIVERED)), reports)
        val row = dao.get("m1")!!
        assertEquals(DELIVERED, row.state)
        assertEquals(setOf(Status.SENT, Status.DELIVERED), row.reported)
    }

    @Test
    fun deliveryOvertakingSentReportsOnlyDelivered() = runBlocking {
        sending("m1", 1)
        assertEquals(PendingReport("m1", Status.DELIVERED), dao.applyPartEvent("m1", PartEvent.Delivered(0), 3_000))
        assertNull(dao.applyPartEvent("m1", PartEvent.Sent(0), 3_100))
        assertNull(dao.applyPartEvent("m1", PartEvent.SendFailed(0, "generic_failure", "late"), 3_100))
        assertEquals(DELIVERED, dao.get("m1")!!.state)
        assertEquals(setOf(Status.DELIVERED), dao.get("m1")!!.reported)
    }

    @Test
    fun deliveryFailureAfterSentFails() = runBlocking {
        sending("m1", 2)
        dao.applyPartEvent("m1", PartEvent.Sent(0), 3_000)
        dao.applyPartEvent("m1", PartEvent.Sent(1), 3_000)
        dao.applyPartEvent("m1", PartEvent.Delivered(0), 3_000)
        val r = dao.applyPartEvent("m1", PartEvent.DeliveryFailed(1, "delivery_failed", "status 0x41"), 3_500)
        assertEquals(PendingReport("m1", Status.FAILED, "delivery_failed", "status 0x41"), r)
        val row = dao.get("m1")!!
        assertEquals(FAILED, row.state)
        assertEquals(setOf(Status.SENT, Status.FAILED), row.reported)
    }

    @Test
    fun reportedSetSurvivesReadBack() = runBlocking {
        sending("m1", 1)
        dao.applyPartEvent("m1", PartEvent.Sent(0), 3_000)
        // Simulate a re-run after the decision was committed: the persisted set blocks a second report.
        db.force("m1", SENDING, parts = 1, sent = 0)
        assertNull(dao.applyPartEvent("m1", PartEvent.Sent(0), 3_100))
        assertEquals(SENT, dao.get("m1")!!.state)
        assertEquals(setOf(Status.SENT), dao.get("m1")!!.reported)
    }

    @Test
    fun partResultsIgnoredForUnknownOrUnsentRows() = runBlocking {
        assertNull(dao.applyPartEvent("missing", PartEvent.Sent(0), 3_000))
        dao.insertClaimed(claimedEntry("m1"))
        assertNull(dao.applyPartEvent("m1", PartEvent.Sent(0), 3_000))
        dao.markSending("m1", 2_000)
        assertNull("parts not recorded yet", dao.applyPartEvent("m1", PartEvent.Sent(0), 3_000))
        dao.setParts("m1", 2, 2_000)
        assertNull("part out of range", dao.applyPartEvent("m1", PartEvent.Sent(2), 3_000))
        assertEquals(0L, dao.get("m1")!!.partsSent)
    }

    @Test
    fun retentionPurgesBodiesAndDeletesOldRows() = runBlocking {
        val day = JournalRetention.BODY_TTL_MS
        val now = 100 * day
        dao.insertClaimed(claimedEntry("fresh", now = now - day + 1))
        dao.insertClaimed(claimedEntry("day-old", now = now - day - 1))
        dao.insertClaimed(claimedEntry("month-old", now = now - JournalRetention.ROW_TTL_MS - 1))
        db.force("day-old", DELIVERED, parts = 1)
        JournalRetention.run(dao, now)
        assertEquals("hello", dao.get("fresh")!!.body)
        val dayOld = dao.get("day-old")!!
        assertNull(dayOld.body)
        assertEquals(DELIVERED, dayOld.state)
        assertNull(dao.get("month-old"))
    }

    @Test
    fun purgeBodiesAnyState() = runBlocking {
        for (s in JournalState.entries) {
            dao.insertClaimed(claimedEntry("m-$s", now = 10))
            db.force("m-$s", s)
        }
        assertEquals(JournalState.entries.size, dao.purgeBodies(11))
        assertEquals(0, dao.purgeBodies(11))
        for (s in JournalState.entries) assertNull(dao.get("m-$s")!!.body)
        assertEquals(JournalState.entries.size, dao.deleteOlderThan(11))
    }

    @Test
    fun staleSendingListsOnlyIdleSendingRows() = runBlocking {
        sending("old", 2) // updatedAt 2_000
        sending("progressing", 2)
        dao.applyPartEvent("progressing", PartEvent.Sent(0), 9_000)
        sending("done", 1)
        dao.applyPartEvent("done", PartEvent.Sent(0), 2_000)
        dao.insertClaimed(claimedEntry("claimed", now = 1))
        assertEquals(listOf("old"), dao.staleSending(5_000).map { it.id })
        assertEquals(listOf("old", "progressing"), dao.staleSending(10_000).map { it.id })
    }
}
