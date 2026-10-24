package com.jassam.smsgate.api

import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceApiTest {
    private lateinit var server: MockWebServer
    private var token: String? = "dt_secret"
    private var mono = 1_000L
    private val clock = ServerClock(monotonicMillis = { mono }, wallMillis = { 0L })
    private val client = DeviceHttp.client(DeviceHttp.userAgent("1.2.3", "14", "Pixel 8"))
    private lateinit var api: DeviceApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = DeviceApi(server.url("/").toString().trimEnd('/'), { token }, client, clock)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun enqueue(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        val b = MockResponse.Builder().code(code).body(body)
        if (body.isNotEmpty()) b.setHeader("Content-Type", "application/json; charset=utf-8")
        headers.forEach { (k, v) -> b.setHeader(k, v) }
        server.enqueue(b.build())
    }

    private fun take(): RecordedRequest = server.takeRequest()
    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun RecordedRequest.assertCommon(method: String, path: String, auth: Boolean = true) {
        assertEquals(method, this.method)
        assertEquals(path, url.encodedPath)
        assertEquals(if (auth) "Bearer dt_secret" else null, headers["Authorization"])
        assertEquals("SMSGate-Android/1.2.3 (Android 14; Pixel 8)", headers["User-Agent"])
        if (method != "GET") assertEquals("application/json", headers["Content-Type"])
    }

    private fun <T> ApiResult<T>.ok(): T {
        assertTrue("expected Ok, got $this", this is ApiResult.Ok)
        return (this as ApiResult.Ok).value
    }

    private fun ApiResult<*>.err(): ApiResult.HttpError {
        assertTrue("expected HttpError, got $this", this is ApiResult.HttpError)
        return this as ApiResult.HttpError
    }

    @Test
    fun registerWithCode() = runBlocking {
        enqueue(201, """{"device_id":"0192f0c4-0000-7000-8000-000000000001","device_token":"dt_abc"}""")
        val req = RegisterRequest.withCode(
            "ABCD-1234", "Pixel", listOf(Sim(0, 5, "Orange"), Sim(1, 7, "inwi", "+212600000000")), "fcm1", "1.2.3",
        )
        val res = api.register(req).ok()
        assertEquals("0192f0c4-0000-7000-8000-000000000001", res.deviceId)
        assertEquals("dt_abc", res.deviceToken)
        val r = take()
        r.assertCommon("POST", "/device/v1/register", auth = false)
        assertEquals(
            Json.parseToJsonElement(
                """{"pairing_code":"ABCD-1234","name":"Pixel","sims":[{"slot":0,"subscription_id":5,"carrier":"Orange"},""" +
                    """{"slot":1,"subscription_id":7,"carrier":"inwi","number":"+212600000000"}],"fcm_token":"fcm1","app_version":"1.2.3"}""",
            ),
            r.json(),
        )
        assertFalse(res.toString().contains("dt_abc"))
    }

    @Test
    fun registerWithLoginOmitsCodeAndNullToken() = runBlocking {
        enqueue(201, """{"device_id":"d","device_token":"dt_x"}""")
        api.register(RegisterRequest.withLogin("a@b.c", "pw", "Phone", emptyList(), null, null)).ok()
        assertEquals(
            Json.parseToJsonElement("""{"email":"a@b.c","password":"pw","name":"Phone","sims":[]}"""),
            take().json(),
        )
    }

    @Test
    fun registerErrorsKeepBackendCodes() = runBlocking {
        enqueue(400, """{"error":{"code":"invalid_pairing_code","message":"pairing code is invalid or expired"}}""")
        enqueue(401, """{"error":{"code":"invalid_credentials","message":"email or password is incorrect"}}""")
        enqueue(403, """{"error":{"code":"email_not_verified","message":"verify your email first"}}""")
        enqueue(503, """{"error":{"code":"busy","message":"server is busy, retry shortly"}}""")
        val req = RegisterRequest.withCode("X", "n", emptyList(), null, null)
        assertEquals(ApiResult.HttpError(400, "invalid_pairing_code", "pairing code is invalid or expired"), api.register(req))
        val e401 = api.register(req).err()
        assertEquals("invalid_credentials", e401.code)
        assertTrue(e401.isUnauthorized)
        assertEquals("email_not_verified", api.register(req).err().code)
        assertEquals("busy", api.register(req).err().code)
    }

    @Test
    fun meParsesAndUpdatesClock() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"device_id":"d1","name":"Pixel","account_email":"a@b.c","server_time":"2026-10-24T10:00:00.123456Z","extra":1}""")
                .build(),
        )
        val me = api.me().ok()
        assertEquals(DeviceMe("d1", "Pixel", "a@b.c", Instant.parse("2026-10-24T10:00:00.123456Z")), me)
        take().assertCommon("GET", "/device/v1/me")
        assertTrue(clock.isSynced)
    }

    @Test
    fun heartbeat() = runBlocking {
        enqueue(200, """{"server_time":"2026-10-24T10:00:00Z"}""")
        val res = api.heartbeat(Heartbeat(battery = 80, charging = true, signal = 3, sims = null, appVersion = "1.2.3")).ok()
        assertEquals(Instant.parse("2026-10-24T10:00:00Z"), res.serverTime)
        val r = take()
        r.assertCommon("POST", "/device/v1/heartbeat")
        // sims omitted (null = unchanged on the server).
        assertEquals(Json.parseToJsonElement("""{"battery":80,"charging":true,"signal":3,"app_version":"1.2.3"}"""), r.json())
    }

    @Test
    fun heartbeatWithEmptySimsSendsEmptyList() = runBlocking {
        enqueue(200, """{"server_time":"2026-10-24T10:00:00Z"}""")
        api.heartbeat(Heartbeat(sims = emptyList())).ok()
        assertEquals(Json.parseToJsonElement("""{"sims":[]}"""), take().json())
    }

    @Test
    fun setFcmToken() = runBlocking {
        enqueue(204)
        assertEquals(ApiResult.Ok(Unit), api.setFcmToken("tok"))
        val r = take()
        r.assertCommon("PUT", "/device/v1/fcm-token")
        assertEquals("tok", r.json()["fcm_token"]!!.jsonPrimitive.content)
    }

    @Test
    fun pending() = runBlocking {
        enqueue(
            200,
            """{"server_time":"2026-10-24T10:00:00Z","messages":[{"id":"m1","expires_at":"2026-10-24T10:05:00.5Z"},""" +
                """{"id":"m2","expires_at":"2026-10-24T12:00:00+01:00"}]}""",
        )
        val p = api.pending().ok()
        assertEquals(Instant.parse("2026-10-24T10:00:00Z"), p.serverTime)
        assertEquals(listOf("m1", "m2"), p.messages.map { it.id })
        assertEquals(Instant.parse("2026-10-24T10:05:00.500Z"), p.messages[0].expiresAt)
        assertEquals(Instant.parse("2026-10-24T11:00:00Z"), p.messages[1].expiresAt)
        take().assertCommon("GET", "/device/v1/messages/pending")
    }

    @Test
    fun claimSendsBodylessJsonPost() = runBlocking {
        enqueue(
            200,
            """{"id":"m1","to":"+212600000000","body":"Your code is 1234","sim_slot":1,""" +
                """"expires_at":"2026-10-24T10:05:00Z","server_time":"2026-10-24T10:00:00Z"}""",
        )
        val c = api.claim("m1").ok()
        assertEquals(ClaimedMessage("m1", "+212600000000", "Your code is 1234", 1, Instant.parse("2026-10-24T10:05:00Z"), Instant.parse("2026-10-24T10:00:00Z")), c)
        assertFalse(c.toString().contains("1234"))
        val r = take()
        r.assertCommon("POST", "/device/v1/messages/m1/claim")
        assertEquals(0L, r.bodySize)
    }

    @Test
    fun claimNullSimSlot() = runBlocking {
        enqueue(200, """{"id":"m1","to":"+1","body":"b","sim_slot":null,"expires_at":"2026-10-24T10:05:00Z","server_time":"2026-10-24T10:00:00Z"}""")
        assertNull(api.claim("m1").ok().simSlot)
    }

    @Test
    fun claimConflictAndGone() = runBlocking {
        enqueue(409, """{"error":{"code":"already_claimed","message":"message was already claimed"}}""")
        enqueue(410, """{"error":{"code":"expired","message":"message expired before it could be claimed"}}""")
        assertEquals(ApiResult.HttpError(409, "already_claimed", "message was already claimed"), api.claim("m1"))
        assertEquals("expired", api.claim("m1").err().code)
    }

    @Test
    fun messageIdIsPathEncoded() = runBlocking {
        enqueue(404, """{"error":{"code":"message_not_found","message":"not found"}}""")
        api.claim("a/b")
        assertEquals("/device/v1/messages/a%2Fb/claim", take().url.encodedPath)
    }

    @Test
    fun reportStatusSent() = runBlocking {
        enqueue(
            200,
            """{"id":"m1","device_id":"d1","sim_slot":null,"to":"+1","status":"sent","client_ref":null,"error_code":null,""" +
                """"error_message":null,"created_at":"2026-10-24T09:59:00Z","expires_at":"2026-10-24T10:05:00Z",""" +
                """"dispatched_at":"2026-10-24T10:00:00Z","sent_at":"2026-10-24T10:00:01Z","delivered_at":null,"failed_at":null}""",
        )
        val m = api.reportStatus("m1", Status.SENT).ok()
        assertEquals("sent", m.status)
        assertEquals(Instant.parse("2026-10-24T10:00:01Z"), m.sentAt)
        val r = take()
        r.assertCommon("POST", "/device/v1/messages/m1/status")
        assertEquals(Json.parseToJsonElement("""{"status":"sent"}"""), r.json())
    }

    @Test
    fun reportStatusFailedAndInvalidTransition() = runBlocking {
        enqueue(409, """{"error":{"code":"invalid_transition","message":"cannot move from delivered to failed"}}""")
        val e = api.reportStatus("m1", Status.FAILED, "generic_failure", "radio said no").err()
        assertEquals(409, e.status)
        assertEquals("invalid_transition", e.code)
        assertEquals(
            Json.parseToJsonElement("""{"status":"failed","error_code":"generic_failure","error_message":"radio said no"}"""),
            take().json(),
        )
    }

    @Test
    fun unregister() = runBlocking {
        enqueue(204)
        enqueue(401, """{"error":{"code":"unauthorized","message":"authentication required"}}""")
        assertEquals(ApiResult.Ok(Unit), api.unregister())
        val r = take()
        r.assertCommon("POST", "/device/v1/unregister")
        assertEquals(0L, r.bodySize)
        // Already revoked: surfaced as 401, callers treat it as done.
        assertTrue(api.unregister().isUnauthorized)
    }

    @Test
    fun unauthorizedIsFlagged() = runBlocking {
        enqueue(401, """{"error":{"code":"unauthorized","message":"authentication required"}}""")
        val res = api.pending()
        assertTrue(res.isUnauthorized)
        assertEquals(ApiResult.HttpError(401, "unauthorized", "authentication required"), res)
    }

    @Test
    fun missingTokenIsSyntheticUnauthorizedWithoutRequest() = runBlocking {
        token = null
        val res = api.me().err()
        assertEquals(401, res.status)
        assertEquals(ApiResult.CODE_NO_CREDENTIALS, res.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun nonJsonErrorBecomesHttpStatusCode() = runBlocking {
        server.enqueue(MockResponse.Builder().code(502).setHeader("Content-Type", "text/html").body("<html>Bad Gateway</html>").build())
        assertEquals(ApiResult.HttpError(502, "http_502", "HTTP 502"), api.pending())
    }

    @Test
    fun rateLimitedCarriesRetryAfter() = runBlocking {
        enqueue(429, """{"error":{"code":"rate_limited","message":"too many requests, retry later"}}""", "Retry-After" to "17")
        val e = api.heartbeat(Heartbeat()).err()
        assertEquals("rate_limited", e.code)
        assertEquals(17L, e.retryAfterSeconds)
    }

    @Test
    fun invalidSuccessBodyIsInvalidResponse() = runBlocking {
        enqueue(200, """{"nope":true}""")
        assertEquals(ApiResult.CODE_INVALID_RESPONSE, api.me().err().code)
    }

    @Test
    fun networkFailureIsNetworkError() = runBlocking {
        // Unsafe methods are never retried silently: one dropped connection = NetworkError.
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        enqueue(409, """{"error":{"code":"already_claimed","message":"x"}}""")
        val res = api.claim("m1")
        assertTrue("got $res", res is ApiResult.NetworkError)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun connectionRefusedIsNetworkError() = runBlocking {
        val url = server.url("/").toString().trimEnd('/')
        server.close()
        val res = DeviceApi(url, { token }, client).claim("m1")
        assertTrue("got $res", res is ApiResult.NetworkError)
    }

    @Test
    fun basePathPrefixIsKept() = runBlocking {
        val prefixed = DeviceApi(server.url("/api/").toString(), { token }, client)
        enqueue(204)
        prefixed.setFcmToken("t")
        assertEquals("/api/device/v1/fcm-token", take().url.encodedPath)
    }

    @Test
    fun userAgentIsAscii() {
        assertEquals("SMSGate-Android/1.0 (Android 7.0; Mi 9 _)", DeviceHttp.userAgent("1.0", "7.0", "Mi 9 é"))
    }
}
