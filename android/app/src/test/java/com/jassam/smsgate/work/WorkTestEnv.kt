package com.jassam.smsgate.work

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.jassam.smsgate.api.DeviceApi
import com.jassam.smsgate.api.DeviceHttp
import com.jassam.smsgate.api.Heartbeat
import com.jassam.smsgate.api.ServerClock
import com.jassam.smsgate.api.Sim
import com.jassam.smsgate.api.Status
import com.jassam.smsgate.auth.CredentialStore
import com.jassam.smsgate.auth.FakeTokenCipher
import com.jassam.smsgate.journal.JournalDao
import com.jassam.smsgate.journal.JournalDb
import com.jassam.smsgate.journal.PendingReport
import com.jassam.smsgate.journal.inMemoryJournal
import com.jassam.smsgate.sms.FakeTransport
import com.jassam.smsgate.sms.SmsIntents
import com.jassam.smsgate.sms.SmsSender
import java.io.Closeable
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.robolectric.RuntimeEnvironment

/** Records what the workers and the push handler ask WorkManager to do. */
internal class RecordingWorkQueue : WorkQueue {
    data class Process(val id: String, val expedited: Boolean)

    val processed: MutableList<Process> = Collections.synchronizedList(mutableListOf())
    val reports: MutableList<PendingReport> = Collections.synchronizedList(mutableListOf())
    val tokens: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    @Volatile var syncs = 0
    @Volatile var periodic = 0
    @Volatile var cancelled = 0

    override fun enqueue(id: String, status: Status, errorCode: String?, errorMessage: String?) {
        reports += PendingReport(id, status, errorCode, errorMessage)
    }

    override fun processMessage(id: String, expedited: Boolean) {
        processed += Process(id, expedited)
    }

    override fun syncNow() {
        syncs++
    }

    override fun schedulePeriodicSync() {
        periodic++
    }

    override fun setFcmToken(token: String?) {
        tokens += token
    }

    override suspend fun cancelAll() {
        cancelled++
    }
}

/**
 * Workers against fakes: a MockWebServer backend, a real CredentialStore (fake cipher),
 * an in-memory journal, a real SmsSender over [FakeTransport] reporting into
 * [queue], and a Revocation whose "cancel all work" is [queue].cancelAll.
 */
internal class WorkTestEnv(dir: File) : Closeable {
    val app: Application = RuntimeEnvironment.getApplication()
    val server = MockWebServer().apply { start() }
    val base = "http://127.0.0.1:${server.port}"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val cipher = FakeTokenCipher()
    val store = CredentialStore(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "credentials.preferences_pb") }),
        cipher,
    )
    val db: JournalDb = inMemoryJournal()
    val dao: JournalDao = db.journalDao()

    /** Local wall clock (epoch ms) for journal timestamps. */
    @Volatile var wall = 1_800_000_000_000L
    val clock = ServerClock(monotonicMillis = { 0L }, wallMillis = { wall })
    val transport = FakeTransport()
    val queue = RecordingWorkQueue()
    val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val revocation = Revocation(store, queue::cancelAll)

    var heartbeat: () -> Heartbeat = {
        Heartbeat(battery = 81, charging = true, signal = 3, sims = listOf(Sim(0, 11, "Orange")), appVersion = "1.2.3")
    }
    var fcm: String? = "fcm-current"

    val sender = SmsSender(
        dao = dao,
        transport = transport,
        sims = { listOf(Sim(0, 11, "Orange"), Sim(1, 22, "inwi")) },
        phoneStateGranted = { true },
        defaultSmsSubscriptionId = { 11 },
        intents = { SmsIntents.pendingIntent(app, it) },
        reports = queue,
        now = { wall },
        serverNow = { clock.serverNow().toEpochMilliseconds() },
    )

    private val client = DeviceHttp.client("test")

    val deps = WorkDeps(
        credentials = store,
        api = { c -> DeviceApi(c.serverUrl, { c.deviceToken }, client, clock) },
        journal = dao,
        sender = sender,
        queue = queue,
        revocation = revocation,
        heartbeat = { heartbeat() },
        fcmToken = { fcm },
        now = { wall },
        log = { logs += it },
    )

    fun pair(token: String = TOKEN, deviceId: String = "dev-1") = runBlocking { store.save(base, deviceId, token) }

    inline fun <reified W : ListenableWorker> worker(input: Data, attempt: Int = 0): W =
        TestListenableWorkerBuilder<W>(app)
            .setInputData(input)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(SmsGateWorkerFactory { deps })
            .build()

    fun respond(code: Int, body: String = "") {
        val b = MockResponse.Builder().code(code).body(body)
        if (body.isNotEmpty()) b.setHeader("Content-Type", "application/json")
        server.enqueue(b.build())
    }

    fun respondError(code: Int, error: String) = respond(code, """{"error":{"code":"$error","message":"m"}}""")

    fun dropConnection() = server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

    fun take(): RecordedRequest = server.takeRequest()

    fun RecordedRequest.json(): JsonElement = Json.parseToJsonElement(body!!.utf8())

    override fun close() {
        server.close()
        db.close()
        scope.cancel()
    }

    companion object {
        const val TOKEN = "dt_1"
        const val ID = "0192f0c4-0000-7000-8000-0000000000a1"
        const val ID2 = "0192f0c4-0000-7000-8000-0000000000a2"
        const val SERVER_TIME = "2027-01-15T08:00:00Z"
        const val FUTURE = "2027-01-15T08:05:00Z"
        const val PAST = "2027-01-15T07:59:59Z"

        fun claimJson(id: String = ID, expiresAt: String = FUTURE, simSlot: Int? = null, body: String = "Your code is 4242") =
            """{"id":"$id","to":"+212600000001","body":"$body","sim_slot":${simSlot ?: "null"},""" +
                """"expires_at":"$expiresAt","server_time":"$SERVER_TIME"}"""
    }
}
