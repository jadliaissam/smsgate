package com.jassam.smsgate.sms

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import com.jassam.smsgate.api.Sim
import com.jassam.smsgate.api.Status
import com.jassam.smsgate.journal.JournalDao
import com.jassam.smsgate.journal.JournalDb
import com.jassam.smsgate.journal.JournalState
import com.jassam.smsgate.journal.PendingReport
import com.jassam.smsgate.journal.claimedEntry
import com.jassam.smsgate.journal.inMemoryJournal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsSenderTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var db: JournalDb
    private lateinit var dao: JournalDao
    private val transport = FakeTransport()
    private val queue = RecordingQueue()
    private var sims = listOf(Sim(0, 11, "Orange"), Sim(1, 22, "inwi"))
    private var clock = 2_000L
    private val logs = mutableListOf<String>()
    private lateinit var sender: SmsSender
    private lateinit var handler: SmsResultHandler

    private val number = "+212600000001"
    private val body = "Your code is 4242"

    @Before
    fun setUp() {
        db = inMemoryJournal()
        dao = db.journalDao()
        sender = SmsSender(
            dao, transport, { sims }, { SmsIntents.pendingIntent(app, it) }, queue, { clock }, { synchronized(logs) { logs += it } },
        )
        handler = SmsResultHandler(dao, queue) { clock }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun claimed(id: String, simSlot: Int? = null, text: String? = body) =
        claimedEntry(id, body = text, simSlot = simSlot).also { assertTrue(dao.insertClaimed(it)) }

    private suspend fun deliver(pi: PendingIntent, resultCode: Int) {
        val ref = SmsIntents.parse(shadowOf(pi).savedIntent)!!
        handler.handle(ref, resultCode, null, null)
    }

    private fun assertNoPii(texts: Iterable<String?>) {
        for (t in texts.filterNotNull()) {
            assertFalse(t, t.contains(number) || t.contains("600000001"))
            assertFalse(t, t.contains(body) || t.contains("4242"))
        }
    }

    @Test
    fun handsOffWithOneSentAndOneDeliveryIntentPerPart() = runBlocking {
        transport.partsOf = { listOf("a", "b", "c") }
        val entry = claimed("m1")
        assertEquals(SendResult.HandedOff(3), sender.send(entry))
        val row = dao.get("m1")!!
        assertEquals(JournalState.SENDING, row.state)
        assertEquals(3, row.parts)
        val sent = transport.sends.single()
        assertEquals(number, sent.to)
        assertEquals(listOf("a", "b", "c"), sent.parts)
        assertEquals(listOf(null), transport.channelRequests)
        val refs = (sent.sent + sent.delivered).map { SmsIntents.parse(shadowOf(it).savedIntent) }
        assertEquals(
            (0..2).map { PartRef("m1", it, PartKind.SENT) } + (0..2).map { PartRef("m1", it, PartKind.DELIVERED) },
            refs,
        )
        assertEquals(6, (sent.sent + sent.delivered).toSet().size)
        assertEquals(6, (sent.sent + sent.delivered).map { shadowOf(it).requestCode }.toSet().size)
        assertTrue(queue.reports.isEmpty())
        assertNoPii(logs)
    }

    @Test
    fun secondSendOfSameRowDoesNothing() = runBlocking {
        val entry = claimed("m1")
        assertEquals(SendResult.HandedOff(1), sender.send(entry))
        assertEquals(SendResult.NotSent, sender.send(entry))
        assertEquals(SendResult.NotSent, sender.send(claimedEntry("unknown")))
        assertEquals(1, transport.sends.size)
    }

    @Test
    fun concurrentSendsHandOffExactlyOnce() = runBlocking {
        val entry = claimed("m1")
        val callers = 6
        val ready = CountDownLatch(callers)
        val go = CountDownLatch(1)
        val results = (1..callers).map {
            async(Dispatchers.IO) {
                ready.countDown()
                go.await(5, TimeUnit.SECONDS)
                sender.send(entry)
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        val all = results.awaitAll()
        assertEquals(1, all.count { it is SendResult.HandedOff })
        assertEquals(callers - 1, all.count { it == SendResult.NotSent })
        assertEquals(1, transport.sends.size)
    }

    @Test
    fun simSlotMapsToItsSubscription() = runBlocking {
        sender.send(claimed("m0", simSlot = 0))
        sender.send(claimed("m1", simSlot = 1))
        sender.send(claimed("mn", simSlot = null))
        assertEquals(listOf(11, 22, null), transport.channelRequests)
        assertEquals(3, transport.sends.size)
    }

    @Test
    fun missingSimFailsWithoutFallingBack() = runBlocking {
        sims = listOf(Sim(0, 11, "Orange"))
        assertEquals(SendResult.Failed(ErrorCodes.SIM_UNAVAILABLE), sender.send(claimed("m1", simSlot = 1)))
        sims = emptyList()
        assertEquals(SendResult.Failed(ErrorCodes.SIM_UNAVAILABLE), sender.send(claimed("m2", simSlot = 0)))
        assertTrue(transport.channelRequests.isEmpty())
        assertTrue(transport.sends.isEmpty())
        val row = dao.get("m1")!!
        assertEquals(JournalState.FAILED, row.state)
        assertEquals(ErrorCodes.SIM_UNAVAILABLE, row.errorCode)
        assertEquals(setOf(Status.FAILED), row.reported)
        assertEquals(listOf("m1", "m2"), queue.reports.map { it.id })
        assertEquals(PendingReport("m1", Status.FAILED, ErrorCodes.SIM_UNAVAILABLE, "No active SIM in slot 1"), queue.reports[0])
    }

    private fun expectFailure(code: String, configure: FakeTransport.() -> Unit) = runBlocking {
        val t = FakeTransport().apply(configure)
        val q = RecordingQueue()
        val s = SmsSender(dao, t, { sims }, { SmsIntents.pendingIntent(app, it) }, q, { clock })
        val id = "m-$code-${System.nanoTime()}"
        val result = s.send(claimed(id))
        assertEquals(SendResult.Failed(code), result)
        val row = dao.get(id)!!
        assertEquals(JournalState.FAILED, row.state)
        assertEquals(code, row.errorCode)
        assertTrue(row.errorMessage!!.length <= ErrorCodes.MAX_MESSAGE)
        assertEquals(listOf(code), q.reports.map { it.errorCode })
        assertEquals(Status.FAILED, q.reports.single().status)
        assertNoPii(listOf(row.errorMessage, q.reports.single().errorMessage))
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun synchronousPlatformFailuresMoveToFailedAndReport() {
        expectFailure(ErrorCodes.PERMISSION_DENIED) { throwOnSend = SecurityException("Sending SMS message: uid 10123 does not have android.permission.SEND_SMS.") }
        expectFailure(ErrorCodes.INVALID_DESTINATION) { throwOnSend = IllegalArgumentException("Invalid destinationAddress +212600000001") }
        expectFailure(ErrorCodes.SEND_EXCEPTION) { throwOnSend = IllegalStateException("boom +212600000001") }
        expectFailure(ErrorCodes.NO_SMS_SERVICE) { available = false }
        expectFailure(ErrorCodes.NO_SMS_SERVICE) { throwOnDivide = UnsupportedOperationException("Sms is not supported") }
        expectFailure(ErrorCodes.PERMISSION_DENIED) { throwOnChannel = SecurityException("no") }
        expectFailure(ErrorCodes.SEND_EXCEPTION) { throwOnDivide = RuntimeException("Your code is 4242") }
        expectFailure(ErrorCodes.TOO_MANY_PARTS) { partsOf = { b -> List(65) { b } } }
    }

    @Test
    fun missingBodyFails() = runBlocking {
        assertEquals(SendResult.Failed(ErrorCodes.BODY_MISSING), sender.send(claimed("m1", text = null)))
        assertEquals(JournalState.FAILED, dao.get("m1")!!.state)
        assertTrue(transport.sends.isEmpty())
    }

    @Test
    fun sixtyFourPartsIsTheLimit() = runBlocking {
        transport.partsOf = { b -> List(64) { b } }
        assertEquals(SendResult.HandedOff(64), sender.send(claimed("m1")))
    }

    @Test
    fun threePartsOneFailsReportsFailedOnce() = runBlocking {
        transport.partsOf = { listOf("a", "b", "c") }
        sender.send(claimed("m1"))
        val s = transport.sends.single()
        deliver(s.sent[0], Activity.RESULT_OK)
        deliver(s.sent[1], 1) // RESULT_ERROR_GENERIC_FAILURE
        deliver(s.sent[2], 2) // RESULT_ERROR_RADIO_OFF
        deliver(s.sent[1], 1) // duplicate broadcast
        assertEquals(1, queue.reports.size)
        val r = queue.reports.single()
        assertEquals(Status.FAILED, r.status)
        assertEquals("generic_failure", r.errorCode)
        assertEquals("SMS part 2 was not sent: generic_failure (result 1)", r.errorMessage)
        assertEquals(JournalState.FAILED, dao.get("m1")!!.state)
    }

    @Test
    fun outOfOrderSentThenDeliveredReportsEachOnce() = runBlocking {
        transport.partsOf = { listOf("a", "b") }
        sender.send(claimed("m1"))
        val s = transport.sends.single()
        deliver(s.sent[1], Activity.RESULT_OK)
        deliver(s.sent[1], Activity.RESULT_OK)
        deliver(s.sent[0], Activity.RESULT_OK)
        deliver(s.delivered[0], Activity.RESULT_OK)
        deliver(s.delivered[1], Activity.RESULT_OK)
        deliver(s.delivered[1], Activity.RESULT_OK)
        assertEquals(listOf(Status.SENT, Status.DELIVERED), queue.reports.map { it.status })
        assertEquals(JournalState.DELIVERED, dao.get("m1")!!.state)
    }

    @Test
    fun deliveryReportFailureAfterSent() = runBlocking {
        sender.send(claimed("m1"))
        val s = transport.sends.single()
        deliver(s.sent[0], Activity.RESULT_OK)
        handler.handle(SmsIntents.parse(shadowOf(s.delivered[0]).savedIntent)!!, Activity.RESULT_OK, "3gpp", 0x20)
        assertEquals(JournalState.SENT, dao.get("m1")!!.state)
        handler.handle(SmsIntents.parse(shadowOf(s.delivered[0]).savedIntent)!!, Activity.RESULT_OK, "3gpp", 0x41)
        assertEquals(listOf(Status.SENT, Status.FAILED), queue.reports.map { it.status })
        assertEquals(ErrorCodes.DELIVERY_FAILED, queue.reports.last().errorCode)
        assertEquals("Delivery report for SMS part 1: not delivered (3gpp status 0x41)", queue.reports.last().errorMessage)
    }

    @Test
    fun intentsUniqueAcrossMessages() = runBlocking {
        transport.partsOf = { listOf("a", "b") }
        sender.send(claimed("m1"))
        sender.send(claimed("m2"))
        val all = transport.sends.flatMap { it.sent + it.delivered }
        assertEquals(8, all.toSet().size)
        assertEquals(8, all.map { shadowOf(it).savedIntent.data }.toSet().size)
        assertEquals(8, all.map { shadowOf(it).requestCode }.toSet().size)
    }

    @Test
    fun recordsPartsBeforeHandingOff() = runBlocking {
        transport.partsOf = { listOf("a", "b") }
        var partsAtSend = -1
        transport.onSend = { partsAtSend = runBlocking { dao.get("m1")!!.parts } }
        sender.send(claimed("m1"))
        assertEquals(2, partsAtSend)
    }
}
