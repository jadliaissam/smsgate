package com.jassam.smsgate.realtime

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [SocketLoop] on a real single thread (OkHttp callbacks arrive from other threads, as
 * in production) with a virtual clock: timers run only when the test calls [advance].
 */
internal class FakeLoop(start: Long = 1_000_000L) : SocketLoop {
    class Timer(val at: Long, val delayMs: Long, val wakeup: Boolean, val task: () -> Unit)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-loop").apply { isDaemon = true } }
    private val lock = Any()
    private var time = start
    private val timers = mutableListOf<Timer>()
    @Volatile var closed = 0

    override fun now(): Long = synchronized(lock) { time }

    override fun post(task: () -> Unit) {
        executor.execute(task)
    }

    override fun schedule(delayMs: Long, wakeup: Boolean, task: () -> Unit): Cancellable {
        val timer = synchronized(lock) { Timer(time + delayMs, delayMs, wakeup, task).also { timers += it } }
        return Cancellable { synchronized(lock) { timers.remove(timer) } }
    }

    override fun close() {
        closed++
    }

    /** Timers not yet run or cancelled, soonest first. */
    fun timers(): List<Timer> = synchronized(lock) { timers.sortedBy { it.at } }

    /** Moves the clock forward by [ms], running every timer that falls due, in order, on the loop. */
    fun advance(ms: Long) {
        val target = now() + ms
        while (true) {
            val next = synchronized(lock) {
                timers.filter { it.at <= target }.minByOrNull { it.at }?.also {
                    timers.remove(it)
                    time = maxOf(time, it.at)
                }
            } ?: break
            executor.submit(next.task).get(5, TimeUnit.SECONDS)
        }
        synchronized(lock) { time = target }
        idle()
    }

    /** Waits until the tasks posted so far have run. */
    fun idle() {
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }
}
