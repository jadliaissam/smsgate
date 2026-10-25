package com.jassam.smsgate.ui.home

import java.util.GregorianCalendar
import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DayWindowTest {
    private val casablanca = TimeZone.getTimeZone("Africa/Casablanca")
    private val paris = TimeZone.getTimeZone("Europe/Paris")
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")

    private fun at(zone: TimeZone, y: Int, m: Int, d: Int, h: Int, min: Int = 0, s: Int = 0, ms: Int = 0): Long =
        GregorianCalendar(zone).apply {
            clear()
            set(y, m - 1, d, h, min, s)
            set(java.util.Calendar.MILLISECOND, ms)
        }.timeInMillis

    @Test
    fun aDayRunsFromLocalMidnightToTheNext() {
        val w = DayWindow.of(at(paris, 2026, 10, 25, 15, 30), paris)
        assertEquals(at(paris, 2026, 10, 25, 0), w.startMs)
        assertEquals(at(paris, 2026, 10, 26, 0), w.endMs)
    }

    @Test
    fun boundariesAroundMidnightAreHalfOpen() {
        val lastMs = at(tokyo, 2026, 3, 1, 23, 59, 59, 999)
        val midnight = at(tokyo, 2026, 3, 2, 0)
        val yesterday = DayWindow.of(lastMs, tokyo)
        val today = DayWindow.of(midnight, tokyo)
        assertTrue(lastMs in yesterday)
        assertFalse(midnight in yesterday)
        assertTrue(midnight in today)
        assertEquals(yesterday.endMs, today.startMs)
    }

    @Test
    fun theSameInstantIsADifferentDayInAnotherZone() {
        // 23:30 in Casablanca (UTC+1) is 07:30 the next day in Tokyo.
        val instant = at(casablanca, 2026, 10, 25, 23, 30)
        assertEquals(at(casablanca, 2026, 10, 25, 0), DayWindow.of(instant, casablanca).startMs)
        assertEquals(at(tokyo, 2026, 10, 26, 0), DayWindow.of(instant, tokyo).startMs)
    }

    @Test
    fun dstDaysAre23And25Hours() {
        val hour = 3_600_000L
        val spring = DayWindow.of(at(paris, 2026, 3, 29, 12), paris)
        assertEquals(23 * hour, spring.endMs - spring.startMs)
        val autumn = DayWindow.of(at(paris, 2026, 10, 25, 12), paris)
        assertEquals(25 * hour, autumn.endMs - autumn.startMs)
    }

    @Test
    fun aMidnightThatDoesNotExistStartsTheDayAtTheFirstValidInstant() {
        // Chile springs forward at 00:00 on 2026-09-06: clocks jump to 01:00.
        val santiago = TimeZone.getTimeZone("America/Santiago")
        val hour = 3_600_000L
        val before = DayWindow.of(at(santiago, 2026, 9, 5, 12), santiago)
        val gap = DayWindow.of(at(santiago, 2026, 9, 6, 12), santiago)
        val after = DayWindow.of(at(santiago, 2026, 9, 7, 12), santiago)
        assertEquals(before.endMs, gap.startMs)
        assertEquals(gap.endMs, after.startMs)
        assertEquals(24 * hour, before.endMs - before.startMs)
        assertEquals(23 * hour, gap.endMs - gap.startMs)
        assertEquals(24 * hour, after.endMs - after.startMs)
        val start = GregorianCalendar(santiago).apply { timeInMillis = gap.startMs }
        assertEquals(6, start.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(1, start.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, start.get(java.util.Calendar.MINUTE))
    }

    @Test
    fun windowsAdvanceAtEachMidnight() = runTest {
        val start = at(paris, 2026, 10, 24, 23, 59)
        val windows = dayWindows({ start + testScheduler.currentTime }, { paris }).take(3).toList()
        assertEquals(
            listOf(at(paris, 2026, 10, 24, 0), at(paris, 2026, 10, 25, 0), at(paris, 2026, 10, 26, 0)),
            windows.map { it.startMs },
        )
        // The second window came at the first midnight, one minute in.
        assertTrue(testScheduler.currentTime >= 60_000)
    }
}
