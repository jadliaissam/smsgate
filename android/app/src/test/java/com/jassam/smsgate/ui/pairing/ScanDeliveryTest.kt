package com.jassam.smsgate.ui.pairing

import com.google.android.gms.tasks.Tasks
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scan result reaches the callbacks without any Activity (the listeners are
 * not lifecycle-scoped, so a stopped or recreated MainActivity cannot drop it).
 * The real scanner still needs an on-device check (A10).
 */
class ScanDeliveryTest {
    private val direct = Executor { it.run() }
    private val results = mutableListOf<String>()
    private var unavailable = 0

    private fun <T> deliver(task: com.google.android.gms.tasks.Task<T>, raw: (T) -> String?) =
        PlayServices.deliverScan(task, direct, raw, { results += it }, { unavailable++ })

    @Test
    fun successDeliversTheRawValue() {
        deliver(Tasks.forResult("smsgate://pair?server=x&code=ABCD2345")) { it }
        assertEquals(listOf("smsgate://pair?server=x&code=ABCD2345"), results)
        assertEquals(0, unavailable)
    }

    @Test
    fun codeWithoutTextDeliversEmpty() {
        deliver(Tasks.forResult("ignored")) { null }
        assertEquals(listOf(""), results)
    }

    @Test
    fun failureReportsUnavailable() {
        deliver(Tasks.forException<String>(IllegalStateException("module not installed"))) { it }
        assertEquals(emptyList<String>(), results)
        assertEquals(1, unavailable)
    }

    @Test
    fun cancelCallsNothing() {
        deliver(Tasks.forCanceled<String>()) { it }
        assertEquals(emptyList<String>(), results)
        assertEquals(0, unavailable)
    }
}
