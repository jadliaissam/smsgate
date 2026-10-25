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
import kotlinx.coroutines.CompletableDeferred
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
    private val destroyed = mutableListOf<Boolean>()
    private var credentialsGate: CompletableDeferred<Unit>? = null
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
            credentials = { credentialsGate?.await(); credentials },
            isCharging = { charging },
            newClient = { c, report -> FakeClient(c, report).also { clients += it } },
            onState = { states += it },
            onRunning = { running += it },
            onDestroyed = { destroyed += it },
            onUnpluggedStop = { unpluggedStops++ },
        )
        ConnectivityService.clearStopRequest()
        controller = Robolectric.buildService(ConnectivityService::class.java)
    }

    @After
    fun tearDown() {
        ConnectivityService.depsOverride = null
        ConnectivityService.clearStopRequest()
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

    private fun stoppedBySelf(s: ConnectivityService) = shadowOf(s).isStoppedBySelf || shadowOf(s).stopSelfId != 0

    private fun assertStopped(s: ConnectivityService) {
        assertTrue("service stopped itself", stoppedBySelf(s))
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
        assertFalse(stoppedBySelf(s))
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
        assertFalse(stoppedBySelf(s))

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
        assertFalse(stoppedBySelf(s))
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
        assertTrue(stoppedBySelf(service))
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

    @Test
    fun aPolicyStopAsksForAReevaluationOnceDestroyed() {
        start()
        broadcast(Intent.ACTION_POWER_DISCONNECTED)
        controller.destroy()
        assertEquals(listOf(true), destroyed)
    }

    @Test
    fun aRefusedForegroundStartDoesNotAskForAReevaluation() {
        controller.create()
        shadowOf(service).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("background"))
        service.onStartCommand(Intent(app, ConnectivityService::class.java), 0, 1)
        controller.destroy()
        assertEquals(listOf(false), destroyed)
    }

    @Test
    fun whenItsClientStopsItselfTheServiceStopsWithoutReevaluating() {
        val s = start()
        clients.single().report(ConnectionState.OFF) // revoked, or an unusable server URL
        idle()
        assertStopped(s)
        assertEquals(ConnectionState.OFF, states.last())
        controller.destroy()
        assertEquals(listOf(false), destroyed)
    }

    @Test
    fun aStopRequestWhileRunningStopsAndReevaluates() {
        val s = start()
        ConnectivityService.requestStop()
        idle()
        assertStopped(s)
        controller.destroy()
        assertEquals(listOf(true), destroyed)
    }

    @Test
    fun aStopRequestedBeforeTheServiceRanStopsItRightAfterStartForeground() {
        // startForegroundService, then a stop before onStartCommand: stopService here would
        // crash the app ("did not then call startForeground"); the request waits instead.
        ConnectivityService.requestStop()
        controller.create()
        idle()
        assertFalse(stoppedBySelf(service))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(app, ConnectivityService::class.java), 0, 1))
        idle()
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(stoppedBySelf(service))
        assertTrue(clients.isEmpty())
    }

    @Test
    fun aStartQueuedAfterAPolicyStopBeginsAFreshWatch() {
        val s = start()
        broadcast(Intent.ACTION_POWER_DISCONNECTED)
        assertStopped(s)
        broadcast(Intent.ACTION_POWER_CONNECTED) // plugged in again before the service was destroyed
        controller.startCommand(0, 2)
        idle()
        assertEquals(2, clients.size)
        assertEquals(1, clients.last().starts)
        assertEquals(0, clients.last().stops)
    }

    @Test
    fun destroyedWhileStillLoadingCredentialsCreatesNoClient() {
        credentialsGate = CompletableDeferred()
        start()
        controller.destroy()
        credentialsGate!!.complete(Unit)
        idle()
        assertTrue(clients.isEmpty())
        assertEquals(listOf(true, false), running)
    }

    @Test
    fun aStartQueuedAfterAStopRequestConnectsAgain() {
        val s = start()
        ConnectivityService.requestStop()
        idle()
        assertStopped(s)
        // A new start (AndroidServiceControl.start clears the request) lands before onDestroy.
        ConnectivityService.clearStopRequest()
        controller.startCommand(0, 2)
        idle()
        assertEquals(2, clients.size)
        assertEquals(1, clients.last().starts)
        assertEquals(0, clients.last().stops)
    }
}
