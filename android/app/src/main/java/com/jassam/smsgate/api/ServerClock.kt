package com.jassam.smsgate.api

import android.os.SystemClock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Server-synchronised clock. Every response carrying `server_time` updates it
 * (DeviceApi does this itself): the server time is assumed to correspond to the
 * midpoint between sending the request and receiving the response.
 *
 * The anchor is kept against a monotonic clock (elapsedRealtime), so a user
 * changing the phone's wall clock after a sync cannot shift [serverNow]. Before
 * the first sync [serverNow] falls back to the wall clock; callers that must be
 * exact (the expiry check before sending) have a fresh `server_time` from the
 * claim response anyway.
 */
class ServerClock(
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    private class Anchor(val server: Instant, val monotonic: Long)

    @Volatile
    private var anchor: Anchor? = null

    /** True once a server time has been observed in this process. */
    val isSynced: Boolean get() = anchor != null

    /** Monotonic timestamp to pass back to [update] as the request start/end. */
    fun mark(): Long = monotonicMillis()

    /**
     * Record [serverTime] observed by a request sent at monotonic [sentAt] whose
     * response arrived at [receivedAt] (both from [mark]).
     */
    fun update(serverTime: Instant, sentAt: Long, receivedAt: Long) {
        val mid = sentAt + (receivedAt - sentAt).coerceAtLeast(0) / 2
        anchor = Anchor(serverTime, mid)
    }

    /** Current server time (wall clock until the first sync). */
    fun serverNow(): Instant {
        val a = anchor ?: return Instant.fromEpochMilliseconds(wallMillis())
        return a.server + (monotonicMillis() - a.monotonic).milliseconds
    }

    /** Server minus local wall clock, in milliseconds (0 before the first sync). For display/diagnostics. */
    fun offsetMillis(): Long =
        if (anchor == null) 0 else serverNow().toEpochMilliseconds() - wallMillis()
}
