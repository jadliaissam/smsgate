package com.jassam.smsgate.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.jassam.smsgate.api.DeviceApi
import com.jassam.smsgate.api.DeviceHttp
import com.jassam.smsgate.auth.CredentialStore
import com.jassam.smsgate.auth.FakeTokenCipher
import com.jassam.smsgate.realtime.ConnectionState
import com.jassam.smsgate.ui.home.PhoneInfo
import com.jassam.smsgate.work.Revocation
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The unpair flow against a real stack: MockWebServer backend, real DeviceApi, real
 * CredentialStore (fake cipher) and the real Revocation (recording its effects). Every
 * path must go through Revocation with the token captured before the call.
 */
class UnpairFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var server: MockWebServer
    private lateinit var store: CredentialStore
    private val base get() = "http://127.0.0.1:${server.port}"
    @Volatile private var cancelled = 0
    @Volatile private var stops = 0
    @Volatile private var abandons = 0
    @Volatile private var revokeCalls = 0
    private val forgotten = java.util.Collections.synchronizedList(mutableListOf<String>())
    private lateinit var revocation: Revocation
    private val vms = mutableListOf<SettingsViewModel>()

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        server = MockWebServer()
        server.start()
        store = CredentialStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "credentials.preferences_pb") }),
            FakeTokenCipher(),
        )
        revocation = Revocation(store, { cancelled++ }, abandonClaimed = { abandons++ }, stopRealtime = { stops++ })
        runBlocking { store.save(base, "dev-1", "dt_1") }
    }

    @After
    fun tearDown() {
        runBlocking { vms.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        server.close()
        scope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun vm(): SettingsViewModel {
        val client = DeviceHttp.client("test")
        val vm = SettingsViewModel(
            SettingsDeps(
                credentials = store::load,
                unregister = { c -> DeviceApi(c.serverUrl, { c.deviceToken }, client).unregister() },
                revoke = { token -> revocation.onUnauthorized(token).also { revokeCalls++ } },
                alwaysConnected = flowOf(false),
                setAlwaysConnected = { true },
                connection = MutableStateFlow(ConnectionState.OFF),
                isCharging = { false },
                batteryExempt = { true },
                prefs = FakePrefs(),
                phone = { PhoneInfo(emptyList(), null) },
                syncNow = { true },
                lastSync = flowOf(null),
                now = { 0L },
                appVersion = "1.2.3",
                forgetAccount = { forgotten += it },
            ),
        )
        vms += vm
        scope.launch { vm.state.collect {} }
        return vm
    }

    /** Polls (the state is a StateFlow kept hot by a collector) until [done] holds; fails with the last state. */
    private fun SettingsViewModel.waitFor(done: (UnpairState) -> Boolean): UnpairState = runBlocking {
        withTimeoutOrNull(10_000) {
            while (!done(state.value.unpair)) delay(10)
            state.value.unpair
        } ?: throw AssertionError("unpair flow did not settle; last state ${state.value.unpair}, revoke calls $revokeCalls")
    }

    /**
     * Waits for the flow's final state [expected]. Working and Idle are also shown while the
     * request is in flight, so they count as final only once the revocation path has run
     * (and, for Working, the account cache was dropped, the last step before the final state).
     */
    private fun SettingsViewModel.settle(expected: UnpairState): UnpairState = waitFor { s ->
        s == expected && when (expected) {
            UnpairState.Working -> revokeCalls > 0 && forgotten.isNotEmpty()
            UnpairState.Idle -> revokeCalls > 0
            else -> true
        }
    }

    private fun SettingsViewModel.unpair(expected: UnpairState): UnpairState {
        requestUnpair()
        confirmUnpair()
        return settle(expected)
    }

    private fun respond(code: Int, body: String = "", json: Boolean = true) {
        val b = MockResponse.Builder().code(code).body(body)
        if (json && body.isNotEmpty()) b.setHeader("Content-Type", "application/json")
        server.enqueue(b.build())
    }

    private fun assertRevokedWith(): Unit = runBlocking {
        assertNull(store.load())
        assertEquals(listOf("dev-1"), forgotten.toList()) // cached account dropped, label kept
        assertEquals(1, cancelled)
        assertEquals(1, stops)
        assertEquals(1, abandons)
    }

    private fun assertNothingCleared(): Unit = runBlocking {
        assertNotNull(store.load())
        assertEquals(emptyList<String>(), forgotten.toList())
        assertEquals("dt_1", store.load()!!.deviceToken)
        assertEquals(0, cancelled)
        assertEquals(0, stops)
        assertEquals(0, abandons)
    }

    @Test
    fun noContentRevokesThroughRevocation() {
        respond(204)
        val vm = vm()
        vm.unpair(UnpairState.Working)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/device/v1/unregister", req.url.encodedPath)
        assertEquals("Bearer dt_1", req.headers["Authorization"])
        assertRevokedWith()
    }

    @Test
    fun alreadyRevoked401AlsoRevokesLocally() {
        respond(401, """{"error":{"code":"unauthorized","message":"revoked"}}""")
        vm().unpair(UnpairState.Working)
        assertRevokedWith()
    }

    @Test
    fun aBare401FromAProxyIsAFailureAndClearsNothing() {
        respond(401, "<html>401</html>", json = false)
        vm().unpair(UnpairState.Failed(UnpairFailure.Server))
        assertNothingCleared()
    }

    @Test
    fun aServerErrorShowsAnErrorAndClearsNothing() {
        respond(503, """{"error":{"code":"busy","message":"try later"}}""")
        vm().unpair(UnpairState.Failed(UnpairFailure.Server))
        assertNothingCleared()
    }

    @Test
    fun aNetworkErrorClearsNothingAndRetryWorks() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        val vm = vm()
        vm.unpair(UnpairState.Failed(UnpairFailure.Network))
        assertNothingCleared()
        respond(204)
        vm.confirmUnpair() // "Try again" from the failure dialog
        vm.settle(UnpairState.Working)
        assertRevokedWith()
    }

    @Test
    fun aTokenChangedDuringTheCallIsNotCleared() {
        // The phone is re-paired (new token) while unregister is in flight: the old
        // token's revocation must not wipe the new credentials.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { store.save(base, "dev-2", "dt_2") }
                return MockResponse.Builder().code(204).build()
            }
        }
        vm().unpair(UnpairState.Idle)
        runBlocking {
            assertEquals("dt_2", store.load()?.deviceToken)
            assertEquals(emptyList<String>(), forgotten.toList())
            assertEquals(0, cancelled)
            assertEquals(0, stops)
        }
    }

    @Test
    fun removeFromThisPhoneOnlyGoesThroughRevocationWithoutAServerCall() {
        val vm = vm()
        vm.requestLocalRemoval()
        vm.confirmLocalRemoval()
        vm.settle(UnpairState.Working)
        assertRevokedWith()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun localRemovalIsOfferedAfterAFailure() {
        respond(500, """{"error":{"code":"internal","message":"boom"}}""")
        val vm = vm()
        vm.unpair(UnpairState.Failed(UnpairFailure.Server))
        vm.requestLocalRemoval()
        vm.confirmLocalRemoval()
        vm.settle(UnpairState.Working)
        assertRevokedWith()
        assertEquals(1, server.requestCount)
    }
}
