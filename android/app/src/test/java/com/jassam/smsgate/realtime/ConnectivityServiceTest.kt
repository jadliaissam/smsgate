package com.jassam.smsgate.realtime

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Looper
import com.jassam.smsgate.auth.Credentials
import com.jassam.smsgate.auth.PairingState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectivityServiceTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val first = Credentials("https://sms.example.com", "dev-1", "dt_1")
    private val second = Credentials("https://sms.example.com", "dev-2", "dt_2")

    private val pairing = MutableStateFlow<PairingState>(PairingState.Paired(first.serverUrl, first.deviceId))
    private val always = MutableStateFlow(false)
    private var credentials: Credentials? = first
    private var charging = true
    private val clients = mutableListOf<FakeClient>()
    private val states = mutableListOf<ConnectionState>()
    private val running = mutableListOf<Boolean>()
    private var unpluggedStops = 0
    private lateinit var controller: ServiceController<ConnectivityService>

    class FakeClient(val credentials: Credentials, val report: (ConnectionState) -> Unit) : RealtimeClient {
        var starts = 0
        var stops = 0
        var reconnects = 0
        override fun start() {
            starts++
        }
        override fun stop() {
            stops++
        }
        override fun reconnectNow() {
            reconnects++
        }
    }

    @Before
    fun setUp() {
        ConnectivityService.depsOverride = ConnectivityService.Deps(
            pairing = pairing,
            alwaysConnected = always,
            credentials = { credentials },
            isCharging = { charging },
            newClient = { c, report -> FakeClient(c, report).also { clients += it } },
            onState = { states += it },
            onRunning = { running += it },
            onUnpluggedStop = { unpluggedStops++ },
        )
        controller = Robolectric.buildService(ConnectivityService::class.java)
    }

    @After
    fun tearDown() {
        ConnectivityService.depsOverride = null
    }

    private val service: ConnectivityService get() = controller.get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun start(): ConnectivityService {
        controller.create().startCommand(0, 1)
        idle()
        return service
    }

    private fun broadcast(action: String) {
        app.sendBroadcast(Intent(action))
        idle()
    }

    private fun assertStopped(s: ConnectivityService) {
        assertTrue("service stopped itself", shadowOf(s).isStoppedBySelf)
        assertTrue(clients.all { it.stops == it.starts })
    }

    @Test
    fun startsInTheForegroundWithTheQuietNotificationAndConnects() {
        val s = start()
        assertEquals(ConnectivityNotification.NOTIFICATION_ID, shadowOf(s).lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, s.foregroundServiceType)
        val n = shadowOf(s).lastForegroundNotification
        assertEquals(ConnectivityNotification.CHANNEL_ID, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(first, clients.single().credentials)
        assertEquals(1, clients.single().starts)
        assertFalse(shadowOf(s).isStoppedBySelf)
        assertEquals(listOf(true), running)
    }

    @Test
    @Config(sdk = [30])
    fun belowApi34ItIsAPlainForegroundService() {
        val s = start()
        assertEquals(ConnectivityNotification.NOTIFICATION_ID, shadowOf(s).lastForegroundNotificationId)
        assertEquals(1, clients.single().starts)
    }

    @Test
    fun powerDisconnectedStopsTheServiceAndArmsTheChargingTrigger() {
        val s = start()
        broadcast(Intent.ACTION_POWER_DISCONNECTED)
        assertEquals(1, clients.single().stops)
        assertStopped(s)
        assertEquals(1, unpluggedStops)
        assertEquals(ConnectionState.OFF, states.last())
    }

    @Test
    fun withAlwaysConnectedPowerChangesDontMatterButTurningItOffStops() {
        charging = false
        always.value = true
        val s = start()
        assertEquals(1, clients.single().starts)
        broadcast(Intent.ACTION_POWER_CONNECTED)
        broadcast(Intent.ACTION_POWER_DISCONNECTED)
        assertEquals(1, clients.size)
        assertEquals(0, clients.single().stops)
        assertFalse(shadowOf(s).isStoppedBySelf)

        always.value = false // the settings toggle
        idle()
        assertStopped(s)
        assertEquals(1, unpluggedStops)
    }

    @Test
    fun turningTheSettingOnWhileChargingKeepsTheSameSocket() {
        val s = start()
        always.value = true
        idle()
        assertEquals(1, clients.size)
        assertEquals(0, clients.single().stops)
        assertFalse(shadowOf(s).isStoppedBySelf)
    }

    @Test
    fun unpairingStopsTheService() {
        val s = start()
        credentials = null
        pairing.value = PairingState.Unpaired
        idle()
        assertStopped(s)
        assertEquals(0, unpluggedStops)
    }

    @Test
    fun rePairingSwapsTheClientAndDropsTheOldOnesLateStates() {
        start()
        val old = clients.single()
        credentials = second
        pairing.value = PairingState.Paired(second.serverUrl, second.deviceId)
        idle()
        assertEquals(1, old.stops)
        assertEquals(second, clients.last().credentials)
        assertEquals(1, clients.last().starts)
        states.clear()
        old.report(ConnectionState.CONNECTED) // late report from the replaced socket
        clients.last().report(ConnectionState.CONNECTING)
        assertEquals(listOf(ConnectionState.CONNECTING), states)
    }

    @Test
    fun startedWhenNotNeededItStopsAtOnceAfterEnteringTheForeground() {
        charging = false
        val s = start()
        assertNotNull("startForeground is always called first", shadowOf(s).lastForegroundNotification)
        assertTrue(clients.isEmpty())
        assertStopped(s)
    }

    @Test
    fun pairedButCredentialsUnreadableStops() {
        credentials = null
        val s = start()
        assertTrue(clients.isEmpty())
        assertStopped(s)
    }

    @Test
    fun anotherStartNudgesAReconnect() {
        start()
        controller.startCommand(0, 2)
        idle()
        assertEquals(1, clients.size)
        assertEquals(1, clients.single().reconnects)
    }

    @Test
    fun networkAvailableReconnectsAtOnce() {
        start()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val callbacks = shadowOf(cm).networkCallbacks
        assertEquals(1, callbacks.size)
        callbacks.single().onAvailable(ShadowNetwork.newInstance(7))
        assertEquals(1, clients.single().reconnects)
    }

    @Test
    fun aRefusedForegroundStartStopsWithoutCrashing() {
        controller.create()
        shadowOf(service).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("background"))
        val result = service.onStartCommand(Intent(app, ConnectivityService::class.java), 0, 1)
        idle()
        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(clients.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun aStickyRestartReChecksThePolicy() {
        controller.create()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        idle()
        assertEquals(1, clients.size)
    }

    @Test
    fun destroyStopsTheSocketAndUnregisters() {
        start()
        controller.destroy()
        assertEquals(1, clients.single().stops)
        assertEquals(listOf(true, false), running)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        assertTrue(shadowOf(cm).networkCallbacks.isEmpty())
        assertTrue(shadowOf(app).registeredReceivers.none { it.broadcastReceiver.javaClass.name.startsWith(ConnectivityService::class.java.name) })
    }
}
