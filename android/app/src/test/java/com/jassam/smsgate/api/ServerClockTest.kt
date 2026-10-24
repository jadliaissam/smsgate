package com.jassam.smsgate.api

import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerClockTest {
    private var mono = 0L
    private var wall = 1_000_000L
    private val clock = ServerClock(monotonicMillis = { mono }, wallMillis = { wall })

    @Test
    fun beforeSyncFallsBackToWallClock() {
        assertFalse(clock.isSynced)
        assertEquals(Instant.fromEpochMilliseconds(wall), clock.serverNow())
        assertEquals(0L, clock.offsetMillis())
    }

    @Test
    fun usesMidpointOfRoundTrip() {
        val server = Instant.parse("2026-10-24T10:00:00Z")
        clock.update(server, sentAt = 100, receivedAt = 300) // midpoint 200
        mono = 300
        assertTrue(clock.isSynced)
        assertEquals(Instant.parse("2026-10-24T10:00:00.100Z"), clock.serverNow())
        mono = 10_200
        assertEquals(Instant.parse("2026-10-24T10:00:10Z"), clock.serverNow())
    }

    @Test
    fun wallClockChangesDoNotMoveServerNow() {
        clock.update(Instant.parse("2026-10-24T10:00:00Z"), 0, 0)
        val before = clock.serverNow()
        wall += 3_600_000
        assertEquals(before, clock.serverNow())
    }

    @Test
    fun offsetIsServerMinusWall() {
        wall = Instant.parse("2026-10-24T09:59:55Z").toEpochMilliseconds()
        clock.update(Instant.parse("2026-10-24T10:00:00Z"), 0, 0)
        assertEquals(5_000L, clock.offsetMillis())
    }

    @Test
    fun laterResponsesReplaceTheAnchor() {
        clock.update(Instant.parse("2026-10-24T10:00:00Z"), 0, 0)
        mono = 1_000
        clock.update(Instant.parse("2026-10-24T10:00:05Z"), 1_000, 1_000)
        assertEquals(Instant.parse("2026-10-24T10:00:05Z"), clock.serverNow())
    }
}
