package com.jassam.smsgate.realtime

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Intent
import android.os.BatteryManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectivityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: ConnectionPrefs
    private val service = FakeServiceControl()
    private var paired = true
    private var charging = false
    private var armed = 0
    private val logs = mutableListOf<String>()
    private lateinit var connectivity: Connectivity

    class FakeServiceControl : ServiceControl {
        var starts = 0
        var stops = 0
        var failStart: RuntimeException? = null

        override fun start() {
            failStart?.let { throw it }
            starts++
        }

        override fun stop() {
            stops++
        }
    }

    @Before
    fun setUp() {
        prefs = ConnectionPrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "c.preferences_pb") }))
        connectivity = Connectivity(prefs, { paired }, { charging }, service, { armed++ }, { logs += it })
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun evaluateStartsOrStopsTheServiceByPolicy() = runBlocking {
        data class Case(val paired: Boolean, val charging: Boolean, val always: Boolean, val connect: Boolean, val arms: Boolean)
        val cases = listOf(
            Case(paired = true, charging = true, always = false, connect = true, arms = false),
            Case(paired = true, charging = false, always = true, connect = true, arms = false),
            Case(paired = true, charging = false, always = false, connect = false, arms = true),
            Case(paired = false, charging = true, always = true, connect = false, arms = false),
            Case(paired = false, charging = false, always = false, connect = false, arms = false),
        )
        for (c in cases) {
            service.starts = 0
            service.stops = 0
            armed = 0
            paired = c.paired
            charging = c.charging
            prefs.setAlwaysConnected(c.always)
            assertEquals("$c", c.connect, connectivity.evaluate())
            assertEquals("$c starts", if (c.connect) 1 else 0, service.starts)
            assertEquals("$c stops", if (c.connect) 0 else 1, service.stops)
            assertEquals("$c arms the charging trigger", if (c.arms) 1 else 0, armed)
        }
    }

    @Test
    fun aRefusedBackgroundStartIsSwallowed() = runBlocking {
        charging = true
        service.failStart = ForegroundServiceStartNotAllowedException("app is in the background")
        assertTrue(connectivity.evaluate()) // no throw
        assertTrue(logs.any { "ForegroundServiceStartNotAllowedException" in it })
        service.failStart = SecurityException("no")
        assertTrue(connectivity.evaluate())
    }

    @Test
    fun aRunningServiceIsNotStartedAgain() = runBlocking {
        charging = true
        connectivity.serviceRunning = true
        assertTrue(connectivity.evaluate())
        assertEquals(0, service.starts)
    }

    @Test
    fun setAlwaysConnectedPersistsAndAppliesAtOnce() = runBlocking {
        assertTrue(connectivity.setAlwaysConnected(true))
        assertTrue(connectivity.alwaysConnected.first())
        assertEquals(1, service.starts)
        assertTrue(connectivity.setAlwaysConnected(false))
        assertFalse(prefs.alwaysConnected())
        assertEquals(1, service.stops)
        assertEquals(1, armed)
    }

    @Test
    fun stopNeverThrows() {
        val throwing = Connectivity(prefs, { true }, { true }, object : ServiceControl {
            override fun start() = Unit
            override fun stop() = throw IllegalStateException("x")
        }, {}, {})
        throwing.stop()
    }

    @Test
    fun androidServiceControlStartsTheServiceAndStopsItByRequestNotStopService() {
        val control = AndroidServiceControl(app)
        control.stop()
        assertTrue(ConnectivityService.stopRequested)
        control.start() // a new start clears an old request
        assertFalse(ConnectivityService.stopRequested)
        assertEquals(ComponentName(app, ConnectivityService::class.java), shadowOf(app).nextStartedService.component)
        control.stop()
        assertTrue(ConnectivityService.stopRequested)
        assertNull(shadowOf(app).nextStoppedService) // never stopService (crash between startForegroundService and startForeground)
        ConnectivityService.clearStopRequest()
    }

    @Test
    fun aQuickOffOnToggleEndsWithTheServiceRunning() = runBlocking {
        charging = false
        connectivity.serviceRunning = true // the service is still shutting down after "off"
        assertTrue(connectivity.setAlwaysConnected(true))
        assertEquals(0, service.starts) // skipped: it looked like it was running
        connectivity.serviceRunning = false // onRunning(false), synchronously in onDestroy
        connectivity.onServiceDestroyed(reevaluate = true)
        assertEquals(1, service.starts)
    }

    @Test
    fun aLateDestroyOfAnOldInstanceLeavesTheNewInstancesStateAlone() = runBlocking {
        charging = true
        // The old instance's onDestroy ran (running=false), a new instance started and
        // reports, then the old instance's queued re-evaluation arrives.
        connectivity.serviceRunning = true
        connectivity.report(ConnectionState.CONNECTED)
        connectivity.onServiceDestroyed(reevaluate = true)
        assertTrue(connectivity.serviceRunning)
        assertEquals(ConnectionState.CONNECTED, connectivity.state.value)
        assertEquals(0, service.starts)
    }

    @Test
    fun aServiceThatStoppedForAReasonARestartWouldRepeatIsNotRestarted() = runBlocking {
        charging = true
        connectivity.serviceRunning = false
        connectivity.onServiceDestroyed(reevaluate = false)
        assertEquals(0, service.starts)
    }

    @Test
    fun aFailingSocketRevocationNeverReachesTheScopeHandler() = runBlocking {
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })
        val tokens = mutableListOf<String>()
        scope.launchRevocation("dt_1", { tokens += it; throw IllegalStateException("datastore broke") }, { logs += it }).join()
        assertEquals(listOf("dt_1"), tokens)
        assertTrue(uncaught.isEmpty())
        assertTrue(logs.any { it.contains("IllegalStateException") })
        assertFalse(logs.any { it.contains("datastore broke") || it.contains("dt_1") })
        scope.cancel()
    }

    @Test
    fun powerStateReadsThePluggedExtraOfTheStickyBatteryBroadcast() {
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB))
        assertTrue(PowerState.isPluggedIn(app))
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 0))
        assertFalse(PowerState.isPluggedIn(app))
    }
}
