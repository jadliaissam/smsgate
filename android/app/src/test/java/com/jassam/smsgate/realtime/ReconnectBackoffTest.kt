package com.jassam.smsgate.realtime

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {
    /** Always returns the lowest or highest value of the requested range. */
    private class Extreme(private val high: Boolean) : Random() {
        override fun nextBits(bitCount: Int): Int = if (high) -1 ushr (32 - bitCount) else 0
        override fun nextLong(until: Long): Long = if (high) until - 1 else 0
    }

    @Test
    fun nominalDelayDoublesFromOneSecondAndCapsAtFiveMinutes() {
        val b = ReconnectBackoff(Random(1))
        assertEquals(
            listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 64_000, 128_000, 256_000, 300_000, 300_000),
            (0..10).map(b::nominalMs),
        )
        assertEquals(300_000L, b.nominalMs(Int.MAX_VALUE)) // no overflow
        assertEquals(1_000L, b.nominalMs(-3))
    }

    @Test
    fun jitterStaysWithinHalfToFullNominal() {
        val low = ReconnectBackoff(Extreme(high = false))
        val high = ReconnectBackoff(Extreme(high = true))
        for (attempt in 0..20) {
            val n = low.nominalMs(attempt)
            assertEquals(n / 2, low.delayMs(attempt))
            assertEquals(n, high.delayMs(attempt))
        }
        val random = ReconnectBackoff(Random(42))
        repeat(1_000) {
            val attempt = it % 15
            val n = random.nominalMs(attempt)
            assertTrue(random.delayMs(attempt) in n / 2..n)
        }
    }

    @Test
    fun longDelayIsAtTheCap() {
        val b = ReconnectBackoff(Random(3))
        repeat(100) { assertTrue(b.longDelayMs() in 150_000L..300_000L) }
    }
}
