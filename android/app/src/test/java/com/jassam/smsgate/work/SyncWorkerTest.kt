package com.jassam.smsgate.work

import androidx.work.ListenableWorker.Result
import com.jassam.smsgate.api.Heartbeat
import com.jassam.smsgate.api.Status
import com.jassam.smsgate.journal.JournalEntry
import com.jassam.smsgate.journal.JournalRetention
import com.jassam.smsgate.journal.JournalState
import com.jassam.smsgate.journal.PendingReport
import com.jassam.smsgate.journal.claimedEntry
import com.jassam.smsgate.journal.force
import com.jassam.smsgate.sms.ErrorCodes
import com.jassam.smsgate.work.WorkTestEnv.Companion.ID
import com.jassam.smsgate.work.WorkTestEnv.Companion.ID2
import com.jassam.smsgate.work.WorkTestEnv.Companion.SERVER_TIME
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var env: WorkTestEnv

    private val stale get() = env.wall - CrashRecovery.STALE_AFTER_MS - 1
    private val id3 = "0192f0c4-0000-7000-8000-0000000000a3"

    @Before
    fun setUp() {
        env = WorkTestEnv(tmp.root)
        env.pair()
    }

    @After
    fun tearDown() = env.close()

    private fun run(attempt: Int = 0): Result = runBlocking { env.worker<SyncWorker>(androidx.work.Data.EMPTY, attempt).doWork() }

    private fun respondHeartbeat() = env.respond(200, """{"server_time":"$SERVER_TIME"}""")

    private fun respondPending(vararg ids: String) = env.respond(
        200,
        """{"server_time":"$SERVER_TIME","messages":[""" +
            ids.joinToString(",") { """{"id":"$it","expires_at":"2027-01-15T08:05:00Z"}""" } + "]}",
    )

    @Test
    fun heartbeatThenPendingEnqueuesUnjournaledMessages() = runBlocking {
        env.dao.insertClaimed(claimedEntry(id3, now = env.wall))
        respondHeartbeat()
        respondPending(ID, ID2, id3)
        assertEquals(Result.success(), run())

        val hb = env.take()
        assertEquals("POST", hb.method)
        assertEquals("/device/v1/heartbeat", hb.url.encodedPath)
        assertEquals("Bearer dt_1", hb.headers["Authorization"])
        assertEquals(
            Json.parseToJsonElement(
                """{"battery":81,"charging":true,"signal":3,"sims":[{"slot":0,"subscription_id":11,"carrier":"Orange"}],"app_version":"1.2.3"}""",
            ),
            with(env) { hb.json() },
        )
        val pending = env.take()
        assertEquals("GET", pending.method)
        assertEquals("/device/v1/messages/pending", pending.url.encodedPath)
        assertEquals(
            listOf(RecordingWorkQueue.Process(ID, false), RecordingWorkQueue.Process(ID2, false)),
            env.queue.processed,
        )
    }

    @Test
    fun heartbeatOmitsWhatTheSourceCannotRead() = runBlocking {
        env.heartbeat = { Heartbeat(appVersion = "1.2.3") }
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run())
        assertEquals(Json.parseToJsonElement("""{"app_version":"1.2.3"}"""), with(env) { env.take().json() })
    }

    @Test
    fun aThrowingHeartbeatSourceDoesNotStopTheSync() = runBlocking {
        env.heartbeat = { throw SecurityException("no permission") }
        respondHeartbeat()
        respondPending(ID)
        assertEquals(Result.success(), run())
        assertEquals(Json.parseToJsonElement("{}"), with(env) { env.take().json() })
        assertEquals(listOf(RecordingWorkQueue.Process(ID, false)), env.queue.processed)
    }

    @Test
    fun crashRecoveryReportsOutcomeUnknownAndNeverSends() = runBlocking {
        env.dao.insertClaimed(claimedEntry(ID, now = stale).copy(expiresAt = env.wall + 600_000))
        env.db.force(ID, JournalState.SENDING, parts = 1)
        env.dao.insertClaimed(claimedEntry(ID2, now = env.wall).copy(expiresAt = env.wall + 600_000))
        env.db.force(ID2, JournalState.SENDING, parts = 1) // recent: its result may still come
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run())

        val row = env.dao.get(ID)!!
        assertEquals(JournalState.FAILED, row.state)
        assertEquals(ErrorCodes.OUTCOME_UNKNOWN, row.errorCode)
        assertEquals(JournalState.SENDING, env.dao.get(ID2)!!.state)
        assertTrue(env.transport.sends.isEmpty())
        // Enqueued by recovery and again by the unacked re-enqueue: the real queue's
        // unique KEEP work collapses the two into one report job.
        assertEquals(setOf(PendingReport(ID, Status.FAILED, ErrorCodes.OUTCOME_UNKNOWN, row.errorMessage)), env.queue.reports.toSet())
    }

    @Test
    fun recoveryOnAStaleReadDoesNotOverwriteALateSent() = runBlocking {
        env.dao.insertClaimed(claimedEntry(ID, now = stale))
        env.db.force(ID, JournalState.SENDING, parts = 1)
        val snapshot = env.dao.staleSending(env.wall - CrashRecovery.STALE_AFTER_MS)
        assertEquals(1, snapshot.size)
        env.db.force(ID, JournalState.SENT, parts = 1, sent = 1) // the late sent result wins the race

        CrashRecovery.failStaleSending(env.dao, env.queue, snapshot, env.wall)

        assertEquals(JournalState.SENT, env.dao.get(ID)!!.state)
        assertNull(env.dao.get(ID)!!.errorCode)
        assertTrue(env.queue.reports.isEmpty())
    }

    @Test
    fun staleClaimedRowIsResumedOnceAndNeverResent() = runBlocking {
        env.dao.insertClaimed(
            JournalEntry(
                id = ID, to = "+212600000001", body = "hi", simSlot = null,
                expiresAt = env.wall + 3_600_000, createdAt = stale, updatedAt = stale,
            ),
        )
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run())
        assertEquals(1, env.transport.sends.size)
        assertEquals(JournalState.SENDING, env.dao.get(ID)!!.state)

        // Later the result never came: the next sync reports outcome_unknown, never resends.
        env.wall += CrashRecovery.STALE_AFTER_MS + 1
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run())
        assertEquals(1, env.transport.sends.size)
        assertEquals(JournalState.FAILED, env.dao.get(ID)!!.state)
        assertEquals(ErrorCodes.OUTCOME_UNKNOWN, env.dao.get(ID)!!.errorCode)
    }

    @Test
    fun staleClaimedRowWaitsWhileTheServerClockIsUnsynced() = runBlocking {
        // The phone's clock is behind: by its own clock the message is still valid,
        // but nothing has told this process the server time yet (heartbeat and pending fail).
        env.dao.insertClaimed(
            JournalEntry(
                id = ID, to = "+212600000001", body = "hi", simSlot = null,
                expiresAt = env.wall + 3_600_000, createdAt = stale, updatedAt = stale,
            ),
        )
        env.dropConnection()
        env.dropConnection()
        assertEquals(Result.retry(), run())
        assertTrue(env.transport.sends.isEmpty())
        assertEquals(JournalState.CLAIMED, env.dao.get(ID)!!.state)

        // Next sync reaches the server: the clock is synced and the row is resumed once.
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run(attempt = 1))
        assertEquals(1, env.transport.sends.size)
    }

    @Test
    fun everySyncReAssertsThePeriodicJob() = runBlocking {
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run())
        assertEquals(1, env.queue.periodic)
    }

    @Test
    fun noPeriodicReAssertWhenTheStoredTokenChangedDuringTheSync() = runBlocking {
        // Revoked and re-paired while this sync (holding the old token) was running.
        env.heartbeat = {
            runBlocking { env.store.save(env.base, "dev-2", "dt_2") }
            Heartbeat(appVersion = "1.2.3")
        }
        respondHeartbeat()
        respondPending()
        run()
        assertEquals(0, env.queue.periodic)
    }

    @Test
    fun aBare401HeartbeatDoesNotRevoke() = runBlocking {
        env.respond(401, "")
        respondPending()
        assertEquals(Result.success(), run())
        assertEquals("dt_1", env.store.deviceToken())
        assertEquals(0, env.queue.cancelled)
    }

    @Test
    fun theCurrentFcmTokenIsReSentOnlyWhenTheServerMayNotHaveIt() = runBlocking {
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run())
        assertEquals(listOf("fcm-current"), env.queue.tokens)

        env.sentTokens.remember("dev-1", "fcm-current")
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run())
        assertEquals(listOf("fcm-current"), env.queue.tokens)

        env.fcm = null // no Play services
        env.sentTokens.sent.clear()
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run())
        assertEquals(listOf("fcm-current"), env.queue.tokens)
    }

    @Test
    fun freshClaimedRowIsLeftToItsWorker() = runBlocking {
        env.dao.insertClaimed(claimedEntry(ID, now = env.wall).copy(expiresAt = env.wall + 600_000))
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run())
        assertTrue(env.transport.sends.isEmpty())
        assertEquals(JournalState.CLAIMED, env.dao.get(ID)!!.state)
    }

    @Test
    fun unackedReportsAreReEnqueued() = runBlocking {
        env.dao.insertClaimed(claimedEntry(ID, now = env.wall))
        env.dao.markSending(ID, env.wall)
        env.dao.moveTo(ID, JournalState.SENDING, JournalState.SENT, env.wall)
        env.dao.insertClaimed(claimedEntry(ID2, now = env.wall))
        env.dao.markSending(ID2, env.wall)
        env.dao.moveTo(ID2, JournalState.SENDING, JournalState.SENT, env.wall)
        env.dao.markAcked(ID2, Status.SENT)
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run())
        assertEquals(listOf(PendingReport(ID, Status.SENT)), env.queue.reports)
    }

    @Test
    fun retentionRunsLast() = runBlocking {
        val day = 86_400_000L
        env.dao.insertClaimed(claimedEntry(ID, now = env.wall - JournalRetention.ROW_TTL_MS - day))
        env.db.force(ID, JournalState.SENT)
        env.dao.insertClaimed(claimedEntry(ID2, now = env.wall - 2 * day))
        env.db.force(ID2, JournalState.SENT)
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run())
        assertNull(env.dao.get(ID))
        assertNull(env.dao.get(ID2)!!.body)
    }

    @Test
    fun everySyncReevaluatesTheConnectionPolicy() = runBlocking {
        respondHeartbeat()
        respondPending()
        assertEquals(Result.success(), run())
        assertEquals(1, env.evaluations)

        // Offline: still evaluated (power or the setting may have changed meanwhile).
        env.dropConnection()
        env.dropConnection()
        run(attempt = SyncWorker.MAX_ATTEMPTS - 1)
        assertEquals(2, env.evaluations)
    }

    @Test
    fun aFailingPolicyEvaluationDoesNotStopTheSync() = runBlocking {
        env.evaluate = { throw IllegalStateException("refused") }
        respondHeartbeat()
        respondPending(ID)
        assertEquals(Result.success(), run())
        assertEquals(listOf(RecordingWorkQueue.Process(ID, false)), env.queue.processed.toList())
    }

    @Test
    fun unauthorizedHeartbeatRevokesAndStops() = runBlocking {
        env.respondError(401, "unauthorized")
        assertEquals(Result.failure(), run())
        assertNull(env.store.load())
        assertEquals(1, env.queue.cancelled)
        assertEquals(1, env.realtimeStops) // the socket service is stopped too
        assertEquals(1, env.server.requestCount)
        assertTrue(env.queue.processed.isEmpty())
    }

    @Test
    fun unauthorizedPendingRevokes() = runBlocking {
        respondHeartbeat()
        env.respondError(401, "unauthorized")
        assertEquals(Result.failure(), run())
        assertNull(env.store.load())
        assertEquals(1, env.queue.cancelled)
    }

    @Test
    fun offlineSyncStillRecoversLocallyAndRetriesBoundedly() = runBlocking {
        env.dao.insertClaimed(claimedEntry(ID, now = stale))
        env.db.force(ID, JournalState.SENDING, parts = 1)
        env.dropConnection()
        env.dropConnection()
        assertEquals(Result.retry(), run(attempt = 0))
        assertEquals(JournalState.FAILED, env.dao.get(ID)!!.state)

        env.dropConnection()
        env.dropConnection()
        assertEquals(Result.success(), run(attempt = SyncWorker.MAX_ATTEMPTS - 1))
    }

    @Test
    fun noCredentialsCancelsWork() = runBlocking {
        env.store.clear()
        assertEquals(Result.failure(), run())
        assertEquals(0, env.server.requestCount)
        assertEquals(1, env.queue.cancelled)
    }
}
