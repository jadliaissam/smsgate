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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
    private var serverClock = 2_000L
    private var phoneState = true
    private var defaultSub = 33
    private var simsError: Exception? = null
    private var onSims: () -> Unit = {}
    private val logs = mutableListOf<String>()
    private lateinit var sender: SmsSender
    private lateinit var handler: SmsResultHandler

    private val number = "+212600000001"
    private val body = "Your code is 4242"

    @Before
    fun setUp() {
        db = inMemoryJournal()
        dao = db.journalDao()
        sender = newSender(transport, queue)
        handler = SmsResultHandler(dao, queue) { clock }
    }

    @After
    fun tearDown() = db.close()

    private fun newSender(t: SmsTransport, q: StatusReportQueue) = SmsSender(
        dao = dao,
        transport = t,
        sims = { onSims(); simsError?.let { throw it }; sims },
        phoneStateGranted = { phoneState },
        defaultSmsSubscriptionId = { defaultSub },
        intents = { SmsIntents.pendingIntent(app, it) },
        reports = q,
        now = { clock },
        serverNow = { serverClock },
        log = { synchronized(logs) { logs += it } },
    )

    private suspend fun claimed(id: String, simSlot: Int? = null, text: String? = body) =
        claimedEntry(id, body = text, simSlot = simSlot).also { assertTrue(dao.insertClaimed(it)) }

    private suspend fun deliver(pi: PendingIntent, resultCode: Int) {
        val ref = SmsIntents.parse(shadowOf(pi).savedIntent)!!
        handler.handle(ref, resultCode, if (ref.kind == PartKind.DELIVERED) DeliveryReport(null, false, null) else null)
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
        assertEquals(SendOutcome.HandedOff(3), sender.send(entry))
        val row = dao.get("m1")!!
        assertEquals(JournalState.SENDING, row.state)
        assertEquals(3, row.parts)
        val sent = transport.sends.single()
        assertEquals(number, sent.to)
        assertEquals(listOf("a", "b", "c"), sent.parts)
        assertEquals(listOf(33), transport.channelRequests)
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
        assertEquals(SendOutcome.HandedOff(1), sender.send(entry))
        assertEquals(SendOutcome.NotSent, sender.send(entry))
        assertEquals(SendOutcome.NotSent, sender.send(claimedEntry("unknown")))
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
        assertEquals(1, all.count { it is SendOutcome.HandedOff })
        assertEquals(callers - 1, all.count { it == SendOutcome.NotSent })
        assertEquals(1, transport.sends.size)
    }

    @Test
    fun simSlotMapsToItsSubscription() = runBlocking {
        sender.send(claimed("m0", simSlot = 0))
        sender.send(claimed("m1", simSlot = 1))
        sender.send(claimed("mn", simSlot = null))
        assertEquals(listOf(11, 22, 33), transport.channelRequests)
        assertEquals(3, transport.sends.size)
    }

    @Test
    fun missingSimFailsWithoutFallingBack() = runBlocking {
        sims = listOf(Sim(0, 11, "Orange"))
        assertEquals(SendOutcome.Failed(ErrorCodes.SIM_UNAVAILABLE), sender.send(claimed("m1", simSlot = 1)))
        sims = emptyList()
        assertEquals(SendOutcome.Failed(ErrorCodes.SIM_UNAVAILABLE), sender.send(claimed("m2", simSlot = 0)))
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
        val s = newSender(t, q)
        val id = "m-$code-${System.nanoTime()}"
        val result = s.send(claimed(id))
        assertEquals(SendOutcome.Failed(code), result)
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
    fun missingOrBlankBodyFails() = runBlocking {
        assertEquals(SendOutcome.Failed(ErrorCodes.BODY_MISSING), sender.send(claimed("m1", text = null)))
        assertEquals(SendOutcome.Failed(ErrorCodes.BODY_MISSING), sender.send(claimed("m2", text = "")))
        assertEquals(SendOutcome.Failed(ErrorCodes.BODY_MISSING), sender.send(claimed("m3", text = " \n\t ")))
        for (id in listOf("m1", "m2", "m3")) assertEquals(ErrorCodes.BODY_MISSING, dao.get(id)!!.errorCode)
        assertEquals(3, queue.reports.size)
        assertTrue(transport.sends.isEmpty())
    }

    @Test
    fun nullSlotUsesTheDefaultSmsSubscription() = runBlocking {
        defaultSub = 22
        sims = emptyList()
        phoneState = false
        assertEquals(SendOutcome.HandedOff(1), sender.send(claimed("m1")))
        assertEquals(listOf(22), transport.channelRequests)
    }

    @Test
    fun nullSlotWithoutDefaultUsesTheOnlyActiveSim() = runBlocking {
        defaultSub = -1
        sims = listOf(Sim(1, 22, "inwi"))
        assertEquals(SendOutcome.HandedOff(1), sender.send(claimed("m1")))
        defaultSub = Int.MAX_VALUE // DEFAULT_SUBSCRIPTION_ID placeholder is not a real subscription either
        assertEquals(SendOutcome.HandedOff(1), sender.send(claimed("m2")))
        assertEquals(listOf(22, 22), transport.channelRequests)
    }

    @Test
    fun nullSlotWithoutDefaultAndSeveralOrNoSimsFails() = runBlocking {
        defaultSub = -1
        assertEquals(SendOutcome.Failed(ErrorCodes.NO_DEFAULT_SIM), sender.send(claimed("two")))
        sims = emptyList()
        assertEquals(SendOutcome.Failed(ErrorCodes.NO_DEFAULT_SIM), sender.send(claimed("none")))
        sims = listOf(Sim(0, 11, "Orange"))
        phoneState = false
        assertEquals(SendOutcome.Failed(ErrorCodes.PERMISSION_DENIED), sender.send(claimed("denied")))
        assertTrue(transport.channelRequests.isEmpty())
        assertEquals(JournalState.FAILED, dao.get("two")!!.state)
        assertEquals(listOf(ErrorCodes.NO_DEFAULT_SIM, ErrorCodes.NO_DEFAULT_SIM, ErrorCodes.PERMISSION_DENIED), queue.reports.map { it.errorCode })
    }

    @Test
    fun explicitSlotWithoutPhoneStatePermissionIsPermissionDenied() = runBlocking {
        phoneState = false
        assertEquals(SendOutcome.Failed(ErrorCodes.PERMISSION_DENIED), sender.send(claimed("m1", simSlot = 0)))
        assertTrue(transport.channelRequests.isEmpty())
        assertEquals(JournalState.FAILED, dao.get("m1")!!.state)
    }

    @Test
    fun simLookupThrowingFailsInsteadOfThrowing() = runBlocking {
        simsError = SecurityException("READ_PHONE_STATE")
        assertEquals(SendOutcome.Failed(ErrorCodes.PERMISSION_DENIED), sender.send(claimed("m1", simSlot = 0)))
        simsError = IllegalStateException("binder died")
        assertEquals(SendOutcome.Failed(ErrorCodes.SEND_EXCEPTION), sender.send(claimed("m2", simSlot = 0)))
        defaultSub = -1
        assertEquals(SendOutcome.Failed(ErrorCodes.SEND_EXCEPTION), sender.send(claimed("m3")))
        for (id in listOf("m1", "m2", "m3")) assertEquals(JournalState.FAILED, dao.get(id)!!.state)
        assertEquals(3, queue.reports.size)
        assertTrue(transport.channelRequests.isEmpty())
    }

    @Test
    fun expiredMessageIsSkippedNeverSent() = runBlocking {
        val entry = claimed("m1") // expiresAt = 61_000
        serverClock = entry.expiresAt // boundary: expiresAt <= serverNow is expired
        assertEquals(SendOutcome.Expired, sender.send(entry))
        val row = dao.get("m1")!!
        assertEquals(JournalState.SKIPPED_EXPIRED, row.state)
        assertEquals(ErrorCodes.EXPIRED_ON_DEVICE, row.errorCode)
        assertEquals(listOf(Status.FAILED to ErrorCodes.EXPIRED_ON_DEVICE), queue.reports.map { it.status to it.errorCode })
        assertEquals(SendOutcome.NotSent, sender.send(entry))
        serverClock = 0
        assertEquals("a later in-window retry can't revive it", SendOutcome.NotSent, sender.send(entry))
        assertTrue(transport.channelRequests.isEmpty())
        assertEquals(1, queue.reports.size)
    }

    @Test
    fun oneMillisecondBeforeExpirySends() = runBlocking {
        val entry = claimed("m1")
        serverClock = entry.expiresAt - 1
        clock = entry.expiresAt + 10_000 // the local clock is irrelevant to expiry
        assertEquals(SendOutcome.HandedOff(1), sender.send(entry))
    }

    @Test
    fun expiredCheckDoesNotTouchARowSomeoneElseOwns() = runBlocking {
        val entry = claimed("m1")
        assertEquals(SendOutcome.HandedOff(1), sender.send(entry))
        serverClock = entry.expiresAt + 1
        assertEquals(SendOutcome.NotSent, sender.send(entry))
        assertEquals(JournalState.SENDING, dao.get("m1")!!.state)
        assertTrue(queue.reports.isEmpty())
    }

    @Test
    fun cancellingTheCallerMidSendStillCompletesTheHandOff() = runBlocking {
        val inSims = CountDownLatch(1)
        val release = CountDownLatch(1)
        onSims = { inSims.countDown(); release.await(5, TimeUnit.SECONDS) }
        val entry = claimed("m1", simSlot = 0)
        val job = launch(Dispatchers.IO) { sender.send(entry) }
        assertTrue(inSims.await(5, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        job.join()
        val row = dao.get("m1")!!
        assertEquals(JournalState.SENDING, row.state)
        assertEquals(1, row.parts)
        assertEquals(1, transport.sends.size)
    }

    @Test
    fun alreadyCancelledCallerDoesNothing() = runBlocking {
        val entry = claimed("m1")
        var thrown: Throwable? = null
        val job = launch(Dispatchers.IO) {
            coroutineContext.job.cancel()
            thrown = runCatching { sender.send(entry) }.exceptionOrNull()
        }
        job.join()
        assertTrue(thrown is CancellationException)
        assertEquals(JournalState.CLAIMED, dao.get("m1")!!.state)
        assertTrue(transport.sends.isEmpty())
    }

    @Test
    fun unparseableDeliveryReportLeavesThePartPending() = runBlocking {
        sender.send(claimed("m1"))
        val s = transport.sends.single()
        deliver(s.sent[0], Activity.RESULT_OK)
        val ref = SmsIntents.parse(shadowOf(s.delivered[0]).savedIntent)!!
        handler.handle(ref, Activity.RESULT_OK, DeliveryReport("3gpp", pduPresent = true, status = null))
        assertEquals(JournalState.SENT, dao.get("m1")!!.state)
        assertEquals(listOf(Status.SENT), queue.reports.map { it.status })
    }

    @Test
    fun sixtyFourPartsIsTheLimit() = runBlocking {
        transport.partsOf = { b -> List(64) { b } }
        assertEquals(SendOutcome.HandedOff(64), sender.send(claimed("m1")))
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
        handler.handle(SmsIntents.parse(shadowOf(s.delivered[0]).savedIntent)!!, Activity.RESULT_OK, DeliveryReport("3gpp", true, 0x20))
        assertEquals(JournalState.SENT, dao.get("m1")!!.state)
        handler.handle(SmsIntents.parse(shadowOf(s.delivered[0]).savedIntent)!!, Activity.RESULT_OK, DeliveryReport("3gpp", true, 0x41))
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
