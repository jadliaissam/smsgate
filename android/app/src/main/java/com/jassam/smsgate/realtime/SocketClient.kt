package com.jassam.smsgate.realtime

import com.jassam.smsgate.api.ApiJson
import com.jassam.smsgate.api.ApiResult
import com.jassam.smsgate.api.ErrorEnvelope
import com.jassam.smsgate.api.Heartbeat
import com.jassam.smsgate.api.ServerClock
import com.jassam.smsgate.api.ServerUrl
import com.jassam.smsgate.auth.Credentials
import com.jassam.smsgate.work.HeartbeatSource
import com.jassam.smsgate.work.MessageIds
import com.jassam.smsgate.work.WorkQueue
import com.jassam.smsgate.work.isRevocation
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** The always-connected socket as [ConnectivityService] drives it. */
interface RealtimeClient {
    /** Connects (once; later calls do nothing). */
    fun start()

    /** Closes the socket for good: no reconnect after this. */
    fun stop()

    /**
     * Reconnects right away with a fresh backoff when disconnected and waiting (a network
     * became available, the service was started again). Ignored while connecting or
     * connected, and while a server-requested wait runs (4001 replaced, Retry-After).
     */
    fun reconnectNow()
}

/**
 * The device WebSocket, `GET <server>/device/v1/ws` (wss, or ws for a local debug
 * server), authenticated with `Authorization: Bearer` and the token of [credentials].
 * Server contract (backend/internal/realtime/hub.go, devices/ws.go, README):
 *
 * - server → device: `{"type":"new","message_id"}`, `{"type":"revoked"}` (then close
 *   4003), `{"type":"pong","server_time"}` (answers a ping or heartbeat, in order),
 *   `{"type":"error","code","message"}` (answers a bad frame, in order; the socket stays
 *   open).
 * - device → server: `{"type":"ping"}` and `{"type":"heartbeat", …}` with exactly the
 *   fields of `POST /device/v1/heartbeat` (the server rejects unknown fields).
 * - The server closes a socket that sent no data frame for `WS_PING_TIMEOUT` (6 min) with
 *   4000; WebSocket protocol pings don't count. Close codes: 4000 idle, 4001 replaced by a
 *   newer connection of this device, 4003 revoked, 1001 shutting down, 1011 internal
 *   error, 1013 instance full. Before the upgrade: 401 (bad or revoked token), 429
 *   `rate_limited` with Retry-After, 503 `ws_capacity`/`shutting_down`.
 *
 * Behaviour:
 * - On open: a heartbeat frame ([heartbeat], nulls left out), then [WorkQueue.syncNow]
 *   (catch-up via `pending()`, recovery), since nothing missed while disconnected is
 *   replayed over the socket.
 * - `new` → [WorkQueue.processMessage] (expedited, the same unique KEEP job as an FCM
 *   push, so duplicates and replays collapse). Nothing is processed on the socket's
 *   thread; frames carry ids only and are never logged.
 * - `pong`'s `server_time` updates [clock] (request/response marks matched in order).
 * - Revocation: a `revoked` frame, close 4003, an `error` frame `unauthorized` or an
 *   upgrade 401 that [isRevocation] (the backend's envelope) → [onRevoked] with this
 *   socket's token, and the client stops. A bare 401 (a proxy's, `http_401`) only backs off.
 * - Keepalive: an app-level `{"type":"ping"}` at least every [PING_INTERVAL_MS] (5 min,
 *   the README's contract, inside the server's 6-minute idle timeout), on a wake-up
 *   timer armed [KEEPALIVE_DELAY_MS] ahead: inexact alarms may run up to 75 % late, so
 *   pings go out every 2 min 51 s to 5 min. In deep Doze, without the battery-optimisation
 *   exemption (D12), the OS allows such an alarm only once per 9 min: the server may then
 *   close the socket with 4000 and FCM delivers instead. No OkHttp protocol pings: they don't satisfy
 *   the server's idle timer, and a second, unaligned ping schedule would wake the radio
 *   twice. A ping unanswered after [PONG_TIMEOUT_MS] (or by the next ping) marks the
 *   socket dead → reconnect.
 * - Reconnect: [ReconnectBackoff] (1 s doubling to 5 min, jittered); the attempt count
 *   resets once a connection stayed open [HEALTHY_AFTER_MS]. 4001 (replaced) waits the
 *   long, capped delay so two sockets of one device don't keep replacing each other; a
 *   Retry-After is honoured. Server-wide events (close 1001 shutting down / 1013 full, an
 *   upgrade answered 502/503/504) wait at least a uniformly random
 *   [BUSY_MIN_DELAY_MS]–[BUSY_MAX_DELAY_MS], so thousands of phones don't reconnect in the
 *   same second; the attempt count still grows. These server-requested waits are not
 *   cut short by a network change.
 *
 * Thread-safe: all state is confined to [loop]; OkHttp callbacks post to it and are
 * ignored once their socket is no longer the current one.
 */
class SocketClient(
    private val credentials: Credentials,
    httpClient: OkHttpClient,
    private val loop: SocketLoop,
    private val backoff: ReconnectBackoff,
    private val queue: WorkQueue,
    private val clock: ServerClock,
    private val heartbeat: HeartbeatSource,
    /** Called once, on the loop, with the token this socket authenticated with. */
    private val onRevoked: (tokenUsed: String) -> Unit,
    private val onState: (ConnectionState) -> Unit = {},
    /** Diagnostics; never passed tokens, numbers or bodies. */
    private val log: (String) -> Unit = {},
    /** Tests only: a fake socket factory instead of [httpClient]. */
    webSockets: WebSocket.Factory? = null,
) : RealtimeClient {
    // readTimeout(0): the API client's 30 s read timeout would kill an idle socket.
    private val client: WebSocket.Factory = webSockets ?: httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    // State below: loop thread only.
    private var started = false
    private var stopped = false
    private var socket: WebSocket? = null
    private var open = false
    private var openedAt = NEVER
    private var attempt = 0
    private var holdUntil = NEVER
    private var retry: Cancellable? = null
    private var pingTimer: Cancellable? = null
    private var pongTimer: Cancellable? = null
    private var awaitingPong = false
    private val replyMarks = ArrayDeque<Long>() // clock marks of frames awaiting a pong/error, oldest first

    override fun start() = loop.post {
        if (started || stopped) return@post
        started = true
        connect()
    }

    override fun stop() = loop.post { shutdown() }

    override fun reconnectNow() = loop.post {
        if (!started || stopped || socket != null || loop.now() < holdUntil) return@post
        attempt = 0
        connect()
    }

    private fun connect() {
        cancelRetry()
        val request = try {
            Request.Builder()
                .url(ServerUrl.resolve(credentials.serverUrl.toHttpUrl(), WS_PATH))
                .header("Authorization", "Bearer ${credentials.deviceToken}")
                .build()
        } catch (e: IllegalArgumentException) {
            log("socket: invalid server url")
            shutdown()
            return
        }
        open = false
        openedAt = NEVER
        awaitingPong = false
        replyMarks.clear()
        state(ConnectionState.CONNECTING)
        socket = client.newWebSocket(request, Listener())
    }

    private fun onOpened() {
        open = true
        openedAt = loop.now()
        holdUntil = NEVER
        state(ConnectionState.CONNECTED)
        val hb = try {
            heartbeat.read()
        } catch (e: Exception) {
            log("socket: heartbeat source failed: ${e.javaClass.simpleName}")
            Heartbeat()
        }
        send(heartbeatFrame(hb), expectsReply = true)
        guarded("sync") { queue.syncNow() }
        schedulePing()
    }

    private fun onText(text: String, receivedAt: Long) {
        awaitingPong = false
        if (text.length > MAX_FRAME_CHARS) return
        val frame = try {
            ApiJson.decodeFromString(InFrame.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        when (frame?.type) {
            "new" -> {
                val id = MessageIds.normalize(frame.messageId) ?: return
                guarded("process") { queue.processMessage(id, expedited = true) }
            }
            "pong" -> {
                val sentAt = replyMarks.removeFirstOrNull() ?: receivedAt
                val serverTime = frame.serverTime?.let(::parseInstant) ?: return
                clock.update(serverTime, sentAt, receivedAt)
            }
            "error" -> {
                replyMarks.removeFirstOrNull()
                if (frame.code == CODE_UNAUTHORIZED) revoked() else log("socket: error frame ${safeCode(frame.code)}")
            }
            "revoked" -> revoked()
            else -> Unit // malformed or unknown: ignored
        }
    }

    private fun onClosed(code: Int) {
        dropSocket()
        when (code) {
            CLOSE_REVOKED -> revoked()
            CLOSE_REPLACED -> {
                log("socket: replaced by another connection of this device")
                scheduleReconnect(backoff.longDelayMs(), hold = true)
            }
            CLOSE_GOING_AWAY, CLOSE_TRY_AGAIN_LATER -> {
                log("socket: closed $code")
                scheduleBusyReconnect()
            }
            else -> {
                log("socket: closed $code")
                scheduleReconnect()
            }
        }
    }

    private fun onFailed(failure: UpgradeFailure?) {
        dropSocket()
        if (failure == null) {
            scheduleReconnect()
            return
        }
        val error = failure.error
        if (error.isRevocation) {
            revoked()
            return
        }
        log("socket: upgrade HTTP ${error.status} ${safeCode(error.code)}")
        val retryAfterMs = error.retryAfterSeconds?.let { (it * 1000).coerceAtMost(MAX_RETRY_AFTER_MS) } ?: 0
        when {
            error.status in BUSY_STATUSES -> scheduleBusyReconnect(retryAfterMs)
            retryAfterMs > 0 -> scheduleReconnect(maxOf(nextBackoff(), retryAfterMs), hold = true)
            else -> scheduleReconnect()
        }
    }

    /** Server-wide trouble: a wide random wait (at least [minDelayMs]), held against nudges. */
    private fun scheduleBusyReconnect(minDelayMs: Long = 0) {
        val spread = backoff.uniformMs(BUSY_MIN_DELAY_MS, BUSY_MAX_DELAY_MS)
        scheduleReconnect(maxOf(spread, nextBackoff(), minDelayMs), hold = true)
    }

    private fun nextBackoff(): Long {
        if (openedAt != NEVER && loop.now() - openedAt >= HEALTHY_AFTER_MS) attempt = 0
        val delay = backoff.delayMs(attempt)
        if (attempt < MAX_ATTEMPT) attempt++
        return delay
    }

    private fun scheduleReconnect(delayMs: Long = nextBackoff(), hold: Boolean = false) {
        if (stopped) return
        cancelRetry()
        holdUntil = if (hold) loop.now() + delayMs else NEVER
        state(ConnectionState.WAITING)
        retry = loop.schedule(delayMs, wakeup = true) {
            retry = null
            if (!stopped && socket == null) connect()
        }
    }

    private fun schedulePing() {
        pingTimer?.cancel()
        pingTimer = loop.schedule(KEEPALIVE_DELAY_MS, wakeup = true) { onPingDue() }
    }

    private fun onPingDue() {
        pingTimer = null
        if (socket == null || !open) return
        if (awaitingPong) return dead("no pong")
        if (!send(PING_FRAME, expectsReply = true)) return
        awaitingPong = true
        pongTimer?.cancel()
        pongTimer = loop.schedule(PONG_TIMEOUT_MS, wakeup = false) {
            pongTimer = null
            if (awaitingPong && socket != null) dead("pong timeout")
        }
        schedulePing()
    }

    /** The socket stopped answering: drop it and reconnect. */
    private fun dead(reason: String) {
        log("socket: $reason")
        val s = socket
        dropSocket()
        s?.cancel()
        scheduleReconnect()
    }

    private fun send(frame: String, expectsReply: Boolean): Boolean {
        val sent = socket?.send(frame) == true
        if (sent && expectsReply) {
            if (replyMarks.size >= MAX_PENDING_REPLIES) replyMarks.removeFirst()
            replyMarks.addLast(clock.mark())
        }
        return sent
    }

    private fun revoked() {
        if (stopped) return
        log("socket: device revoked")
        shutdown()
        onRevoked(credentials.deviceToken)
    }

    private fun shutdown() {
        val s = socket
        val wasOpen = open
        stopped = true
        cancelRetry()
        dropSocket()
        // A socket still upgrading is cancelled: a graceful close would only be queued
        // until the upgrade finished, keeping the connection (and the server's slot) alive.
        if (wasOpen) s?.close(NORMAL_CLOSURE, null) else s?.cancel()
        state(ConnectionState.OFF)
        loop.close() // after the tasks already posted; later callbacks are dropped
    }

    private fun dropSocket() {
        socket = null
        open = false
        awaitingPong = false
        pingTimer?.cancel()
        pingTimer = null
        pongTimer?.cancel()
        pongTimer = null
    }

    private fun cancelRetry() {
        retry?.cancel()
        retry = null
    }

    private fun state(s: ConnectionState) {
        guarded("state") { onState(s) }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("socket: $what failed: ${e.javaClass.simpleName}")
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = loop.post {
            if (webSocket === socket) onOpened()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val receivedAt = clock.mark()
            loop.post { if (webSocket === socket) onText(text, receivedAt) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            loop.post { if (webSocket === socket) awaitingPong = false } // binary frames: not part of the contract
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null) // complete the close handshake
            loop.post { if (webSocket === socket) onClosed(code) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = loop.post {
            if (webSocket === socket) onClosed(code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // The response (a refused upgrade) is closed when this returns: read it here.
            val failure = response?.let(UpgradeFailure::of)
            loop.post { if (webSocket === socket) onFailed(failure) }
        }
    }

    /** A refused upgrade, as the API client would report it. */
    internal class UpgradeFailure(val error: ApiResult.HttpError) {
        companion object {
            fun of(response: Response): UpgradeFailure {
                val body = try {
                    response.peekBody(MAX_ERROR_BODY).string()
                } catch (_: IOException) {
                    ""
                } catch (_: IllegalStateException) {
                    ""
                }
                val envelope = try {
                    ApiJson.decodeFromString(ErrorEnvelope.serializer(), body).error
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
                val status = response.code
                val code = envelope?.code?.takeIf { it.isNotBlank() } ?: "http_$status"
                val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                return UpgradeFailure(ApiResult.HttpError(status, code, envelope?.message ?: "HTTP $status", retryAfter))
            }
        }
    }

    @Serializable
    private data class InFrame(
        val type: String? = null,
        @SerialName("message_id") val messageId: String? = null,
        @SerialName("server_time") val serverTime: String? = null,
        val code: String? = null,
    )

    companion object {
        const val WS_PATH = "device/v1/ws"

        /** Longest gap between device frames (README: "at least every 5 min"; the server closes after `WS_PING_TIMEOUT`, 6 min). */
        const val PING_INTERVAL_MS = 5 * 60_000L

        /** The keepalive alarm's delay: even 75 % late (inexact alarm) it fires within [PING_INTERVAL_MS]. */
        const val KEEPALIVE_DELAY_MS = PING_INTERVAL_MS * 4 / 7

        /** A ping not answered within this marks the socket dead. */
        const val PONG_TIMEOUT_MS = 30_000L

        /** A connection open this long resets the backoff. */
        const val HEALTHY_AFTER_MS = 30_000L

        /** Upper bound for a server's Retry-After. */
        const val MAX_RETRY_AFTER_MS = 15 * 60_000L

        const val CLOSE_IDLE = 4000
        const val CLOSE_REPLACED = 4001
        const val CLOSE_REVOKED = 4003
        const val CLOSE_GOING_AWAY = 1001
        const val CLOSE_TRY_AGAIN_LATER = 1013
        const val NORMAL_CLOSURE = 1000

        /** Bounds of the random wait after a server-wide disconnect (deploy, full instance). */
        const val BUSY_MIN_DELAY_MS = 1_000L
        const val BUSY_MAX_DELAY_MS = 60_000L
        private val BUSY_STATUSES = setOf(502, 503, 504)

        const val PING_FRAME = """{"type":"ping"}"""
        private const val CODE_UNAUTHORIZED = "unauthorized"
        private const val MAX_FRAME_CHARS = 16 * 1024
        private const val MAX_ERROR_BODY = 4L * 1024
        private const val MAX_PENDING_REPLIES = 16
        private const val MAX_ATTEMPT = 30
        private const val NEVER = Long.MIN_VALUE
        private val SAFE_CODE = Regex("[a-z0-9_]{1,64}")

        /** `{"type":"heartbeat", …}` with the HTTP heartbeat's fields; nulls are left out. */
        fun heartbeatFrame(heartbeat: Heartbeat): String {
            val fields = ApiJson.encodeToJsonElement(Heartbeat.serializer(), heartbeat).jsonObject
            return JsonObject(mapOf("type" to JsonPrimitive("heartbeat")) + fields).toString()
        }

        private fun parseInstant(s: String): Instant? = try {
            Instant.parse(s)
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun safeCode(code: String?): String = code?.takeIf { SAFE_CODE.matches(it) } ?: "?"
    }
}
