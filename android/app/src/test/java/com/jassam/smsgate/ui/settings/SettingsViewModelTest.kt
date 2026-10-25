package com.jassam.smsgate.ui.settings

import androidx.lifecycle.viewModelScope
import com.jassam.smsgate.api.ApiResult
import com.jassam.smsgate.api.Sim
import com.jassam.smsgate.auth.Credentials
import com.jassam.smsgate.realtime.ConnectionState
import com.jassam.smsgate.ui.home.PhoneInfo
import com.jassam.smsgate.ui.home.SimCard
import com.jassam.smsgate.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings view-model rules with fakes (the unpair flow against a real stack is in UnpairFlowTest). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val vms = mutableListOf<SettingsViewModel>()

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private class Fixture {
        val always = MutableStateFlow(false)
        var saveOk = true
        val saved = mutableListOf<Boolean>()
        var charging = false
        var exempt = false
        val prefs = FakePrefs()
        var phone: suspend () -> PhoneInfo = { PhoneInfo(listOf(Sim(0, 11, "Orange"), Sim(1, 22, "inwi")), 22) }
        var syncs = 0
        var paired = true
        val lastSync = MutableStateFlow<Long?>(null)
        var now = 10_000L
        val connection = MutableStateFlow(ConnectionState.OFF)
    }

    private fun TestScope.vm(f: Fixture): SettingsViewModel {
        val main = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val vm = SettingsViewModel(
            SettingsDeps(
                credentials = { Credentials("https://s", "dev-1", "dt_1") },
                unregister = { ApiResult.Ok(Unit) },
                revoke = { true },
                alwaysConnected = f.always,
                setAlwaysConnected = { v -> f.saved += v; if (f.saveOk) f.always.value = v; f.saveOk },
                connection = f.connection,
                isCharging = { f.charging },
                batteryExempt = { f.exempt },
                prefs = f.prefs,
                phone = { f.phone() },
                syncNow = { f.syncs++; f.paired },
                lastSync = f.lastSync,
                now = { f.now },
                appVersion = "1.2.3",
                io = main,
            ),
        )
        vms += vm
        backgroundScope.launch(main) { vm.state.collect {} }
        return vm
    }

    @Test
    fun toggleOnSavesAndShowsTheLiveState() = runTest {
        val f = Fixture().apply { charging = true }
        val vm = vm(f)
        vm.setAlwaysConnected(true)
        f.connection.value = ConnectionState.CONNECTED
        val s = vm.state.value
        assertEquals(listOf(true), f.saved)
        assertTrue(s.alwaysConnected)
        assertEquals(ConnectionState.CONNECTED, s.connection)
        assertFalse(s.toggleBusy)
        assertFalse(s.toggleFailed)
    }

    @Test
    fun aToggleThatCannotBeSavedShowsAnError() = runTest {
        val f = Fixture().apply { saveOk = false; exempt = false }
        val vm = vm(f)
        vm.setAlwaysConnected(true)
        val s = vm.state.value
        assertTrue(s.toggleFailed)
        assertFalse(s.alwaysConnected)
        assertFalse(s.exemptionPrompt) // nothing was turned on, nothing to warn about
        vm.dismissToggleError()
        assertFalse(vm.state.value.toggleFailed)
    }

    @Test
    fun exemptionPromptMatrix() = runTest {
        // (charging, exempt) -> prompt when turning Always connected on.
        val cases = mapOf(
            (false to false) to true, // unplugged, not exempt: Doze would drop the socket
            (false to true) to false,
            (true to false) to false, // on the charger the phone does not doze
            (true to true) to false,
        )
        for ((input, expected) in cases) {
            val f = Fixture().apply { charging = input.first; exempt = input.second }
            val vm = vm(f)
            vm.setAlwaysConnected(true)
            assertEquals("charging=${input.first} exempt=${input.second}", expected, vm.state.value.exemptionPrompt)
            assertEquals(expected, SettingsViewModel.needsExemptionPrompt(input.first, input.second))
        }
    }

    @Test
    fun turningOffNeverPromptsAndClearsAPendingPrompt() = runTest {
        val f = Fixture().apply { charging = false; exempt = false }
        val vm = vm(f)
        vm.setAlwaysConnected(true)
        assertTrue(vm.state.value.exemptionPrompt)
        vm.setAlwaysConnected(false)
        assertFalse(vm.state.value.exemptionPrompt)
    }

    @Test
    fun theDozeNoteStaysWhileNotExemptAndGoesOnceExempt() = runTest {
        val f = Fixture().apply { charging = false; exempt = false }
        val vm = vm(f)
        vm.setAlwaysConnected(true)
        vm.dismissExemptionPrompt()
        assertFalse(vm.state.value.exemptionPrompt)
        assertTrue(vm.state.value.dozeWarning)
        f.exempt = true // granted in system settings
        vm.onResume()
        assertFalse(vm.state.value.dozeWarning)
    }

    @Test
    fun themeChangesArePersistedThroughThePrefs() = runTest {
        val f = Fixture()
        val vm = vm(f)
        vm.setTheme(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, f.prefs.theme.value)
        assertEquals(ThemeMode.Dark, vm.state.value.theme)
        f.prefs.fail = true
        vm.setTheme(ThemeMode.Light)
        assertTrue(vm.state.value.saveFailed)
        assertEquals(ThemeMode.Dark, vm.state.value.theme)
    }

    @Test
    fun deviceLabelIsValidatedAndBlankClears() = runTest {
        val f = Fixture()
        val vm = vm(f)
        assertTrue(vm.saveDeviceLabel("  Front desk "))
        assertEquals("Front desk", f.prefs.label.value)
        assertFalse(vm.saveDeviceLabel("x".repeat(65)))
        assertEquals("Front desk", f.prefs.label.value)
        assertTrue(vm.saveDeviceLabel(" "))
        assertEquals(null, f.prefs.label.value)
    }

    @Test
    fun defaultSimIsTheDefaultSubscriptionOrTheOnlySim() = runTest {
        assertEquals(DefaultSim.Card(SimCard(1, "inwi", null, isDefault = true)), vm(Fixture()).state.value.defaultSim)
        val single = Fixture().apply { phone = { PhoneInfo(listOf(Sim(0, 11, "Orange")), null) } }
        assertEquals(DefaultSim.Card(SimCard(0, "Orange", null, isDefault = false)), vm(single).state.value.defaultSim)
        val none = Fixture().apply { phone = { PhoneInfo(listOf(Sim(0, 11, "A"), Sim(1, 22, "B")), null) } }
        assertEquals(DefaultSim.None, vm(none).state.value.defaultSim)
        val denied = Fixture().apply { phone = { PhoneInfo(null, 11) } }
        assertEquals(DefaultSim.NoPermission, vm(denied).state.value.defaultSim)
    }

    @Test
    fun syncFeedbackShowsDoneBrieflyOnceTheSyncLands() = runTest {
        val f = Fixture()
        val vm = vm(f)
        vm.syncNow()
        assertEquals(1, f.syncs)
        assertEquals(SyncFeedback.Requested, vm.state.value.sync)
        f.lastSync.value = 9_000 // an older sync does not count
        assertEquals(SyncFeedback.Requested, vm.state.value.sync)
        f.lastSync.value = 12_000
        assertEquals(SyncFeedback.Done, vm.state.value.sync)
        advanceTimeBy(SettingsViewModel.SYNC_DONE_SHOW_MS + 1)
        assertEquals(SyncFeedback.None, vm.state.value.sync)
        assertEquals(12_000L, vm.state.value.lastSyncMs)
    }

    @Test
    fun syncingNeverShowsForever() = runTest {
        val f = Fixture()
        val vm = vm(f)
        vm.syncNow()
        assertEquals(SyncFeedback.Requested, vm.state.value.sync)
        advanceTimeBy(SettingsViewModel.SYNC_WAIT_MS - 1)
        assertEquals(SyncFeedback.Requested, vm.state.value.sync)
        advanceTimeBy(2)
        assertEquals(SyncFeedback.None, vm.state.value.sync) // back to "Last sync …"
    }

    @Test
    fun noFeedbackWhenNothingWasEnqueued() = runTest {
        val f = Fixture().apply { paired = false }
        val vm = vm(f)
        vm.syncNow()
        assertEquals(SyncFeedback.None, vm.state.value.sync)
    }

    @Test
    fun aSecondTapRestartsTheWait() = runTest {
        val f = Fixture()
        val vm = vm(f)
        vm.syncNow()
        advanceTimeBy(SettingsViewModel.SYNC_WAIT_MS - 10)
        vm.syncNow()
        advanceTimeBy(20)
        assertEquals(SyncFeedback.Requested, vm.state.value.sync)
        assertEquals(2, f.syncs)
    }

    @Test
    fun unpairDialogTransitions() = runTest {
        val vm = vm(Fixture())
        vm.confirmUnpair() // not confirmed yet: ignored
        assertEquals(UnpairState.Idle, vm.state.value.unpair)
        vm.requestUnpair()
        assertEquals(UnpairState.Confirm, vm.state.value.unpair)
        vm.cancelUnpair()
        assertEquals(UnpairState.Idle, vm.state.value.unpair)
        vm.confirmLocalRemoval() // not confirmed: ignored
        assertEquals(UnpairState.Idle, vm.state.value.unpair)
        vm.requestLocalRemoval()
        assertEquals(UnpairState.ConfirmLocal, vm.state.value.unpair)
    }
}
