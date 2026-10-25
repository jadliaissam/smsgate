package com.jassam.smsgate.realtime

import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/** A pending [SocketLoop.schedule] task. */
fun interface Cancellable {
    fun cancel()
}

/**
 * The single thread a [SocketClient]'s state lives on, with its timers (a seam so
 * tests drive time by hand). Tasks posted or scheduled run one at a time, in order.
 */
interface SocketLoop {
    /** Monotonic milliseconds that keep counting through deep sleep (elapsedRealtime). */
    fun now(): Long

    fun post(task: () -> Unit)

    /**
     * Runs [task] on the loop no earlier than [delayMs] from now. [wakeup]: the timer
     * wakes the CPU from deep sleep (keepalive pings and reconnects); otherwise it runs
     * the next time the phone is awake anyway. Delivery may be late by up to 75 % of the
     * delay (inexact alarms: no exact-alarm permission, the OS batches wake-ups; delays
     * under 10 s are not stretched), and more in Doze (maintenance windows).
     */
    fun schedule(delayMs: Long, wakeup: Boolean, task: () -> Unit): Cancellable

    /** Cancels every timer and ends the thread after the tasks already posted. */
    fun close()
}

/**
 * [SocketLoop] on a [HandlerThread] (created when the socket client is, so nothing
 * exists while the always-connected mode is off) with timers from
 * [AlarmManager] listener alarms on that thread: `ELAPSED_REALTIME_WAKEUP` for wake-up
 * timers, `ELAPSED_REALTIME` otherwise. Plain `postDelayed` timers would stop while
 * the CPU sleeps (screen off, even while charging), the 5-minute ping would not go
 * out and the server would drop the socket after `WS_PING_TIMEOUT`. A listener alarm
 * needs no PendingIntent or receiver, lives only as long as this process, and holds a
 * wake lock only while its task runs: none is held between frames.
 */
class AndroidSocketLoop(context: Context) : SocketLoop {
    private val alarms = context.applicationContext.getSystemService(AlarmManager::class.java)
    private val thread = HandlerThread("smsgate-socket").apply { start() }
    private val handler = Handler(thread.looper)
    private val pending = mutableSetOf<AlarmManager.OnAlarmListener>() // guarded by itself

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun post(task: () -> Unit) {
        handler.post(task)
    }

    override fun schedule(delayMs: Long, wakeup: Boolean, task: () -> Unit): Cancellable {
        val listener = object : AlarmManager.OnAlarmListener {
            override fun onAlarm() {
                synchronized(pending) { pending.remove(this) }
                task()
            }
        }
        val type = if (wakeup) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.ELAPSED_REALTIME
        synchronized(pending) { pending += listener }
        alarms.set(type, now() + delayMs.coerceAtLeast(0), TAG, listener, handler)
        return Cancellable {
            synchronized(pending) { pending.remove(listener) }
            alarms.cancel(listener)
        }
    }

    override fun close() {
        val all = synchronized(pending) { pending.toList().also { pending.clear() } }
        all.forEach(alarms::cancel)
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "smsgate:socket"
    }
}
