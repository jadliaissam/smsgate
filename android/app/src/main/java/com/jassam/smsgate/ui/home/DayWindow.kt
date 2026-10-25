package com.jassam.smsgate.ui.home

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * One local calendar day as `[startMs, endMs)` epoch ms. Built with [GregorianCalendar]
 * (java.time needs API 26), so days of 23 or 25 hours around DST changes come out
 * right, and a midnight that does not exist starts the day at the first valid instant.
 */
data class DayWindow(val startMs: Long, val endMs: Long) {
    operator fun contains(ms: Long): Boolean = ms in startMs until endMs

    companion object {
        fun of(nowMs: Long, zone: TimeZone): DayWindow {
            val c = GregorianCalendar(zone)
            c.timeInMillis = nowMs
            c.startOfDay()
            val start = c.timeInMillis
            c.add(Calendar.DAY_OF_MONTH, 1)
            c.startOfDay()
            return DayWindow(start, c.timeInMillis)
        }

        private fun Calendar.startOfDay() {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}

/**
 * Today's window, again at each local midnight (one timer a day while collected, not a
 * polling loop). [zone] is read at each boundary, so a time-zone change shows up at
 * the next midnight or when collection restarts (screen shown again).
 */
fun dayWindows(now: () -> Long, zone: () -> TimeZone): Flow<DayWindow> = flow {
    while (true) {
        val window = DayWindow.of(now(), zone())
        emit(window)
        delay((window.endMs - now()).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS))
    }
}.distinctUntilChanged()

private const val MIN_WAIT_MS = 1_000L
private const val MAX_WAIT_MS = 26L * 60 * 60 * 1000
