package com.jassam.smsgate.realtime

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSocketLoopTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val loop = AndroidSocketLoop(app)

    @After
    fun tearDown() = loop.close()

    private fun single(): ShadowAlarmManager.ScheduledAlarm = alarms.scheduledAlarms.single()

    /** Delivers a PendingIntent alarm like AlarmManager would (the receiver runs on the main looper). */
    private fun fire(alarm: ShadowAlarmManager.ScheduledAlarm) {
        alarm.operation!!.send()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun wakeupTimersAreAllowWhileIdleAlarmsToTheReceiverWhateverThePowerState() {
        // Chosen when armed and kept: always allow-while-idle, so a timer armed while
        // plugged in still fires if the phone is unplugged and dozes before it is due.
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC))
        assertTrue(PowerState.isPluggedIn(app))
        loop.schedule(171_000, wakeup = true) {}
        val alarm = single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertTrue("fires in Doze", alarm.isAllowWhileIdle)
        assertEquals(SystemClock.elapsedRealtime() + 171_000, alarm.triggerAtMs)
        val pi = shadowOf(alarm.operation!!)
        assertTrue(pi.isBroadcast)
        assertTrue(pi.isImmutable)
        assertEquals(ComponentName(app, SocketAlarmReceiver::class.java), pi.savedIntent.component)
        assertEquals(SocketAlarms.ACTION, pi.savedIntent.action)
    }

    @Test
    fun nonWakeupTimersAreListenerAlarms() {
        loop.schedule(30_000, wakeup = false) {}
        val alarm = single()
        assertEquals(AlarmManager.ELAPSED_REALTIME, alarm.type)
        assertTrue(alarm.onAlarmListener != null && alarm.operation == null && !alarm.isAllowWhileIdle)
    }

    @Test
    fun anIdleSafeAlarmRunsItsTaskOnTheLoopThread() {
        val ran = CountDownLatch(1)
        var thread: String? = null
        loop.schedule(1_000, wakeup = true) {
            thread = Thread.currentThread().name
            ran.countDown()
        }
        fire(single())
        shadowOf(loop.looper).idle() // run what the receiver posted to the socket thread
        assertEquals(0, ran.count)
        assertEquals("smsgate-socket", thread)
        assertFalse("a delivered timer is gone from the registry", SocketAlarms.dispatch(shadowOf(single().operation!!).savedIntent.data) {})
    }

    @Test
    fun aCancelledOrStaleAlarmDoesNothing() {
        val ran = AtomicInteger()
        val timer = loop.schedule(1_000, wakeup = true) { ran.incrementAndGet() }
        val alarm = single()
        val intent = shadowOf(alarm.operation!!).savedIntent
        timer.cancel()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        app.sendBroadcast(Intent(intent))
        // An alarm left over from an earlier process (other nonce) is ignored too.
        app.sendBroadcast(Intent(intent).setData(android.net.Uri.parse("smsgate-socket-alarm://0/1")))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, ran.get())
        assertFalse(SocketAlarms.dispatch(intent.data) {})
    }

    @Test
    fun receiverIgnoresOtherActions() {
        val ran = AtomicInteger()
        loop.schedule(1_000, wakeup = true) { ran.incrementAndGet() }
        val intent = Intent(shadowOf(single().operation!!).savedIntent).setAction("other")
        SocketAlarmReceiver().onReceive(app, intent)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(0, ran.get())
    }

    @Test
    fun closeCancelsEveryAlarmAndDropsLaterWork() {
        loop.schedule(1_000, wakeup = true) {}
        loop.schedule(2_000, wakeup = true) {}
        loop.schedule(3_000, wakeup = false) {}
        assertEquals(3, alarms.scheduledAlarms.size)
        loop.close()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        loop.schedule(1_000, wakeup = true) {}
        assertTrue(alarms.scheduledAlarms.isEmpty())
        var posted = false
        loop.post { posted = true }
        assertFalse(posted)
    }

    @Test
    fun manifestDeclaresThePrivateAlarmReceiver() {
        val info = app.packageManager.getReceiverInfo(ComponentName(app, SocketAlarmReceiver::class.java), 0)
        assertFalse(info.exported)
        assertNotNull(info)
        assertNull(info.permission)
    }
}
