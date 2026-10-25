package com.jassam.smsgate.ui.pairing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jassam.smsgate.api.DeviceApi
import com.jassam.smsgate.api.DeviceHttp
import com.jassam.smsgate.api.ServerUrl
import com.jassam.smsgate.api.Sim
import com.jassam.smsgate.auth.Credentials
import com.jassam.smsgate.auth.CredentialStore
import com.jassam.smsgate.auth.FakeTokenCipher
import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Registration through a real DeviceApi (MockWebServer) and CredentialStore (fake cipher). */
class PairerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var server: MockWebServer
    private lateinit var store: CredentialStore
    private val cipher = FakeTokenCipher()
    private val client = DeviceHttp.client("test")
    private var hookCalls = 0
    private var fcmCalls = 0

    // MockWebServer listens on loopback; debug builds allow plain HTTP there.
    private val base get() = "http://127.0.0.1:${server.port}"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val file = File(tmp.root, "credentials.preferences_pb")
        store = CredentialStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }), cipher)
    }

    @After
    fun tearDown() {
        server.close()
        scope.cancel()
    }

    private fun pairer(
        sims: List<Sim> = listOf(Sim(0, 3, "Orange"), Sim(1, 7, "inwi", "+212600000000")),
        fcm: String? = "fcm-token-1",
        hook: PostPairingHook = PostPairingHook { hookCalls++ },
        hookTimeoutMs: Long = 10_000,
    ) = Pairer(
        normalizeServerUrl = { ServerUrl.normalize(it, allowLocalHttp = true) },
        apiFactory = { url, token -> DeviceApi(url, token, client) },
        credentials = store,
        sims = { sims },
        fcmToken = { fcmCalls++; fcm },
        appVersion = "1.2.3",
        postPairing = hook,
        postPairingTimeoutMs = hookTimeoutMs,
    )

    private fun enqueue(code: Int, body: String, vararg headers: Pair<String, String>) {
        val b = MockResponse.Builder().code(code).body(body).setHeader("Content-Type", "application/json")
        headers.forEach { (k, v) -> b.setHeader(k, v) }
        server.enqueue(b.build())
    }

    private fun enqueueError(status: Int, code: String, vararg headers: Pair<String, String>) =
        enqueue(status, """{"error":{"code":"$code","message":"m"}}""", *headers)

    private fun enqueueCreated() =
        enqueue(201, """{"device_id":"0192f0c4-0000-7000-8000-000000000001","device_token":"dt_secret"}""")

    private fun body(): JsonObject = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    private fun code(server: String = "$base/") = PairingMethod.Code(server, "abcd-2345")

    @Test
    fun pairsWithCodeStoresCredentialsAndRunsTheHook() = runBlocking {
        enqueueCreated()
        val result = pairer().pair(code(), "  Pixel 8  ", withPush = true)
        assertEquals(PairingResult.Success, result)

        val b = body()
        assertEquals("ABCD2345", b["pairing_code"]!!.jsonPrimitive.content)
        assertFalse("email" in b)
        assertFalse("password" in b)
        assertEquals("Pixel 8", b["name"]!!.jsonPrimitive.content)
        assertEquals("fcm-token-1", b["fcm_token"]!!.jsonPrimitive.content)
        assertEquals("1.2.3", b["app_version"]!!.jsonPrimitive.content)
        val sims = b["sims"]!!.jsonArray
        assertEquals(2, sims.size)
        assertEquals("7", sims[1].jsonObject["subscription_id"]!!.jsonPrimitive.content)
        assertEquals("+212600000000", sims[1].jsonObject["number"]!!.jsonPrimitive.content)
        assertFalse("number" in sims[0].jsonObject)

        assertEquals(Credentials(base, "0192f0c4-0000-7000-8000-000000000001", "dt_secret"), store.load())
        assertEquals(1, hookCalls)
    }

    @Test
    fun pairsWithLogin() = runBlocking {
        enqueueCreated()
        val result = pairer().pair(PairingMethod.Login(base, " dev@example.com ", "hunter2!"), "Pixel", withPush = true)
        assertEquals(PairingResult.Success, result)
        val b = body()
        assertEquals("dev@example.com", b["email"]!!.jsonPrimitive.content)
        assertEquals("hunter2!", b["password"]!!.jsonPrimitive.content)
        assertFalse("pairing_code" in b)
        assertTrue(store.load() != null)
    }

    @Test
    fun withoutPushNoFcmTokenIsRequestedOrSent() = runBlocking {
        enqueueCreated()
        assertEquals(PairingResult.Success, pairer().pair(code(), "Pixel", withPush = false))
        assertFalse("fcm_token" in body())
        assertEquals(0, fcmCalls)
    }

    @Test
    fun missingFcmTokenIsOmitted() = runBlocking {
        enqueueCreated()
        assertEquals(PairingResult.Success, pairer(fcm = null).pair(code(), "Pixel", withPush = true))
        assertFalse("fcm_token" in body())
    }

    @Test
    fun emptySimListIsSentAsEmptyArray() = runBlocking {
        enqueueCreated()
        pairer(sims = emptyList()).pair(code(), "Pixel", withPush = true)
        assertEquals(0, body()["sims"]!!.jsonArray.size)
    }

    @Test
    fun invalidInputNeverReachesTheServer() = runBlocking {
        val p = pairer()
        assertEquals(PairingResult.Failure(PairingError.InvalidServer), p.pair(code("http://example.com"), "Pixel", true))
        assertEquals(PairingResult.Failure(PairingError.InvalidServer), p.pair(code("  "), "Pixel", true))
        assertEquals(PairingResult.Failure(PairingError.InvalidName), p.pair(code(), "   ", true))
        assertEquals(PairingResult.Failure(PairingError.InvalidName), p.pair(code(), "x".repeat(65), true))
        assertEquals(PairingResult.Failure(PairingError.InvalidCode), p.pair(PairingMethod.Code(base, "ABC"), "Pixel", true))
        assertEquals(0, server.requestCount)
    }

    private fun assertFailure(expected: PairingError, result: PairingResult) {
        assertEquals(PairingResult.Failure(expected), result)
        runBlocking { assertNull("nothing stored on failure", store.load()) }
        assertEquals("hook not run on failure", 0, hookCalls)
    }

    @Test
    fun invalidOrExpiredCode() = runBlocking {
        enqueueError(400, "invalid_pairing_code")
        assertFailure(PairingError.InvalidCode, pairer().pair(code(), "Pixel", true))
    }

    @Test
    fun invalidCredentialsIsNotARevocation() = runBlocking {
        enqueueError(401, "invalid_credentials")
        assertFailure(PairingError.InvalidCredentials, pairer().pair(PairingMethod.Login(base, "a@b.c", "x"), "Pixel", true))
        assertEquals(0, cipher.resets)
    }

    @Test
    fun emailNotVerified() = runBlocking {
        enqueueError(403, "email_not_verified")
        assertFailure(PairingError.EmailNotVerified, pairer().pair(PairingMethod.Login(base, "a@b.c", "x"), "Pixel", true))
    }

    @Test
    fun rateLimitedCarriesRetryAfter() = runBlocking {
        enqueueError(429, "rate_limited", "Retry-After" to "30")
        assertFailure(PairingError.RateLimited(30), pairer().pair(code(), "Pixel", true))
    }

    @Test
    fun rateLimitedWithoutRetryAfter() = runBlocking {
        enqueueError(429, "rate_limited")
        assertFailure(PairingError.RateLimited(null), pairer().pair(code(), "Pixel", true))
    }

    @Test
    fun networkFailure() = runBlocking {
        val url = base
        server.close()
        assertFailure(PairingError.Network, pairer().pair(PairingMethod.Code(url, "ABCD2345"), "Pixel", true))
    }

    @Test
    fun serverUnavailable() = runBlocking {
        enqueueError(503, "busy")
        assertFailure(PairingError.ServerUnavailable, pairer().pair(code(), "Pixel", true))
        server.enqueue(MockResponse.Builder().code(502).body("<html>bad gateway</html>").build())
        assertFailure(PairingError.ServerUnavailable, pairer().pair(code(), "Pixel", true))
    }

    @Test
    fun notAnSmsGateServer() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("<html>not found</html>").build())
        assertFailure(PairingError.NotSmsGateServer, pairer().pair(code(), "Pixel", true))
        enqueue(201, "<html>hello</html>")
        assertFailure(PairingError.NotSmsGateServer, pairer().pair(code(), "Pixel", true))
    }

    @Test
    fun serverRejectedName() = runBlocking {
        enqueueError(422, "invalid_name")
        assertFailure(PairingError.InvalidName, pairer().pair(code(), "Pixel", true))
    }

    @Test
    fun unknownErrorKeepsItsCode() = runBlocking {
        enqueueError(422, "invalid_sims")
        assertFailure(PairingError.Unknown("invalid_sims"), pairer().pair(code(), "Pixel", true))
    }

    private fun assertOrphanUnregistered() {
        server.takeRequest() // register
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/device/v1/unregister", r.url.encodedPath)
        assertEquals("Bearer dt_secret", r.headers["Authorization"])
    }

    @Test
    fun storageFailureIsReportedNothingStoredAndTheOrphanUnregistered() = runBlocking {
        enqueueCreated()
        server.enqueue(MockResponse.Builder().code(204).build())
        cipher.onEncrypt = { throw GeneralSecurityException("keystore broken") }
        assertFailure(PairingError.StorageFailed, pairer().pair(code(), "Pixel", true))
        assertOrphanUnregistered()
    }

    @Test
    fun keystoreRuntimeExceptionsAreStorageFailures() = runBlocking {
        // KeystoreTokenCipher's retry can rethrow provider runtime exceptions.
        for (e in listOf(ProviderException("keystore busy"), IllegalStateException("key invalidated"))) {
            enqueueCreated()
            server.enqueue(MockResponse.Builder().code(204).build())
            cipher.onEncrypt = { throw e }
            assertFailure(PairingError.StorageFailed, pairer().pair(code(), "Pixel", true))
            assertOrphanUnregistered()
        }
    }

    @Test
    fun failedOrphanCleanupStillReportsStorageFailure() = runBlocking {
        enqueueCreated()
        enqueueError(500, "internal")
        cipher.onEncrypt = { throw GeneralSecurityException("keystore broken") }
        assertFailure(PairingError.StorageFailed, pairer().pair(code(), "Pixel", true))
        assertOrphanUnregistered()
    }

    @Test
    fun tokenIsStoredEvenIfTheCallerIsCancelledAfterThe201() = runBlocking {
        enqueueCreated()
        lateinit var job: Job
        // Fires inside save(), i.e. after the 201 arrived: the caller goes away mid-store.
        cipher.onEncrypt = { job.cancel() }
        job = scope.launch(start = CoroutineStart.LAZY) { pairer().pair(code(), "Pixel", true) }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(Credentials(base, "0192f0c4-0000-7000-8000-000000000001", "dt_secret"), store.load())
        assertEquals(1, hookCalls)
    }

    @Test
    fun failingHookDoesNotUndoPairing() = runBlocking {
        enqueueCreated()
        val result = pairer(hook = { error("scheduler down") }).pair(code(), "Pixel", true)
        assertEquals(PairingResult.Success, result)
        assertTrue(store.load() != null)
    }

    @Test(timeout = 10_000)
    fun aHangingHookIsBoundedAndPairingStillSucceeds() = runBlocking {
        enqueueCreated()
        val started = System.nanoTime()
        val result = pairer(hook = { kotlinx.coroutines.awaitCancellation() }, hookTimeoutMs = 200).pair(code(), "Pixel", true)
        assertEquals(PairingResult.Success, result)
        assertTrue(store.load() != null)
        assertTrue("hook must be bounded", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun methodToStringNeverShowsSecrets() {
        val s = PairingMethod.Login(base, "a@b.c", "hunter2!").toString()
        assertFalse(s, "hunter2!" in s)
    }
}
