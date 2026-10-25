package com.jassam.smsgate.work

import androidx.work.ListenableWorker.Result
import androidx.work.workDataOf
import com.jassam.smsgate.api.Status
import com.jassam.smsgate.journal.JournalState
import com.jassam.smsgate.journal.claimedEntry
import com.jassam.smsgate.work.WorkTestEnv.Companion.ID
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
class ReportStatusWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var env: WorkTestEnv

    private val stateJson = """{"id":"$ID","status":"sent"}"""

    @Before
    fun setUp() {
        env = WorkTestEnv(tmp.root)
        env.pair()
    }

    @After
    fun tearDown() = env.close()

    /** A journal row that decided [status] (so it can be acked). */
    private fun decided(status: Status) = runBlocking {
        env.dao.insertClaimed(claimedEntry(ID))
        when (status) {
            Status.SENT -> {
                env.dao.markSending(ID, 2)
                env.dao.moveTo(ID, JournalState.SENDING, JournalState.SENT, 3)
            }
            Status.DELIVERED -> {
                env.dao.markSending(ID, 2)
                env.dao.moveTo(ID, JournalState.SENDING, JournalState.DELIVERED, 3)
            }
            Status.FAILED -> {
                env.dao.markSending(ID, 2)
                env.dao.moveTo(ID, JournalState.SENDING, JournalState.FAILED, 3, "radio_off", "SMS part 1 was not sent")
            }
        }
    }

    private fun run(status: Status, code: String? = null, message: String? = null, attempt: Int = 0): Result = runBlocking {
        env.worker<ReportStatusWorker>(ReportStatusWorker.input(ID, status, code, message), attempt).doWork()
    }

    private fun acked(): Set<Status> = runBlocking { env.dao.get(ID)!!.acked }

    @Test
    fun reportsSentAndAcksOn200() = runBlocking {
        decided(Status.SENT)
        env.respond(200, stateJson)
        assertEquals(Result.success(), run(Status.SENT))
        val r = env.take()
        assertEquals("POST", r.method)
        assertEquals("/device/v1/messages/$ID/status", r.url.encodedPath)
        assertEquals("Bearer dt_1", r.headers["Authorization"])
        assertEquals("application/json", r.headers["Content-Type"])
        assertEquals(Json.parseToJsonElement("""{"status":"sent"}"""), with(env) { r.json() })
        assertEquals(setOf(Status.SENT), acked())
    }

    @Test
    fun failedReportCarriesCodeAndMessage() = runBlocking {
        decided(Status.FAILED)
        env.respond(200, """{"id":"$ID","status":"failed"}""")
        assertEquals(Result.success(), run(Status.FAILED, "radio_off", "SMS part 1 was not sent"))
        assertEquals(
            Json.parseToJsonElement("""{"status":"failed","error_code":"radio_off","error_message":"SMS part 1 was not sent"}"""),
            with(env) { env.take().json() },
        )
        assertEquals(setOf(Status.FAILED), acked())
    }

    @Test
    fun retriesOnServerErrorThenSucceeds() = runBlocking {
        decided(Status.SENT)
        env.respondError(500, "internal")
        assertEquals(Result.retry(), run(Status.SENT))
        assertTrue(acked().isEmpty())
        env.respond(200, stateJson)
        assertEquals(Result.success(), run(Status.SENT, attempt = 1))
        assertEquals(setOf(Status.SENT), acked())
    }

    @Test
    fun retriesOnNetworkErrorAndRateLimit() = runBlocking {
        decided(Status.SENT)
        env.dropConnection()
        assertEquals(Result.retry(), run(Status.SENT))
        env.respondError(429, "rate_limited")
        assertEquals(Result.retry(), run(Status.SENT, attempt = 1))
        assertTrue(acked().isEmpty())
    }

    @Test
    fun invalidTransitionIsFinalAndAcked() = runBlocking {
        decided(Status.SENT)
        env.respondError(409, "invalid_transition")
        assertEquals(Result.success(), run(Status.SENT))
        assertEquals(setOf(Status.SENT), acked())
    }

    @Test
    fun validationErrorIsLoggedDroppedAndAcked() = runBlocking {
        decided(Status.FAILED)
        env.respondError(422, "invalid_error_code")
        assertEquals(Result.success(), run(Status.FAILED, "radio_off", "m"))
        assertEquals(setOf(Status.FAILED), acked())
        assertTrue(env.logs.any { "invalid_error_code" in it })
    }

    @Test
    fun unknownMessageIsDroppedAndAcked() = runBlocking {
        decided(Status.SENT)
        env.respondError(404, "message_not_found")
        assertEquals(Result.success(), run(Status.SENT))
        assertEquals(setOf(Status.SENT), acked())
    }

    @Test
    fun unauthorizedWipesMatchingCredentialsAndCancelsWork() = runBlocking {
        decided(Status.SENT)
        env.respondError(401, "unauthorized")
        assertEquals(Result.failure(), run(Status.SENT))
        assertNull(env.store.load())
        assertEquals(1, env.queue.cancelled)
        assertTrue(acked().isEmpty()) // the journal is kept
        assertEquals(JournalState.SENT, env.dao.get(ID)!!.state)
    }

    @Test
    fun errorFieldsAreOnlySentWithFailed() = runBlocking {
        decided(Status.SENT)
        env.respond(200, stateJson)
        assertEquals(Result.success(), run(Status.SENT, "stray", "stray"))
        assertEquals(Json.parseToJsonElement("""{"status":"sent"}"""), with(env) { env.take().json() })
    }

    @Test
    fun malformedInputFails() = runBlocking {
        assertEquals(Result.failure(), env.worker<ReportStatusWorker>(workDataOf(ReportStatusWorker.KEY_ID to ID, ReportStatusWorker.KEY_STATUS to "queued")).doWork())
        assertEquals(Result.failure(), env.worker<ReportStatusWorker>(workDataOf(ReportStatusWorker.KEY_STATUS to "sent")).doWork())
        assertEquals(0, env.server.requestCount)
    }
}
