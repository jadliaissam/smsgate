package com.jassam.smsgate.realtime

import kotlin.random.Random

/**
 * Jittered exponential reconnect delays: the nominal delay doubles from [baseMs]
 * (1 s, 2 s, 4 s, …) up to [maxMs] (5 min), and each delay is drawn uniformly from
 * `[nominal / 2, nominal]` ("equal jitter"), so phones that lost the same server don't
 * all come back at the same instant, while a delay never exceeds the cap.
 */
class ReconnectBackoff(
    private val random: Random,
    val baseMs: Long = BASE_MS,
    val maxMs: Long = MAX_MS,
) {
    /** The nominal (un-jittered) delay before reconnect attempt [attempt] (0-based). */
    fun nominalMs(attempt: Int): Long {
        var n = baseMs
        repeat(attempt.coerceAtLeast(0)) {
            if (n >= maxMs) return maxMs
            n *= 2
        }
        return n.coerceAtMost(maxMs)
    }

    /** A jittered delay in `[nominal / 2, nominal]` for [attempt]. */
    fun delayMs(attempt: Int): Long {
        val nominal = nominalMs(attempt)
        val low = nominal / 2
        return low + random.nextLong(nominal - low + 1)
    }

    /** A jittered delay at the cap, `[maxMs / 2, maxMs]`. */
    fun longDelayMs(): Long = delayMs(Int.MAX_VALUE)

    companion object {
        const val BASE_MS = 1_000L
        const val MAX_MS = 5 * 60_000L
    }
}
