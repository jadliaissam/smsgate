package com.jassam.smsgate.realtime

import android.app.AlarmManager
import android.app.Application
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSocketLoopTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val loop = AndroidSocketLoop(app)

    @After
    fun tearDown() = loop.close()

    @Test
    fun wakeupTimersAreElapsedRealtimeWakeupListenerAlarmsAndOthersDontWake() {
        loop.schedule(171_000, wakeup = true) {}
        loop.schedule(30_000, wakeup = false) {}
        val scheduled = alarms.scheduledAlarms.sortedBy { it.triggerAtMs }
        assertEquals(listOf(AlarmManager.ELAPSED_REALTIME, AlarmManager.ELAPSED_REALTIME_WAKEUP), scheduled.map { it.type })
        assertEquals(SystemClock.elapsedRealtime() + 30_000, scheduled[0].triggerAtMs)
        assertTrue(scheduled.all { it.onAlarmListener != null && it.operation == null }) // no PendingIntent/receiver
    }

    @Test
    fun cancelAndCloseRemoveTheAlarms() {
        val timer = loop.schedule(1_000, wakeup = true) {}
        loop.schedule(2_000, wakeup = true) {}
        timer.cancel()
        assertEquals(1, alarms.scheduledAlarms.size)
        loop.close()
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun anAlarmRunsItsTaskOnTheLoopThread() {
        val ran = CountDownLatch(1)
        var thread: String? = null
        loop.schedule(1_000, wakeup = true) {
            thread = Thread.currentThread().name
            ran.countDown()
        }
        val alarm = alarms.scheduledAlarms.single()
        val handler = alarm.handler!!
        handler.post { alarm.onAlarmListener!!.onAlarm() }
        shadowOf(handler.looper).idle()
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertEquals("smsgate-socket", thread)
    }
}
