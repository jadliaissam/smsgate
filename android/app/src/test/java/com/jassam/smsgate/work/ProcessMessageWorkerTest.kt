package com.jassam.smsgate.work

import androidx.work.ListenableWorker.Result
import androidx.work.workDataOf
import com.jassam.smsgate.api.ClaimedMessage
import com.jassam.smsgate.api.Status
import com.jassam.smsgate.journal.JournalState
import com.jassam.smsgate.journal.PendingReport
import com.jassam.smsgate.journal.claimedEntry
import com.jassam.smsgate.sms.ErrorCodes
import com.jassam.smsgate.work.WorkTestEnv.Companion.FUTURE
import com.jassam.smsgate.work.WorkTestEnv.Companion.ID
import com.jassam.smsgate.work.WorkTestEnv.Companion.ID2
import kotlin.time.Instant
import com.jassam.smsgate.work.WorkTestEnv.Companion.PAST
import com.jassam.smsgate.work.WorkTestEnv.Companion.claimJson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
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
class ProcessMessageWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var env: WorkTestEnv

    @Before
    fun setUp() {
        env = WorkTestEnv(tmp.root)
        env.pair()
    }

    @After
    fun tearDown() = env.close()

    private fun run(id: String? = ID, attempt: Int = 0): Result = runBlocking {
        env.worker<ProcessMessageWorker>(if (id == null) workDataOf() else ProcessMessageWorker.input(id), attempt).doWork()
    }

    @Test
    fun claimsJournalsAndSendsExactlyOnce() = runBlocking {
        env.respond(200, claimJson())
        assertEquals(Result.success(), run())

        val r = env.take()
        assertEquals("POST", r.method)
        assertEquals("/device/v1/messages/$ID/claim", r.url.encodedPath)
        assertEquals("Bearer dt_1", r.headers["Authorization"])
        assertEquals(1, env.transport.sends.size)
        assertEquals("+212600000001", env.transport.sends[0].to)
        assertEquals(listOf("Your code is 4242"), env.transport.sends[0].parts)
        assertEquals(listOf(11), env.transport.channelRequests) // sim_slot null → default SMS SIM
        val row = env.dao.get(ID)!!
        assertEquals(JournalState.SENDING, row.state)
        assertEquals(1, row.parts)
    }

    @Test
    fun secondPushForTheSameIdNeverClaimsOrSendsAgain() = runBlocking {
        env.respond(200, claimJson())
        assertEquals(Result.success(), run())
        assertEquals(Result.success(), run())
        assertEquals(Result.success(), run())
        assertEquals(1, env.server.requestCount)
        assertEquals(1, env.transport.sends.size)
    }

    @Test
    fun concurrentWorkersBothGetting200SendOnce() = runBlocking {
        // A misbehaving server (or two workers racing past the journal pre-check):
        // the journal insert is the last guard before SmsManager.
        val bothArrived = CountDownLatch(2)
        env.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                bothArrived.countDown()
                bothArrived.await(5, TimeUnit.SECONDS)
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(claimJson()).build()
            }
        }
        val results = (1..2).map {
            async(Dispatchers.IO) { env.worker<ProcessMessageWorker>(ProcessMessageWorker.input(ID)).doWork() }
        }.awaitAll()
        assertEquals(listOf(Result.success(), Result.success()), results)
        assertEquals(2, env.server.requestCount)
        assertEquals(1, env.transport.sends.size)
    }

    @Test
    fun aRowInsertedAfterThePreCheckStopsTheSend() = runBlocking {
        // The insert guard on its own (the pre-check can't see a row a racing job
        // inserted after it ran): insertClaimed returns false, so nothing is sent even
        // though the existing row is CLAIMED and markSending would let a send through.
        env.dao.insertClaimed(claimedEntry(ID, now = env.wall).copy(expiresAt = env.wall + 600_000))
        val claimed = ClaimedMessage(
            id = ID, to = "+212600000001", body = "hi", simSlot = null,
            expiresAt = Instant.fromEpochMilliseconds(env.wall + 600_000), serverTime = Instant.fromEpochMilliseconds(env.wall),
        )
        val worker = env.worker<ProcessMessageWorker>(ProcessMessageWorker.input(ID))
        assertEquals(Result.success(), worker.journalAndSend(claimed))
        assertTrue(env.transport.sends.isEmpty())
        assertEquals(JournalState.CLAIMED, env.dao.get(ID)!!.state)
    }

    @Test
    fun aProxyErrorWithoutTheBackendEnvelopeRetriesTheClaim() = runBlocking {
        env.respond(404, "<html>not found</html>")
        assertEquals(Result.retry(), run())
        env.respondError(404, "route_not_found") // enveloped, but not the message's 404
        assertEquals(Result.retry(), run())
        assertTrue(env.transport.sends.isEmpty())
    }

    @Test
    fun aBare401FromAProxyRetriesInsteadOfRevoking() = runBlocking {
        env.respond(401, "")
        assertEquals(Result.retry(), run())
        assertEquals("dt_1", env.store.deviceToken())
        assertEquals(0, env.queue.cancelled)
    }

    @Test
    fun revocationAbandonsClaimedRowsSoARePairedPhoneNeverSendsThem() = runBlocking {
        val stale = env.wall - CrashRecovery.STALE_AFTER_MS - 1
        env.dao.insertClaimed(claimedEntry(ID2, now = stale).copy(expiresAt = env.wall + 3_600_000))
        env.respondError(401, "unauthorized")
        assertEquals(Result.failure(), run())
        val row = env.dao.get(ID2)!!
        assertEquals(JournalState.FAILED, row.state)
        assertEquals(ErrorCodes.DEVICE_REVOKED, row.errorCode)
        assertEquals(setOf(Status.FAILED), row.acked) // nothing left to report

        // Re-paired as a new device: its sync neither resumes nor reports the old row.
        env.pair(token = "dt_2", deviceId = "dev-2")
        env.respond(200, """{"server_time":"${WorkTestEnv.SERVER_TIME}"}""")
        env.respond(200, """{"server_time":"${WorkTestEnv.SERVER_TIME}","messages":[]}""")
        assertEquals(Result.success(), env.worker<SyncWorker>(androidx.work.Data.EMPTY).doWork())
        assertTrue(env.transport.sends.isEmpty())
        assertTrue(env.queue.reports.isEmpty())
    }

    @Test
    fun existingJournalRowStopsBeforeClaiming() = runBlocking {
        env.dao.insertClaimed(claimedEntry(ID))
        assertEquals(Result.success(), run())
        assertEquals(0, env.server.requestCount)
        assertTrue(env.transport.sends.isEmpty())
    }

    @Test
    fun alreadyClaimedExpiredOrNotFoundSendNothing() = runBlocking {
        for ((status, code) in listOf(409 to "already_claimed", 410 to "expired", 404 to "message_not_found")) {
            env.respondError(status, code)
            assertEquals("HTTP $status", Result.success(), run())
        }
        assertEquals(3, env.server.requestCount)
        assertTrue(env.transport.sends.isEmpty())
        assertNull(env.dao.get(ID))
        assertTrue(env.queue.reports.isEmpty())
    }

    @Test
    fun claimNetworkErrorRetriesThenSucceeds() = runBlocking {
        env.dropConnection()
        assertEquals(Result.retry(), run(attempt = 0))
        assertTrue(env.transport.sends.isEmpty())
        assertNull(env.dao.get(ID))

        env.respond(200, claimJson())
        assertEquals(Result.success(), run(attempt = 1))
        assertEquals(1, env.transport.sends.size)
    }

    @Test
    fun claimRetriesAreBounded() = runBlocking {
        env.dropConnection()
        assertEquals(Result.failure(), run(attempt = ProcessMessageWorker.MAX_CLAIM_ATTEMPTS - 1))
        assertTrue(env.transport.sends.isEmpty())
    }

    @Test
    fun serverErrorsAndRateLimitRetryTheClaim() = runBlocking {
        env.respondError(503, "busy")
        assertEquals(Result.retry(), run())
        env.respondError(429, "rate_limited")
        assertEquals(Result.retry(), run())
        assertTrue(env.transport.sends.isEmpty())
    }

    @Test
    fun unparseable200IsNeverRetriedNorSent() = runBlocking {
        env.respond(200, """{"id":"$ID"}""")
        assertEquals(Result.failure(), run())
        assertTrue(env.transport.sends.isEmpty())
        assertNull(env.dao.get(ID))
    }

    @Test
    fun expiredOnDeviceIsNotSentAndReportedFailed() = runBlocking {
        env.respond(200, claimJson(expiresAt = PAST))
        assertEquals(Result.success(), run())
        assertTrue(env.transport.sends.isEmpty())
        assertEquals(JournalState.SKIPPED_EXPIRED, env.dao.get(ID)!!.state)
        assertEquals(1, env.queue.reports.size)
        val report = env.queue.reports[0]
        assertEquals(ID, report.id)
        assertEquals(Status.FAILED, report.status)
        assertEquals(ErrorCodes.EXPIRED_ON_DEVICE, report.errorCode)
    }

    @Test
    fun expiryUsesTheServerClockNotThePhoneClock() = runBlocking {
        // The phone's wall clock is a day ahead; the claim's server_time says there is time left.
        env.wall = kotlin.time.Instant.parse(FUTURE).toEpochMilliseconds() + 86_400_000
        env.respond(200, claimJson())
        assertEquals(Result.success(), run())
        assertEquals(1, env.transport.sends.size)
    }

    @Test
    fun unauthorizedClaimWipesCredentialsAndCancelsWork() = runBlocking {
        env.respondError(401, "unauthorized")
        assertEquals(Result.failure(), run())
        assertNull(env.store.load())
        assertEquals(1, env.queue.cancelled)
        assertTrue(env.transport.sends.isEmpty())
    }

    @Test
    fun noCredentialsCancelsWorkWithoutCalling() = runBlocking {
        env.store.clear()
        assertEquals(Result.failure(), run())
        assertEquals(0, env.server.requestCount)
        assertEquals(1, env.queue.cancelled)
    }

    @Test
    fun malformedInputFailsWithoutCalling() = runBlocking {
        assertEquals(Result.failure(), run(id = null))
        assertEquals(Result.failure(), env.worker<ProcessMessageWorker>(androidx.work.workDataOf(ProcessMessageWorker.KEY_ID to "../x")).doWork())
        assertEquals(0, env.server.requestCount)
    }

    @Test
    fun sendFailureIsJournaledAndReportedOnce() = runBlocking {
        env.transport.throwOnSend = SecurityException("denied")
        env.respond(200, claimJson())
        assertEquals(Result.success(), run())
        assertEquals(JournalState.FAILED, env.dao.get(ID)!!.state)
        assertEquals(listOf(PendingReport(ID, Status.FAILED, ErrorCodes.PERMISSION_DENIED, env.dao.get(ID)!!.errorMessage)), env.queue.reports)
        // A retry of the same message (new push) does not claim or send again.
        assertEquals(Result.success(), run())
        assertEquals(1, env.server.requestCount)
    }
}
