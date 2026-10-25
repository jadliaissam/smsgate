package com.jassam.smsgate.realtime

import android.app.Application
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.jassam.smsgate.api.Heartbeat
import com.jassam.smsgate.api.ServerClock
import com.jassam.smsgate.auth.Credentials
import com.jassam.smsgate.work.Scheduler
import com.jassam.smsgate.work.testWorkConfiguration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** `new` frames go to the same unique KEEP job as FCM pushes, so duplicates and replays collapse. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SocketClientWorkTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private lateinit var wm: WorkManager
    private lateinit var client: SocketClient
    private val loop = FakeLoop()

    private class Peer : WebSocketListener() {
        val messages = LinkedBlockingQueue<String>()
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            messages += text
        }
        // Complete the close handshake: MockWebServer.close() waits for its socket threads and
        // throws AssertionError("Gave up waiting for executor to terminate") if a socket stays open.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, testWorkConfiguration())
        wm = WorkManager.getInstance(app)
        server.start()
        client = SocketClient(
            credentials = Credentials("http://127.0.0.1:${server.port}", "dev-1", "dt_1"),
            httpClient = OkHttpClient(),
            loop = loop,
            backoff = ReconnectBackoff(Random(1)),
            queue = Scheduler { wm },
            clock = ServerClock(monotonicMillis = { loop.now() }, wallMillis = { 0L }),
            heartbeat = { Heartbeat() },
            onRevoked = { fail("not revoked") },
        )
    }

    @After
    fun tearDown() {
        client.stop()
        server.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get()

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            if (System.nanoTime() > end) fail("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun duplicateAndReplayedNewFramesCollapseIntoOneExpeditedJob() {
        val peer = Peer()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(peer).build())
        client.start()
        peer.messages.poll(5, TimeUnit.SECONDS) ?: fail("no heartbeat")
        val id = "0192f0c4-0000-7000-8000-0000000000a1"
        val other = "0192f0c4-0000-7000-8000-0000000000a2"
        peer.socket!!.send("""{"type":"new","message_id":"$id"}""")
        peer.socket!!.send("""{"type":"new","message_id":"$id"}""")
        peer.socket!!.send("""{"type":"new","message_id":"${id.uppercase()}"}""")
        peer.socket!!.send("""{"type":"new","message_id":"$other"}""") // frames are handled in order
        await("the last frame") { infos(Scheduler.processName(other)).isNotEmpty() }
        loop.idle()
        assertEquals(1, infos(Scheduler.processName(id)).size)
        assertEquals(WorkInfo.State.ENQUEUED, infos(Scheduler.processName(id)).single().state)
        assertEquals(1, infos(Scheduler.SYNC_NOW).size) // the catch-up sync on open
    }
}
