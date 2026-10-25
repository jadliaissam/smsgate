package com.jassam.smsgate.work

import androidx.work.Data
import androidx.work.ListenableWorker.Result
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
class FcmTokenWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var env: WorkTestEnv

    @Before
    fun setUp() {
        env = WorkTestEnv(tmp.root)
    }

    @After
    fun tearDown() = env.close()

    private fun run(token: String, attempt: Int = 0): Result = runBlocking {
        env.worker<FcmTokenWorker>(FcmTokenWorker.input(token), attempt).doWork()
    }

    @Test
    fun sendsTheTokenAndRemembersIt() = runBlocking {
        env.pair()
        env.respond(204)
        assertEquals(Result.success(), run("fcm-new"))
        val r = env.take()
        assertEquals("PUT", r.method)
        assertEquals("/device/v1/fcm-token", r.url.encodedPath)
        assertEquals("Bearer dt_1", r.headers["Authorization"])
        assertEquals(Json.parseToJsonElement("""{"fcm_token":"fcm-new"}"""), with(env) { r.json() })
        assertTrue(env.sentTokens.matches("dev-1", "fcm-new"))
    }

    @Test
    fun aTokenTheServerAlreadyHasIsNotSentAgain() = runBlocking {
        env.pair()
        env.sentTokens.remember("dev-1", "fcm-new")
        assertEquals(Result.success(), run("fcm-new"))
        assertEquals(0, env.server.requestCount)
    }

    @Test
    fun missingTokenFails() = runBlocking {
        env.pair()
        assertEquals(Result.failure(), env.worker<FcmTokenWorker>(Data.EMPTY).doWork())
        assertEquals(0, env.server.requestCount)
    }

    @Test
    fun unpairedDoesNothingAndDoesNotRevoke() = runBlocking {
        assertEquals(Result.success(), run("fcm-new"))
        assertEquals(0, env.server.requestCount)
        assertEquals(0, env.queue.cancelled)
    }

    @Test
    fun retriesOnNetworkAndServerErrorsBoundedly() = runBlocking {
        env.pair()
        env.dropConnection()
        assertEquals(Result.retry(), run("fcm-new"))
        env.respondError(503, "busy")
        assertEquals(Result.retry(), run("fcm-new", attempt = 1))
        env.respondError(503, "busy")
        assertEquals(Result.failure(), run("fcm-new", attempt = FcmTokenWorker.MAX_ATTEMPTS - 1))
        assertTrue(env.sentTokens.sent.isEmpty())
    }

    @Test
    fun invalidTokenIsDropped() = runBlocking {
        env.pair()
        env.respondError(422, "invalid_fcm_token")
        assertEquals(Result.failure(), run("x"))
    }

    @Test
    fun unauthorizedRevokes() = runBlocking {
        env.pair()
        env.respondError(401, "unauthorized")
        assertEquals(Result.failure(), run("fcm-new"))
        assertNull(env.store.load())
        assertEquals(1, env.queue.cancelled)
    }
}
