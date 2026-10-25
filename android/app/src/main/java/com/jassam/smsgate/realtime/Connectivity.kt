package com.jassam.smsgate.realtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Starts and stops [ConnectivityService] (a seam for tests). */
interface ServiceControl {
    /** May throw: `ForegroundServiceStartNotAllowedException` from the background on API 31+. */
    fun start()

    fun stop()
}

/** [ServiceControl] for the real service. */
class AndroidServiceControl(context: Context) : ServiceControl {
    private val app = context.applicationContext

    override fun start() {
        ConnectivityService.clearStopRequest()
        ContextCompat.startForegroundService(app, Intent(app, ConnectivityService::class.java))
    }

    /** A stop request the service honours once in the foreground (see [ConnectivityService.requestStop]). */
    override fun stop() {
        ConnectivityService.requestStop()
    }
}

/**
 * Runs a socket revocation on [this] scope (fire and forget from the socket thread).
 * Never lets an exception escape into the scope, so a failing revocation can't crash
 * the process (and loop under START_STICKY); only the exception class is logged.
 */
fun CoroutineScope.launchRevocation(token: String, revoke: suspend (String) -> Unit, log: (String) -> Unit): Job = launch {
    try {
        revoke(token)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("socket revocation failed: ${e.javaClass.simpleName}")
    }
}

/** Whether the phone is on external power (AC, USB, wireless or dock), from the sticky battery broadcast. */
object PowerState {
    fun isPluggedIn(context: Context): Boolean = try {
        // A null receiver only reads the sticky ACTION_BATTERY_CHANGED; nothing is registered.
        val battery = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    } catch (_: RuntimeException) {
        false
    }
}

/**
 * The always-connected mode, app-wide (Task A8 builds the settings toggle on this):
 * [alwaysConnected] / [setAlwaysConnected] for the setting, [state] for the live
 * connection state, [evaluate] to apply [ConnectionPolicy] now.
 *
 * "Charging" means plugged into power ([PowerState]): a full battery on the charger
 * still counts. Starting the foreground service from the background can be refused
 * (API 31+, unless the app is exempt from battery optimisation, D12); that is logged
 * and the phone carries on with FCM + periodic sync. Nothing here throws.
 */
class Connectivity(
    private val prefs: ConnectionPrefs,
    private val isPaired: suspend () -> Boolean,
    private val isCharging: () -> Boolean,
    private val service: ServiceControl,
    /** Arms a one-off job that runs when the phone is next plugged in (and evaluates again). */
    private val connectWhenCharging: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(ConnectionState.OFF)

    /** What the socket is doing right now (OFF while the service is not running). */
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Whether the service is running in this process (set by the service itself). */
    @Volatile
    var serviceRunning: Boolean = false
        internal set

    /** The "Always connected" setting, emitted on change. */
    val alwaysConnected: Flow<Boolean> get() = prefs.alwaysConnected

    /**
     * Saves the setting, then starts or stops the service accordingly. Returns false if
     * the setting could not be saved.
     */
    suspend fun setAlwaysConnected(enabled: Boolean): Boolean {
        try {
            prefs.setAlwaysConnected(enabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("always-connected setting not saved: ${e.javaClass.simpleName}")
            return false
        }
        evaluate()
        return true
    }

    /**
     * Applies [ConnectionPolicy]: starts the service when it should run and isn't yet,
     * stops it otherwise (arming [connectWhenCharging] while paired, so plugging in
     * reconnects without waiting for the next sync). Returns whether the socket should
     * run. Never throws.
     */
    suspend fun evaluate(): Boolean {
        val connect = try {
            ConnectionPolicy.shouldConnect(isPaired(), isCharging(), prefs.alwaysConnected())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("connection policy unreadable: ${e.javaClass.simpleName}")
            return false
        }
        if (connect) {
            if (!serviceRunning) startService()
        } else {
            stop()
            val armCharger = try {
                isPaired() && !prefs.alwaysConnected()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (armCharger) guarded("charging trigger") { connectWhenCharging() }
        }
        return connect
    }

    /** Stops the service (policy says disconnect, or revocation). Never throws. */
    fun stop() {
        guarded("service stop") { service.stop() }
    }

    /** Socket state, reported by the running client. */
    internal fun report(state: ConnectionState) {
        _state.value = state
    }

    /**
     * The service was destroyed. [reevaluate] (it stopped for the policy or a stop
     * request): apply the policy again, since an [evaluate] that ran while it was still
     * shutting down skipped the start (a quick off/on toggle). Running state and OFF are
     * set synchronously in onDestroy (onRunning(false)), not here: this runs later and
     * must not overwrite the state of a newer service instance.
     */
    suspend fun onServiceDestroyed(reevaluate: Boolean) {
        if (reevaluate) evaluate()
    }

    private fun startService() {
        try {
            service.start()
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+, app in the background and
            // not battery-exempt) or a background start refused on an OEM build.
            log("always-connected service not started: ${e.javaClass.simpleName}; staying on push and periodic sync")
        } catch (e: SecurityException) {
            log("always-connected service not started: ${e.javaClass.simpleName}")
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("$what failed: ${e.javaClass.simpleName}")
        }
    }
}
