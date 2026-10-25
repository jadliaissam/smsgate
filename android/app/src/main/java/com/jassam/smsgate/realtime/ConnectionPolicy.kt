package com.jassam.smsgate.realtime

/**
 * When the phone holds the always-connected WebSocket (D5): while it is paired and
 * either on external power or the user turned "Always connected" on. Otherwise it
 * stays on FCM wake-ups plus the 15-minute sync.
 *
 * Re-evaluated by [ConnectivityService] (power broadcasts, setting, pairing state, all
 * observed while it runs), by every sync ([com.jassam.smsgate.work.SyncWorker]), on app
 * start, after pairing, when the setting changes, and when the phone is plugged in
 * ([ConnectWhenChargingWorker]), since implicit power broadcasts no longer reach
 * manifest receivers on API 26+.
 */
object ConnectionPolicy {
    fun shouldConnect(paired: Boolean, charging: Boolean, alwaysConnected: Boolean): Boolean =
        paired && (charging || alwaysConnected)
}

/** What the always-connected socket is doing (for the UI; Task A8 shows it in settings). */
enum class ConnectionState {
    /** No socket: the policy says FCM only, the phone is unpaired, or the service is stopped. */
    OFF,

    /** Opening the WebSocket. */
    CONNECTING,

    /** Open: wake-ups arrive over the socket. */
    CONNECTED,

    /** Disconnected, waiting to retry (backoff) or for a network. */
    WAITING,
}
