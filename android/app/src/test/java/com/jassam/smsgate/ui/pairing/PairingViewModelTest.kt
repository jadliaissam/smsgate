package com.jassam.smsgate.ui.pairing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jassam.smsgate.api.DeviceApi
import com.jassam.smsgate.api.DeviceHttp
import com.jassam.smsgate.api.ServerUrl
import com.jassam.smsgate.api.Sim
import com.jassam.smsgate.auth.CredentialStore
import com.jassam.smsgate.auth.FakeTokenCipher
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var server: MockWebServer
    private lateinit var store: CredentialStore
    private val base get() = "http://127.0.0.1:${server.port}"

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        server = MockWebServer()
        server.start()
        val file = File(tmp.root, "credentials.preferences_pb")
        store = CredentialStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }), FakeTokenCipher())
    }

    @After
    fun tearDown() {
        server.close()
        scope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun vm(
        playServices: Boolean = true,
        defaultServer: String = base,
        sims: () -> List<Sim> = { listOf(Sim(0, 3, "Orange")) },
    ) = PairingViewModel(
        pairer = Pairer(
            normalizeServerUrl = { ServerUrl.normalize(it, allowLocalHttp = true) },
            apiFactory = { url, token -> DeviceApi(url, token, DeviceHttp.client("test")) },
            credentials = store,
            sims = sims,
            fcmToken = { "fcm-token-1" },
            appVersion = "1.2.3",
            postPairing = PostPairingHook { },
        ),
        defaultServerUrl = defaultServer,
        defaultName = "Pixel 8",
        playServices = playServices,
        normalizeServerUrl = { ServerUrl.normalize(it, allowLocalHttp = true) },
        pairingStates = store.state,
    )

    private fun enqueueCreated() = server.enqueue(
        MockResponse.Builder().code(201).setHeader("Content-Type", "application/json")
            .body("""{"device_id":"d1","device_token":"dt_secret"}""").build(),
    )

    private fun settled(vm: PairingViewModel): PairingUiState = runBlocking {
        withTimeout(10_000) { vm.state.first { !it.busy && (it.paired || it.error != null || it.fieldErrors.isNotEmpty()) } }
    }

    @Test
    fun startsOnTheChooserWithDefaults() {
        val s = vm().state.value
        assertEquals(PairingMode.Choose, s.mode)
        assertEquals("Pixel 8", s.deviceName)
        assertEquals(base, s.serverUrl)
        assertTrue(s.canScan)
    }

    @Test
    fun withoutPlayServicesScanningIsHiddenAndNoFcmTokenIsSent() {
        val vm = vm(playServices = false)
        assertFalse(vm.state.value.canScan)
        enqueueCreated()
        vm.setMode(PairingMode.Code)
        vm.onCodeChange("ABCD2345")
        vm.submit()
        assertTrue(settled(vm).paired)
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertFalse("fcm_token" in body)
    }

    @Test
    fun codeInputIsNormalizedAndCapped() {
        val vm = vm()
        vm.onCodeChange("abcd-2345-xyz")
        assertEquals("ABCD2345", vm.state.value.code)
    }

    @Test
    fun incompleteCodeIsAFieldErrorWithoutARequest() {
        val vm = vm()
        vm.setMode(PairingMode.Code)
        vm.onCodeChange("ABC")
        vm.submit()
        val s = settled(vm)
        assertEquals(FieldError.IncompleteCode, s.fieldErrors[Field.Code])
        assertEquals(0, server.requestCount)
    }

    @Test
    fun loginNeedsEmailAndPassword() {
        val vm = vm()
        vm.setMode(PairingMode.Login)
        vm.onEmailChange("not-an-email")
        vm.submit()
        val s = settled(vm)
        assertEquals(FieldError.InvalidEmail, s.fieldErrors[Field.Email])
        assertEquals(FieldError.Required, s.fieldErrors[Field.Password])
        assertEquals(0, server.requestCount)
    }

    @Test
    fun badServerAndNameAreFieldErrors() {
        val vm = vm(defaultServer = "http://example.com")
        vm.setMode(PairingMode.Code)
        vm.onCodeChange("ABCD2345")
        vm.onNameChange(" ")
        vm.submit()
        val s = settled(vm)
        assertEquals(FieldError.InvalidServer, s.fieldErrors[Field.Server])
        assertEquals(FieldError.Required, s.fieldErrors[Field.Name])
        assertEquals(0, server.requestCount)
    }

    @Test
    fun editingAFieldClearsItsError() {
        val vm = vm()
        vm.setMode(PairingMode.Code)
        vm.submit()
        assertEquals(FieldError.IncompleteCode, settled(vm).fieldErrors[Field.Code])
        vm.onCodeChange("ABCD")
        assertNull(vm.state.value.fieldErrors[Field.Code])
    }

    @Test
    fun successfulCodePairing() {
        enqueueCreated()
        val vm = vm()
        vm.setMode(PairingMode.Code)
        vm.onCodeChange("ABCD2345")
        vm.submit()
        val s = settled(vm)
        assertTrue(s.paired)
        assertNull(s.error)
        assertEquals("ABCD2345", Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["pairing_code"]!!.jsonPrimitive.content)
        assertTrue(runBlocking { store.load() } != null)
    }

    @Test
    fun serverErrorShowsABannerAndCanBeDismissed() {
        server.enqueue(
            MockResponse.Builder().code(400).setHeader("Content-Type", "application/json")
                .body("""{"error":{"code":"invalid_pairing_code","message":"m"}}""").build(),
        )
        val vm = vm()
        vm.setMode(PairingMode.Code)
        vm.onCodeChange("ABCD2345")
        vm.submit()
        val s = settled(vm)
        assertEquals(PairingError.InvalidCode, s.error)
        assertFalse(s.paired)
        vm.dismissError()
        assertNull(vm.state.value.error)
    }

    private fun qr(server: String = base, code: String = "WXYZ6789") =
        "smsgate://pair?server=" + URLEncoder.encode(server, "UTF-8") + "&code=" + code

    /** Gives a wrongly launched request time to reach the server before counting. */
    private fun assertNothingSent() {
        Thread.sleep(300)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun qrForAnotherServerWaitsForConfirmThenPairs() {
        enqueueCreated()
        val vm = vm(defaultServer = "https://default.example")
        vm.onQrScanned(qr())
        val pending = vm.state.value.pendingQr
        assertEquals(PendingQr(base, "127.0.0.1:${server.port}", "WXYZ6789"), pending)
        assertFalse(vm.state.value.busy)
        assertNothingSent()

        vm.confirmQr(pending!!)
        val s = settled(vm)
        assertTrue(s.paired)
        assertNull(s.pendingQr)
        assertEquals(base, s.serverUrl)
        assertEquals(1, server.requestCount)
        assertEquals("WXYZ6789", Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["pairing_code"]!!.jsonPrimitive.content)
    }

    @Test
    fun cancelledQrSendsNothingAndLeavesTheFormAlone() {
        val vm = vm(defaultServer = "https://default.example")
        vm.onQrScanned(qr())
        vm.cancelQr()
        val s = vm.state.value
        assertNull(s.pendingQr)
        assertEquals(PairingMode.Choose, s.mode)
        assertEquals("", s.code)
        assertEquals("https://default.example", s.serverUrl)
        vm.confirmQr(PendingQr(base, "127.0.0.1:${server.port}", "WXYZ6789")) // nothing pending any more: no-op
        assertNothingSent()
    }

    @Test
    fun confirmingAQrThatIsNoLongerTheShownOneDoesNothing() {
        val vm = vm(defaultServer = "https://default.example")
        vm.onQrScanned(qr())
        val shown = vm.state.value.pendingQr!!
        vm.onQrScanned(qr().replace("WXYZ6789", "ABCD2345")) // a second scan replaces the pending one
        assertEquals("ABCD2345", vm.state.value.pendingQr!!.code)
        vm.confirmQr(shown) // the dialog still showed the first
        assertNothingSent()
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun qrForTheDefaultServerPairsWithoutConfirmation() {
        enqueueCreated()
        val vm = vm(defaultServer = "$base/") // same server once normalised
        vm.onQrScanned(qr())
        val s = settled(vm)
        assertTrue(s.paired)
        assertNull(s.pendingQr)
    }

    @Test
    fun qrWithAnUnacceptableServerIsRejected() {
        val vm = vm()
        vm.onQrScanned(qr(server = "http://example.com")) // plain HTTP to a public host
        assertEquals(PairingError.InvalidQr, settled(vm).error)
        assertNull(vm.state.value.pendingQr)
        assertNothingSent()
    }

    @Test
    fun foreignQrIsRejected() {
        val vm = vm()
        vm.onQrScanned("https://example.com/hello")
        assertEquals(PairingError.InvalidQr, settled(vm).error)
        assertNull(vm.state.value.pendingQr)
        assertNothingSent()
    }

    @Test
    fun unexpectedExceptionBecomesAGenericErrorWithoutCrashing() {
        val vm = vm(sims = { throw IllegalStateException("telephony service died") })
        vm.setMode(PairingMode.Code)
        vm.onCodeChange("ABCD2345")
        vm.submit()
        val s = settled(vm)
        assertTrue(s.error is PairingError.Unknown)
        assertFalse(s.busy)
        assertFalse(s.paired)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun formStartsOverAfterAnUnpair() {
        enqueueCreated()
        val vm = vm()
        vm.setMode(PairingMode.Code)
        vm.onNameChange("Office phone")
        vm.onCodeChange("ABCD2345")
        vm.submit()
        assertTrue(settled(vm).paired)

        runBlocking { store.clear() } // what A8's Unpair (or a 401 wipe) does
        val s = runBlocking { withTimeout(10_000) { vm.state.first { !it.paired } } }
        assertEquals(PairingMode.Choose, s.mode)
        assertEquals("", s.code)
        assertNull(s.error)
        assertEquals("Office phone", s.deviceName)
        assertEquals(base, s.serverUrl)
    }

    @Test
    fun stateToStringRedactsPassword() {
        val vm = vm()
        vm.onPasswordChange("hunter2!")
        assertFalse("hunter2!" in vm.state.value.toString())
    }
}
