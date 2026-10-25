package com.jassam.smsgate.realtime

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jassam.smsgate.api.Heartbeat
import com.jassam.smsgate.api.ServerClock
import com.jassam.smsgate.api.Sim
import com.jassam.smsgate.auth.CredentialStore
import com.jassam.smsgate.auth.Credentials
import com.jassam.smsgate.auth.FakeTokenCipher
import com.jassam.smsgate.work.RecordingWorkQueue
import com.jassam.smsgate.work.Revocation
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SocketClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val loop = FakeLoop()
    private val queue = RecordingWorkQueue()
    private val clock = ServerClock(monotonicMillis = { loop.now() }, wallMillis = { 0L })
    private val revoked = CopyOnWriteArrayList<String>()
    private val states = CopyOnWriteArrayList<ConnectionState>()
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var onRevoked: (String) -> Unit = { revoked += it }
    private var heartbeat = Heartbeat(battery = 81, charging = true, signal = null, sims = listOf(Sim(0, 11, "Orange")), appVersion = "1.2.3")
    private val clients = mutableListOf<SocketClient>()

    private lateinit var credentials: Credentials

    @Before
    fun setUp() {
        server.start()
        credentials = Credentials("http://127.0.0.1:${server.port}", "dev-1", TOKEN)
    }

    @After
    fun tearDown() {
        clients.forEach { it.stop() }
        server.close()
    }

    private fun client(creds: Credentials = credentials, random: Random = Random(7)) = SocketClient(
        credentials = creds,
        httpClient = http,
        loop = loop,
        backoff = ReconnectBackoff(random),
        queue = queue,
        clock = clock,
        heartbeat = { heartbeat },
        onRevoked = { onRevoked(it) },
        onState = { states += it },
    ).also { clients += it }

    /** The server side of one upgraded socket. */
    private class Peer : WebSocketListener() {
        val messages = LinkedBlockingQueue<String>()
        val closes = LinkedBlockingQueue<Int>()
        @Volatile var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages += text
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closes += code
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.countDown()
        }

        val closed = CountDownLatch(1)

        fun next(): String = messages.poll(5, TimeUnit.SECONDS) ?: error("no frame from the client")

        fun send(text: String) {
            check(socket!!.send(text))
        }
    }

    private fun upgrade(): Peer = Peer().also { server.enqueue(MockResponse.Builder().webSocketUpgrade(it).build()) }

    private fun refuse(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        val b = MockResponse.Builder().code(code).body(body)
        if (body.startsWith("{")) b.setHeader("Content-Type", "application/json")
        headers.forEach { (k, v) -> b.setHeader(k, v) }
        server.enqueue(b.build())
    }

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            loop.idle()
            if (cond()) return
            if (System.nanoTime() > end) fail("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    /** Starts [c] against a peer and waits until the heartbeat frame arrived (socket open). */
    private fun connect(c: SocketClient = client()): Peer {
        val peer = upgrade()
        c.start()
        assertTrue(peer.next().contains("\"heartbeat\""))
        await("connected") { states.lastOrNull() == ConnectionState.CONNECTED }
        return peer
    }

    private fun retryTimer(): FakeLoop.Timer {
        await("a reconnect timer") { loop.timers().isNotEmpty() && states.lastOrNull() == ConnectionState.WAITING }
        return loop.timers().single()
    }

    @Test
    fun opensWithTheBearerTokenSendsAHeartbeatAndSyncs() {
        val peer = upgrade()
        client().start()
        val upgradeRequest = server.takeRequest()
        assertEquals("/device/v1/ws", upgradeRequest.url.encodedPath)
        assertEquals("Bearer $TOKEN", upgradeRequest.headers["Authorization"])
        assertEquals(
            Json.parseToJsonElement(
                """{"type":"heartbeat","battery":81,"charging":true,"sims":[{"slot":0,"subscription_id":11,"carrier":"Orange"}],"app_version":"1.2.3"}""",
            ),
            Json.parseToJsonElement(peer.next()),
        )
        await("sync") { queue.syncs == 1 }
        assertEquals(listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED), states.toList())
        // Only the keepalive timer is armed, as a wake-up alarm.
        val ping = loop.timers().single()
        assertEquals(SocketClient.KEEPALIVE_DELAY_MS, ping.delayMs)
        assertTrue(ping.wakeup)
    }

    @Test
    fun heartbeatSourceFailureStillSendsAnEmptyHeartbeat() {
        val c = SocketClient(credentials, http, loop, ReconnectBackoff(Random(1)), queue, clock, { error("binder died") }, { revoked += it })
        clients += c
        val peer = upgrade()
        c.start()
        assertEquals("""{"type":"heartbeat"}""", peer.next())
    }

    @Test
    fun newFrameEnqueuesExpeditedProcessingOnceWithTheNormalisedId() {
        val peer = connect()
        peer.send("""{"type":"new","message_id":"${ID.uppercase()}"}""")
        await("process") { queue.processed.isNotEmpty() }
        loop.idle()
        assertEquals(listOf(RecordingWorkQueue.Process(ID, expedited = true)), queue.processed.toList())
        assertEquals(1, queue.syncs) // a wake doesn't trigger another sync
    }

    @Test
    fun malformedAndUnknownFramesAreIgnoredAndTheSocketStaysOpen() {
        val peer = connect()
        listOf(
            "not json",
            "[1,2]",
            "42",
            "{}",
            """{"type":7}""",
            """{"type":"new"}""",
            """{"type":"new","message_id":"../../etc"}""",
            """{"type":"new","message_id":12}""",
            """{"type":"mystery","message_id":"$ID"}""",
            """{"type":"pong","server_time":"yesterday"}""",
            """{"type":"pong"}""",
            "{\"type\":\"new\",\"message_id\":\"$ID\",\"pad\":\"${"x".repeat(20_000)}\"}",
        ).forEach(peer::send)
        peer.socket!!.send("""{"type":"new","message_id":"$ID"}""".encodeUtf8()) // binary: not part of the contract
        peer.send("""{"type":"new","message_id":"$ID2"}""")
        await("the valid frame") { queue.processed.isNotEmpty() }
        assertEquals(listOf(RecordingWorkQueue.Process(ID2, expedited = true)), queue.processed.toList())
        assertEquals(1, server.requestCount)
        assertEquals(ConnectionState.CONNECTED, states.last())
        assertTrue(revoked.isEmpty())
        assertFalse(clock.isSynced)
    }

    @Test
    fun pongServerTimeUpdatesTheServerClock() {
        val peer = connect()
        peer.send("""{"type":"pong","server_time":"2027-01-15T08:00:00.123456Z"}""")
        await("clock sync") { clock.isSynced }
        assertEquals(Instant.parse("2027-01-15T08:00:00.123456Z"), clock.serverNow())
        loop.advance(1_000)
        assertEquals(Instant.parse("2027-01-15T08:00:01.123456Z"), clock.serverNow())
    }

    @Test
    fun revokedFrameRevokesWithThisSocketsTokenAndStops() {
        val peer = connect()
        peer.send("""{"type":"revoked"}""")
        await("revocation") { revoked.isNotEmpty() }
        assertEquals(listOf(TOKEN), revoked.toList())
        assertEquals(1000, peer.closes.poll(5, TimeUnit.SECONDS))
        assertTrue("no reconnect after a revocation", loop.timers().isEmpty())
        assertEquals(ConnectionState.OFF, states.last())
        assertEquals(1, loop.closed)
        loop.advance(SocketClient.PING_INTERVAL_MS * 3)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun revocationWipesCredentialsOnlyWhenTheSocketTokenIsStillStored() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = CredentialStore(
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "c.preferences_pb") }),
                FakeTokenCipher(),
            )
            val cancels = AtomicInteger()
            val stops = AtomicInteger()
            val revocation = Revocation(store, { cancels.incrementAndGet() }, stopRealtime = { stops.incrementAndGet() })
            onRevoked = { token -> runBlocking { revocation.onUnauthorized(token) }; revoked += token }

            // Re-paired since this socket opened: its revocation must not touch the new pairing.
            store.save(credentials.serverUrl, "dev-2", "dt_new")
            val stale = connect(client(credentials.copy(deviceToken = "dt_old")))
            stale.send("""{"type":"revoked"}""")
            await("stale revocation") { revoked.isNotEmpty() }
            assertEquals(Credentials(credentials.serverUrl, "dev-2", "dt_new"), store.load())
            assertEquals(0, cancels.get())
            assertEquals(0, stops.get())

            // The current token: wiped, work cancelled, service stopped.
            revoked.clear()
            states.clear()
            val current = connect(client(credentials.copy(deviceToken = "dt_new")))
            current.send("""{"type":"revoked"}""")
            await("revocation") { revoked.isNotEmpty() }
            assertNull(store.load())
            assertEquals(1, cancels.get())
            assertEquals(1, stops.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun close4003Revokes() {
        val peer = connect()
        peer.socket!!.close(4003, "revoked")
        await("revocation") { revoked.isNotEmpty() }
        assertEquals(listOf(TOKEN), revoked.toList())
        assertTrue(loop.timers().isEmpty())
    }

    @Test
    fun unauthorizedErrorFrameRevokes() {
        val peer = connect()
        peer.send("""{"type":"error","code":"invalid_heartbeat","message":"m"}""")
        peer.send("""{"type":"error","code":"unauthorized","message":"m"}""")
        await("revocation") { revoked.isNotEmpty() }
        assertEquals(listOf(TOKEN), revoked.toList())
    }

    @Test
    fun upgrade401WithTheBackendEnvelopeRevokes() {
        refuse(401, """{"error":{"code":"unauthorized","message":"invalid or revoked device token"}}""")
        client().start()
        await("revocation") { revoked.isNotEmpty() }
        assertEquals(listOf(TOKEN), revoked.toList())
        assertTrue(loop.timers().isEmpty())
        assertEquals(ConnectionState.OFF, states.last())
    }

    @Test
    fun bare401FromAProxyBacksOffInsteadOfRevoking() {
        refuse(401, "<html>Unauthorized</html>")
        val c = client()
        c.start()
        val timer = retryTimer()
        assertTrue(revoked.isEmpty())
        assertTrue(timer.delayMs in 500..1_000)
        val peer = upgrade()
        loop.advance(timer.delayMs)
        assertTrue(peer.next().contains("heartbeat"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun replaced4001WaitsTheLongCappedDelayAndIgnoresNetworkNudges() {
        val c = client()
        val peer = connect(c)
        peer.socket!!.close(4001, "replaced")
        val timer = retryTimer()
        assertTrue("4001 delay ${timer.delayMs}", timer.delayMs in ReconnectBackoff.MAX_MS / 2..ReconnectBackoff.MAX_MS)
        c.reconnectNow() // a network change must not make the two sockets fight
        loop.idle()
        assertEquals(ConnectionState.WAITING, states.last())
        assertEquals(listOf(timer), loop.timers())
        val next = upgrade()
        loop.advance(timer.delayMs)
        assertTrue(next.next().contains("heartbeat"))
    }

    @Test
    fun otherCloseCodesReconnectWithBackoff() {
        for (code in listOf(SocketClient.CLOSE_IDLE, 1001, 1011, 1013)) {
            states.clear()
            val c = client()
            val peer = connect(c)
            peer.socket!!.close(code, "x")
            val timer = retryTimer()
            assertTrue("close $code delay ${timer.delayMs}", timer.delayMs in 500..1_000)
            assertTrue(revoked.isEmpty())
            c.stop()
            loop.idle()
        }
    }

    @Test
    fun lateCallbacksOfADroppedSocketAreIgnored() {
        val peer = connect()
        states.clear()
        peer.socket!!.close(1001, "going away")
        // OkHttp reports both onClosing and onClosed for this socket: only the first counts.
        assertTrue(peer.closed.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        loop.idle()
        assertEquals(listOf(ConnectionState.WAITING), states.toList())
        assertTrue(loop.timers().single().delayMs in 500..1_000)
    }

    @Test
    fun backoffDoublesOnRepeatedFailuresUpToFiveMinutesWithJitter() {
        val c = client(random = Random(99))
        repeat(12) { refuse(503, """{"error":{"code":"ws_capacity","message":"m"}}""") }
        c.start()
        val delays = mutableListOf<Long>()
        repeat(12) { i ->
            val timer = retryTimer()
            val nominal = minOf(1_000L shl i, ReconnectBackoff.MAX_MS)
            assertTrue("attempt $i: ${timer.delayMs} not in [${nominal / 2}, $nominal]", timer.delayMs in nominal / 2..nominal)
            assertTrue(timer.wakeup)
            delays += timer.delayMs
            states.clear()
            loop.advance(timer.delayMs)
            await("request ${i + 2}") { server.requestCount == i + 2 }
        }
        assertTrue(delays.takeLast(3).all { it in ReconnectBackoff.MAX_MS / 2..ReconnectBackoff.MAX_MS })
    }

    @Test
    fun backoffResetsOnlyAfterAConnectionStayedHealthy() {
        val c = client()
        repeat(3) { refuse(503) }
        c.start()
        var peer: Peer? = null
        repeat(3) { i -> // attempts 0, 1, 2 fail; the third retry connects
            val t = retryTimer()
            states.clear()
            if (i == 2) peer = upgrade()
            loop.advance(t.delayMs)
            if (i < 2) await("retry") { server.requestCount == i + 2 }
        }
        val first = peer!!
        assertTrue(first.next().contains("heartbeat"))
        await("connected") { states.lastOrNull() == ConnectionState.CONNECTED }

        // Dropped before it was healthy: the backoff keeps growing (attempt 3: 4–8 s).
        first.socket!!.close(SocketClient.CLOSE_IDLE, "x")
        val t1 = retryTimer()
        assertTrue("quick drop ${t1.delayMs}", t1.delayMs in 4_000..8_000)
        val second = upgrade()
        states.clear()
        loop.advance(t1.delayMs)
        assertTrue(second.next().contains("heartbeat"))
        await("connected") { states.lastOrNull() == ConnectionState.CONNECTED }

        // Open for 30 s, then dropped: back to 0.5–1 s.
        loop.advance(SocketClient.HEALTHY_AFTER_MS)
        second.socket!!.close(1001, "going away")
        val t2 = retryTimer()
        assertTrue("after a healthy connection ${t2.delayMs}", t2.delayMs in 500..1_000)
    }

    @Test
    fun networkAvailableRetriesAtOnceWithAFreshBackoff() {
        val c = client()
        repeat(4) { refuse(503) }
        c.start()
        repeat(3) {
            val t = retryTimer()
            states.clear()
            loop.advance(t.delayMs)
            await("retry") { server.requestCount == it + 2 }
        }
        val waiting = retryTimer() // attempt 3: 4–8 s
        assertTrue(waiting.delayMs >= 4_000)
        val peer = upgrade()
        c.reconnectNow()
        assertTrue(peer.next().contains("heartbeat"))
        await("connected") { states.lastOrNull() == ConnectionState.CONNECTED }
        assertEquals(listOf(SocketClient.KEEPALIVE_DELAY_MS), loop.timers().map { it.delayMs }) // retry timer cancelled
        // The next drop starts from 1 s again.
        peer.socket!!.close(1011, "x")
        assertTrue(retryTimer().delayMs in 500..1_000)
    }

    @Test
    fun reconnectNowWhileConnectedDoesNothing() {
        val c = client()
        connect(c)
        c.reconnectNow()
        loop.idle()
        assertEquals(ConnectionState.CONNECTED, states.last())
        assertEquals(listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED), states.toList())
    }

    @Test
    fun retryAfterOn429IsHonoured() {
        refuse(429, """{"error":{"code":"rate_limited","message":"m"}}""", "Retry-After" to "120")
        val c = client()
        c.start()
        val timer = retryTimer()
        assertTrue(timer.delayMs >= 120_000)
        c.reconnectNow()
        loop.idle()
        assertEquals(ConnectionState.WAITING, states.last())
        assertEquals(listOf(timer), loop.timers())
        assertTrue(revoked.isEmpty())
    }

    @Test
    fun networkFailureBacksOff() {
        server.close()
        client().start()
        val timer = retryTimer()
        assertTrue(timer.delayMs in 500..1_000)
    }

    @Test
    fun keepaliveFiresWithinTheServerContractEvenWhenTheAlarmIsLate() {
        // An inexact alarm may run up to 75 % late: still within "a ping at least every
        // 5 min", itself inside the server's 6-minute idle timeout (WS_PING_TIMEOUT).
        assertTrue(SocketClient.KEEPALIVE_DELAY_MS * 7 / 4 <= SocketClient.PING_INTERVAL_MS)
        assertTrue(SocketClient.PING_INTERVAL_MS < 6 * 60_000L)
        assertTrue(SocketClient.PONG_TIMEOUT_MS < SocketClient.KEEPALIVE_DELAY_MS)
    }

    @Test
    fun pingsOnTheKeepaliveTimerAndADeadSocketReconnects() {
        val peer = connect()
        loop.advance(SocketClient.KEEPALIVE_DELAY_MS)
        assertEquals(SocketClient.PING_FRAME, peer.next())
        peer.send("""{"type":"pong","server_time":"2027-01-15T08:00:00Z"}""")
        await("pong") { clock.isSynced }
        loop.advance(SocketClient.PONG_TIMEOUT_MS) // answered: nothing happens
        assertEquals(ConnectionState.CONNECTED, states.last())
        loop.advance(SocketClient.KEEPALIVE_DELAY_MS - SocketClient.PONG_TIMEOUT_MS)
        assertEquals(SocketClient.PING_FRAME, peer.next())
        // No answer this time: the socket is dead after the pong timeout.
        states.clear()
        loop.advance(SocketClient.PONG_TIMEOUT_MS)
        val timer = retryTimer()
        assertTrue(timer.delayMs in 500..1_000)
        assertTrue(revoked.isEmpty())
    }

    @Test
    fun stopClosesTheSocketAndNeverReconnects() {
        val c = client()
        val peer = connect(c)
        c.stop()
        assertEquals(1000, peer.closes.poll(5, TimeUnit.SECONDS))
        loop.idle()
        assertTrue(loop.timers().isEmpty())
        assertEquals(ConnectionState.OFF, states.last())
        c.start()
        c.reconnectNow()
        loop.advance(SocketClient.PING_INTERVAL_MS)
        assertEquals(1, server.requestCount)
        assertTrue(revoked.isEmpty())
    }

    @Test
    fun heartbeatFrameMatchesTheHttpHeartbeatFields() {
        assertEquals("""{"type":"heartbeat"}""", SocketClient.heartbeatFrame(Heartbeat()))
        assertEquals(
            """{"type":"heartbeat","battery":5,"charging":false,"signal":0,"sims":[],"app_version":"9"}""",
            SocketClient.heartbeatFrame(Heartbeat(5, false, 0, emptyList(), "9")),
        )
    }

    private companion object {
        const val TOKEN = "dt_1"
        const val ID = "0192f0c4-0000-7000-8000-0000000000a1"
        const val ID2 = "0192f0c4-0000-7000-8000-0000000000a2"
    }
}
