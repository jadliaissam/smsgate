package com.jassam.smsgate.realtime

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.annotation.VisibleForTesting
import com.jassam.smsgate.appGraph
import com.jassam.smsgate.auth.Credentials
import com.jassam.smsgate.auth.PairingState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Holds the always-connected WebSocket ([SocketClient]) while [ConnectionPolicy] says
 * so: a foreground service (type `specialUse` on API 34+, plain below; `dataSync` would
 * be capped at 6 h a day on Android 15) with a quiet ongoing notification.
 *
 * While running it watches everything the policy depends on and stops itself when the
 * answer turns to "disconnect": the pairing state (unpaired or revoked → stop; re-paired
 * → a new client for the new credentials), the "Always connected" setting, and power
 * (a runtime receiver for ACTION_POWER_CONNECTED / DISCONNECTED). A default-network
 * callback retries at once when a network becomes available.
 *
 * Every start calls startForeground first (required after startForegroundService); a
 * refused startForeground (background start on API 31+) is logged and the service stops.
 * START_STICKY: a restart by the system re-checks policy and credentials through the
 * same watch and stops if the socket isn't needed.
 */
class ConnectivityService : Service() {
    /** What the service needs (built by AppGraph; tests pass fakes through [depsOverride]). */
    class Deps(
        val pairing: Flow<PairingState>,
        val alwaysConnected: Flow<Boolean>,
        val credentials: suspend () -> Credentials?,
        val isCharging: () -> Boolean,
        /** A client for these credentials, reporting its state to the given callback. */
        val newClient: (Credentials, (ConnectionState) -> Unit) -> RealtimeClient,
        /** The current client's state (a replaced client's late reports are dropped). */
        val onState: (ConnectionState) -> Unit = {},
        /** Running state for [Connectivity.serviceRunning]. */
        val onRunning: (Boolean) -> Unit = {},
        /** The policy stopped the service while paired, unplugged and "Always connected" off. */
        val onUnpluggedStop: () -> Unit = {},
        val log: (String) -> Unit = {},
    )

    private lateinit var deps: Deps
    private var scope: CoroutineScope? = null
    private val charging = MutableStateFlow(false)

    @Volatile
    private var client: RealtimeClient? = null
    private var clientFor: Credentials? = null
    private var powerReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        deps = depsOverride ?: applicationContext.appGraph.connectivityServiceDeps()
        deps.onRunning(true)
        charging.value = deps.isCharging()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> charging.value = true
                    Intent.ACTION_POWER_DISCONNECTED -> charging.value = false
                }
            }
        }
        // Protected system broadcasts only, so no export flag is needed (or allowed to matter).
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
        powerReceiver = receiver
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (scope == null) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = s
            s.launch { watch() }
            registerNetworkCallback()
        } else {
            client?.reconnectNow()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        stopClient()
        powerReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        powerReceiver = null
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        deps.onRunning(false)
        super.onDestroy()
    }

    private suspend fun watch() {
        try {
            combine(deps.pairing, deps.alwaysConnected, charging) { pairing, always, plugged -> Triple(pairing, always, plugged) }
                .distinctUntilChanged()
                .collect { (pairing, always, plugged) -> apply(pairing as? PairingState.Paired, always, plugged) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deps.log("connectivity watch failed: ${e.javaClass.simpleName}")
            finish(unpluggedStop = false)
        }
    }

    private suspend fun apply(paired: PairingState.Paired?, always: Boolean, plugged: Boolean) {
        if (!ConnectionPolicy.shouldConnect(paired != null, plugged, always)) {
            finish(unpluggedStop = paired != null && !always && !plugged)
            return
        }
        val credentials = deps.credentials()
        if (credentials == null) {
            finish(unpluggedStop = false)
            return
        }
        if (client != null && credentials == clientFor) return
        stopClient()
        lateinit var created: RealtimeClient
        created = deps.newClient(credentials) { state -> if (client === created) deps.onState(state) }
        clientFor = credentials
        client = created
        created.start()
    }

    private fun finish(unpluggedStop: Boolean) {
        stopClient()
        if (unpluggedStop) deps.onUnpluggedStop()
        stopSelf()
    }

    private fun stopClient() {
        val c = client ?: return
        client = null
        clientFor = null
        c.stop()
        deps.onState(ConnectionState.OFF)
    }

    private fun enterForeground(): Boolean = try {
        val notification = ConnectivityNotification.build(this)
        if (Build.VERSION.SDK_INT >= 34) {
            // specialUse: required type on API 34+ (declared in the manifest with its subtype).
            startForeground(ConnectivityNotification.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ConnectivityNotification.NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (API 31+), or a missing permission.
        deps.log("foreground start refused: ${e.javaClass.simpleName}")
        false
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                client?.reconnectNow()
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            deps.log("network callback not registered: ${e.javaClass.simpleName}")
        }
    }

    internal companion object {
        /** Tests replace the AppGraph dependencies. */
        @VisibleForTesting
        @Volatile
        var depsOverride: Deps? = null
    }
}
