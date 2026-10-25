package com.jassam.smsgate.realtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.net.toUri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** A pending [SocketLoop.schedule] task. */
fun interface Cancellable {
    fun cancel()
}

/**
 * The single thread a [SocketClient]'s state lives on, with its timers (a seam so
 * tests drive time by hand). Tasks posted or scheduled run one at a time, in order.
 * After [close], posts and timers are dropped.
 */
interface SocketLoop {
    /** Monotonic milliseconds that keep counting through deep sleep (elapsedRealtime). */
    fun now(): Long

    fun post(task: () -> Unit)

    /**
     * Runs [task] on the loop no earlier than [delayMs] from now. [wakeup]: the timer
     * wakes the CPU from deep sleep, also in Doze (keepalive pings and reconnects);
     * otherwise it runs the next time the phone is awake anyway.
     *
     * Timing (inexact alarms, no exact-alarm permission): a delay of 10 s or more may
     * run up to 75 % late (the OS batches wake-ups); shorter delays are not stretched,
     * but on API 31+ nothing fires sooner than about 5 s after it was set. In Doze,
     * wake-up timers are subject to the allow-while-idle quota (API 31+: 72 an hour;
     * older releases: one per 9 min unless the app is battery-optimisation exempt, D12,
     * then one per 5 s).
     */
    fun schedule(delayMs: Long, wakeup: Boolean, task: () -> Unit): Cancellable

    /** Cancels every timer and ends the thread after the tasks already posted. */
    fun close()
}

/**
 * [SocketLoop] on a [HandlerThread] (created when the socket client is, so nothing
 * exists while the always-connected mode is off) with [AlarmManager] timers whose
 * tasks run on that thread. Plain `postDelayed` timers would stop while the CPU sleeps
 * (screen off, even while charging), the keepalive ping would not go out and the server
 * would drop the socket after `WS_PING_TIMEOUT`.
 *
 * - Wake-up timers on battery: `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` with an
 *   explicit, immutable PendingIntent to [SocketAlarmReceiver] (not exported), so they
 *   also fire in Doze. The same heuristic window as `set()` applies (verified against the
 *   platform: the client passes WINDOW_HEURISTIC and the service stretches any negative
 *   window with maxTriggerTime), so [SocketClient.KEEPALIVE_DELAY_MS]'s margin holds.
 * - Wake-up timers while plugged in (no Doze), and all other timers: listener alarms
 *   (`ELAPSED_REALTIME_WAKEUP` / `ELAPSED_REALTIME`), no PendingIntent, no allow-while-idle
 *   quota, so short reconnect delays are not held back.
 *
 * The alarm's wake lock covers the task (the receiver finishes its broadcast only after
 * the task ran); none is held between frames.
 */
class AndroidSocketLoop(
    context: Context,
    private val pluggedIn: () -> Boolean = { PowerState.isPluggedIn(context) },
) : SocketLoop {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val thread = HandlerThread("smsgate-socket").apply { start() }
    private val handler = Handler(thread.looper)
    private val pending = mutableSetOf<Cancellable>() // guarded by itself
    @Volatile private var closed = false

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun post(task: () -> Unit) {
        if (!closed) handler.post(task)
    }

    override fun schedule(delayMs: Long, wakeup: Boolean, task: () -> Unit): Cancellable {
        if (closed) return Cancellable {}
        val at = now() + delayMs.coerceAtLeast(0)
        val timer = if (wakeup && !pluggedIn()) idleSafe(at, task) else listener(at, wakeup, task)
        synchronized(pending) { pending += timer }
        return Cancellable {
            synchronized(pending) { pending -= timer }
            timer.cancel()
        }
    }

    private fun listener(at: Long, wakeup: Boolean, task: () -> Unit): Cancellable {
        lateinit var self: Cancellable
        val listener = AlarmManager.OnAlarmListener {
            synchronized(pending) { pending -= self }
            task()
        }
        self = Cancellable { alarms.cancel(listener) }
        alarms.set(if (wakeup) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.ELAPSED_REALTIME, at, TAG, listener, handler)
        return self
    }

    private fun idleSafe(at: Long, task: () -> Unit): Cancellable {
        lateinit var self: Cancellable
        val id = SocketAlarms.register(handler) {
            synchronized(pending) { pending -= self }
            task()
        }
        val operation = SocketAlarms.pendingIntent(app, id)
        self = Cancellable {
            SocketAlarms.unregister(id)
            alarms.cancel(operation)
            operation.cancel()
        }
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
        return self
    }

    override fun close() {
        closed = true
        val all = synchronized(pending) { pending.toList().also { pending.clear() } }
        all.forEach(Cancellable::cancel)
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "smsgate:socket"
    }
}

/**
 * Registry of the Doze-safe (PendingIntent) socket timers of this process. The intent's
 * data carries a per-process nonce and the timer id, so an alarm left over from a
 * previous process (PendingIntent alarms outlive it) finds nothing and does nothing.
 */
internal object SocketAlarms {
    const val ACTION = "com.jassam.smsgate.action.SOCKET_ALARM"
    private const val SCHEME = "smsgate-socket-alarm"
    private val nonce = Random.nextLong().toULong().toString(16)
    private val nextId = AtomicInteger()
    private val tasks = ConcurrentHashMap<Int, Pair<Handler, () -> Unit>>()

    fun register(handler: Handler, task: () -> Unit): Int {
        val id = nextId.incrementAndGet() and Int.MAX_VALUE
        tasks[id] = handler to task
        return id
    }

    fun unregister(id: Int) {
        tasks.remove(id)
    }

    fun intent(context: Context, id: Int): Intent =
        Intent(context, SocketAlarmReceiver::class.java)
            .setAction(ACTION)
            .setData("$SCHEME://$nonce/$id".toUri())

    fun pendingIntent(context: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(context, id, intent(context, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * Runs the timer named by [data] on its loop and then calls [done]. Returns false
     * (and never calls [done]) when there is no such timer in this process.
     */
    fun dispatch(data: Uri?, done: () -> Unit): Boolean {
        if (data?.scheme != SCHEME || data.host != nonce) return false
        val id = data.lastPathSegment?.toIntOrNull() ?: return false
        val (handler, task) = tasks.remove(id) ?: return false
        val posted = handler.post {
            try {
                task()
            } finally {
                done()
            }
        }
        if (!posted) done() // the loop is gone
        return true
    }
}

/**
 * Delivers [SocketAlarms] timers (not exported: only this app's PendingIntents reach
 * it). Holds the broadcast, and so the alarm's wake lock, until the task has run on the
 * socket thread.
 */
class SocketAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SocketAlarms.ACTION) return
        val result: PendingResult? = goAsync()
        if (!SocketAlarms.dispatch(intent.data) { result?.finish() }) result?.finish()
    }
}
