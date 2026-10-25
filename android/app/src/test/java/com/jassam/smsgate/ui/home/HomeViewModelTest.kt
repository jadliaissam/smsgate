package com.jassam.smsgate.ui.home

import androidx.lifecycle.viewModelScope
import com.jassam.smsgate.api.ApiResult
import com.jassam.smsgate.api.DeviceMe
import com.jassam.smsgate.api.Sim
import com.jassam.smsgate.auth.Credentials
import com.jassam.smsgate.journal.DayCounts
import com.jassam.smsgate.realtime.ConnectionState
import com.jassam.smsgate.ui.onboarding.OnboardingSnapshot
import com.jassam.smsgate.ui.onboarding.PermissionState
import java.io.IOException
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val creds = Credentials("https://sms.example.com", "dev-1", "dt_1")
    private val zone = TimeZone.getTimeZone("Africa/Casablanca")
    private val vms = mutableListOf<HomeViewModel>()

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private class MemoryMeCache(var stored: Pair<String, AccountInfo>? = null) : MeCache {
        override suspend fun get(deviceId: String) = stored?.takeIf { it.first == deviceId }?.second
        override suspend fun put(deviceId: String, info: AccountInfo) {
            stored = deviceId to info
        }

        override suspend fun clear(deviceId: String) {
            if (stored?.first == deviceId) stored = null
        }
    }

    private class Fixture {
        var credentials: Credentials? = null
        var me: suspend (Credentials) -> ApiResult<DeviceMe> = { ApiResult.NetworkError(IOException("offline")) }
        val revoked = mutableListOf<String>()
        val cache = MemoryMeCache()
        val lastSync = MutableStateFlow<Long?>(null)
        val connection = MutableStateFlow(ConnectionState.OFF)
        val always = MutableStateFlow(false)
        val label = MutableStateFlow<String?>(null)
        val windows = mutableListOf<DayWindow>()
        var counts: (DayWindow) -> Flow<DayCounts> = { flowOf(DayCounts.ZERO) }
        var phone: suspend () -> PhoneInfo = { PhoneInfo(listOf(Sim(0, 11, "Orange")), 11) }
        var push: suspend () -> PushStatus = { PushStatus.Ok }
        var meCalls = 0
        var pushCalls = 0
    }

    private fun TestScope.vm(f: Fixture, start: Long = at(2026, 10, 25, 12)): HomeViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val deps = HomeDeps(
            serverHost = "sms.example.com",
            credentials = { f.credentials },
            me = { f.meCalls++; f.me(it) },
            revoke = { f.revoked += it },
            meCache = f.cache,
            lastSync = f.lastSync,
            connection = f.connection,
            alwaysConnected = f.always,
            deviceLabel = f.label,
            counts = { w -> f.windows += w; f.counts(w) },
            now = { start + testScheduler.currentTime },
            zone = { zone },
            phone = { f.phone() },
            push = { f.pushCalls++; f.push() },
        )
        val vm = HomeViewModel(deps)
        vms += vm
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        GregorianCalendar(zone).apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }.timeInMillis

    private fun okMe(name: String = "Pixel 8", email: String = "dev@example.com") =
        ApiResult.Ok(DeviceMe("dev-1", name, email, Instant.parse("2026-10-25T12:00:00Z")))

    @Test
    fun freshAccountIsShownAndCached() = runTest {
        val f = Fixture().apply { credentials = creds; me = { okMe() } }
        val vm = vm(f)
        val s = vm.state.value
        assertEquals(AccountInfo("Pixel 8", "dev@example.com"), s.account)
        assertEquals(AccountStatus.Fresh, s.accountStatus)
        assertEquals("dev-1" to AccountInfo("Pixel 8", "dev@example.com"), f.cache.stored)
        assertEquals("sms.example.com", s.serverHost)
    }

    @Test
    fun offlineStillRendersFromTheCache() = runTest {
        val f = Fixture().apply {
            credentials = creds
            cache.stored = "dev-1" to AccountInfo("Old name", "dev@example.com")
        }
        val vm = vm(f)
        val s = vm.state.value
        assertEquals(AccountInfo("Old name", "dev@example.com"), s.account)
        assertEquals(AccountStatus.Offline, s.accountStatus)
        assertTrue(f.revoked.isEmpty())
        // Everything else renders regardless.
        assertEquals(SimsInfo.Known(listOf(SimCard(0, "Orange", null, isDefault = true))), s.sims)
        assertEquals(PushStatus.Ok, s.push)
    }

    @Test
    fun aCacheForAnotherDeviceIsIgnored() = runTest {
        val f = Fixture().apply {
            credentials = creds
            cache.stored = "dev-OLD" to AccountInfo("Old phone", "old@example.com")
        }
        assertNull(vm(f).state.value.account)
    }

    @Test
    fun aServerErrorIsAnErrorStateNotARevocation() = runTest {
        val f = Fixture().apply { credentials = creds; me = { ApiResult.HttpError(500, "internal", "boom") } }
        val vm = vm(f)
        assertEquals(AccountStatus.Failed, vm.state.value.accountStatus)
        assertTrue(f.revoked.isEmpty())
    }

    @Test
    fun aBare401IsJustAnError() = runTest {
        val f = Fixture().apply { credentials = creds; me = { ApiResult.HttpError(401, "http_401", "HTTP 401") } }
        val vm = vm(f)
        assertEquals(AccountStatus.Failed, vm.state.value.accountStatus)
        assertTrue(f.revoked.isEmpty())
    }

    @Test
    fun aBackend401RevokesWithTheTokenUsed() = runTest {
        val f = Fixture().apply { credentials = creds; me = { ApiResult.HttpError(401, "unauthorized", "revoked") } }
        vm(f)
        assertEquals(listOf("dt_1"), f.revoked)
    }

    @Test
    fun aThrowingCallIsAnErrorNotACrash() = runTest {
        val f = Fixture().apply { credentials = creds; me = { throw IllegalStateException("bug") } }
        assertEquals(AccountStatus.Failed, vm(f).state.value.accountStatus)
    }

    @Test
    fun theUiNeverWaitsForTheAccount() = runTest {
        val gate = CompletableDeferred<ApiResult<DeviceMe>>()
        val f = Fixture().apply { credentials = creds; me = { gate.await() }; lastSync.value = 42L }
        val vm = vm(f)
        assertEquals(AccountStatus.Loading, vm.state.value.accountStatus)
        assertEquals(42L, vm.state.value.lastSyncMs)
        gate.complete(okMe())
        runCurrent()
        assertEquals(AccountStatus.Fresh, vm.state.value.accountStatus)
    }

    @Test
    fun retryRefetchesAfterAFailure() = runTest {
        val f = Fixture().apply { credentials = creds }
        val vm = vm(f)
        assertEquals(AccountStatus.Offline, vm.state.value.accountStatus)
        f.me = { okMe() }
        vm.refresh()
        assertEquals(AccountStatus.Fresh, vm.state.value.accountStatus)
    }

    @Test
    fun resumeRefetchesOnlyWhenStaleOrFailed() = runTest {
        val f = Fixture().apply { credentials = creds; me = { okMe() } }
        val vm = vm(f)
        assertEquals(1, f.meCalls)
        vm.onResume()
        assertEquals(1, f.meCalls)
        advanceTimeBy(HomeViewModel.ACCOUNT_MAX_AGE_MS + 1)
        vm.onResume()
        assertEquals(2, f.meCalls)
    }

    @Test
    fun liveSourcesFlowIntoTheState() = runTest {
        val f = Fixture().apply { credentials = creds; me = { okMe() } }
        val vm = vm(f)
        f.connection.value = ConnectionState.CONNECTED
        f.always.value = true
        f.label.value = "Front desk"
        f.lastSync.value = 1234L
        val s = vm.state.value
        assertEquals(ConnectionState.CONNECTED, s.connection)
        assertTrue(s.alwaysConnected)
        assertEquals("Front desk", s.deviceLabel)
        assertEquals(1234L, s.lastSyncMs)
    }

    @Test
    fun noPlayServicesIsKnownFromThePushCheck() = runTest {
        val f = Fixture().apply { credentials = creds; push = { PushStatus.NoPlayServices } }
        val vm = vm(f)
        assertEquals(false, vm.state.value.playServices)
    }

    @Test
    fun simsWithoutPermissionAndFailingSourcesStillRender() = runTest {
        val f = Fixture().apply {
            credentials = creds
            phone = { PhoneInfo(null, null) }
            push = { throw RuntimeException("play services") }
        }
        val s = vm(f).state.value
        assertEquals(SimsInfo.NoPermission, s.sims)
        assertEquals(PushStatus.NotRegistered, s.push)
    }

    @Test
    fun permissionsBecomeWarnings() = runTest {
        val f = Fixture().apply { credentials = creds }
        val vm = vm(f)
        val ok = PermissionState(true, true, false)
        vm.onPermissions(OnboardingSnapshot(34, PermissionState(false, false, false), ok, ok, batteryExempt = false, oem = null, oemAcknowledged = false), true)
        assertEquals(listOf(PermissionWarning(WarningKind.Sms), PermissionWarning(WarningKind.Battery)), vm.state.value.warnings)
    }

    @Test
    fun todaysCountsFollowTheLocalDayAndSwitchAtMidnight() = runTest {
        val f = Fixture().apply {
            credentials = creds
            counts = { w -> flowOf(w).map { if (it.startMs == at(2026, 10, 25, 0)) DayCounts(5, 4, 1) else DayCounts.ZERO } }
        }
        val vm = vm(f, start = at(2026, 10, 25, 23, 59))
        assertEquals(DayWindow(at(2026, 10, 25, 0), at(2026, 10, 26, 0)), f.windows.first())
        assertEquals(DayCounts(5, 4, 1), vm.state.value.today)
        advanceTimeBy(60_000 + 1)
        assertEquals(DayWindow(at(2026, 10, 26, 0), at(2026, 10, 27, 0)), f.windows.last())
        assertEquals(DayCounts.ZERO, vm.state.value.today)
    }

    @Test
    fun aFailingCountsQueryShowsZeros() = runTest {
        val f = Fixture().apply { credentials = creds; counts = { kotlinx.coroutines.flow.flow { throw IllegalStateException("db") } } }
        assertEquals(DayCounts.ZERO, vm(f).state.value.today)
    }

    @Test
    fun pushStatusIsRecheckedAfterEachSync() = runTest {
        val f = Fixture().apply { credentials = creds; push = { PushStatus.NotRegistered } }
        val vm = vm(f)
        assertEquals(PushStatus.NotRegistered, vm.state.value.push)
        assertEquals(1, f.pushCalls)
        f.push = { PushStatus.Ok } // the sync sent the token
        f.lastSync.value = 5_000L
        assertEquals(PushStatus.Ok, vm.state.value.push)
        assertEquals(2, f.pushCalls)
        f.lastSync.value = 5_000L // unchanged: no new check
        assertEquals(2, f.pushCalls)
    }

    @Test
    fun aSyncRestartsAPushCheckAlreadyInFlight() = runTest {
        val slow = CompletableDeferred<PushStatus>()
        val f = Fixture().apply { credentials = creds; push = { slow.await() } }
        val vm = vm(f)
        assertEquals(PushStatus.Checking, vm.state.value.push)
        f.push = { PushStatus.Ok }
        f.lastSync.value = 1L
        assertEquals(PushStatus.Ok, vm.state.value.push)
        slow.complete(PushStatus.NotRegistered) // the stale check was cancelled
        runCurrent()
        assertEquals(PushStatus.Ok, vm.state.value.push)
    }
}
