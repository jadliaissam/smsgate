package com.jassam.smsgate.sms

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsResultReceiverTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val calls = java.util.Collections.synchronizedList(mutableListOf<List<Any?>>())

    @After
    fun tearDown() {
        SmsResultReceiver.sinkOverride = null
    }

    private fun sink(block: () -> Unit = {}) {
        SmsResultReceiver.sinkOverride = SmsResultSink { ref, code, delivery ->
            calls += listOf(ref, code, delivery)
            block()
        }
    }

    /**
     * Sends [intent] as an ordered broadcast with [code] as the result code. The final
     * receiver only runs once every receiver has finished, including a goAsync() one.
     */
    private fun broadcast(intent: Intent, code: Int) {
        val finished = CountDownLatch(1)
        val last = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = finished.countDown()
        }
        app.sendOrderedBroadcast(intent, null, last, null, code, null, null)
        val deadline = System.currentTimeMillis() + 5_000
        while (finished.count > 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            finished.await(10, TimeUnit.MILLISECONDS)
        }
        if (finished.count > 0) fail("the receiver never finished its pending result")
    }

    @Test
    fun sentResultReachesTheSinkOffMainAndFinishes() {
        var thread: Thread? = null
        sink { thread = Thread.currentThread() }
        val ref = PartRef("m1", 1, PartKind.SENT)
        broadcast(SmsIntents.intent(app, ref), 2)
        assertEquals(listOf(listOf(ref, 2, null)), calls)
        assertTrue(thread != Looper.getMainLooper().thread)
    }

    @Test
    fun deliveryResultWithoutPdu() {
        sink()
        val ref = PartRef("m1", 0, PartKind.DELIVERED)
        broadcast(SmsIntents.intent(app, ref).putExtra("format", "3gpp"), Activity.RESULT_OK)
        assertEquals(listOf(listOf(ref, Activity.RESULT_OK, DeliveryReport("3gpp", pduPresent = false, status = null))), calls)
    }

    @Test
    fun unparseablePduIsPassedOnAsPresentWithoutStatus() {
        sink()
        val ref = PartRef("m1", 0, PartKind.DELIVERED)
        val garbage = byteArrayOf(0x7F, 0x01, 0x02)
        broadcast(SmsIntents.intent(app, ref).putExtra("pdu", garbage).putExtra("format", "3gpp"), Activity.RESULT_OK)
        assertEquals(listOf(listOf(ref, Activity.RESULT_OK, DeliveryReport("3gpp", pduPresent = true, status = null))), calls)
    }

    @Test
    fun finishesEvenWhenTheSinkThrows() {
        sink { throw IllegalStateException("db down") }
        broadcast(SmsIntents.intent(app, PartRef("m1", 0, PartKind.SENT)), Activity.RESULT_OK)
        assertEquals(1, calls.size)
    }

    @Test
    fun malformedAndForeignIntentsAreIgnored() {
        sink()
        val component = SmsIntents.intent(app, PartRef("m1", 0, PartKind.SENT)).component!!
        val bad = listOf(
            Intent().setComponent(component),
            Intent("android.intent.action.BOOT_COMPLETED").setComponent(component),
            Intent(SmsIntents.ACTION).setComponent(component),
            Intent(SmsIntents.ACTION, Uri.parse("smsgate://sms/m1/0/bogus")).setComponent(component),
            Intent(SmsIntents.ACTION, Uri.parse("smsgate://sms/m1/99/sent")).setComponent(component),
            Intent(SmsIntents.ACTION, Uri.parse("https://evil.example/m1/0/sent")).setComponent(component),
        )
        for (intent in bad) broadcast(intent, Activity.RESULT_OK)
        assertTrue(calls.isEmpty())
    }
}
