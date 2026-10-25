package com.jassam.smsgate.ui.log

import com.jassam.smsgate.api.Status
import com.jassam.smsgate.journal.JournalEntry
import com.jassam.smsgate.journal.JournalState
import com.jassam.smsgate.journal.JournalSummary
import com.jassam.smsgate.ui.theme.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFormatTest {
    private fun entry(
        state: JournalState,
        parts: Int = 0,
        sent: Long = 0,
        delivered: Long = 0,
        body: String? = "Your code is 4242",
        code: String? = null,
        reported: Set<Status> = emptySet(),
        acked: Set<Status> = emptySet(),
    ) = JournalEntry(
        id = "m1", to = "+212612345689", body = body, simSlot = 1, expiresAt = 9_000, state = state, errorCode = code,
        parts = parts, partsSent = sent, partsDelivered = delivered, createdAt = 1_000, updatedAt = 5_000,
        reported = reported, acked = acked,
    )

    @Test
    fun masksTheMiddleOfANumberGroupedInThrees() {
        assertEquals("+212 6** *** *89", LogFormat.maskNumber("+212612345689"))
        assertEquals("+336 1** *** 78", LogFormat.maskNumber("+33612345678"))
        assertEquals("061 2** **89", LogFormat.maskNumber("0612345689"))
        assertEquals("+212 6** *** *89", LogFormat.maskNumber("+212 6 12-34-56 89"))
    }

    @Test
    fun shortNumbersRevealLess() {
        assertEquals("12* *** 78", LogFormat.maskNumber("12345678")) // 8 digits: 2 + 2 kept
        assertEquals("*** **67", LogFormat.maskNumber("1234567")) // 7 digits: last 2 only
        assertEquals("*** *56", LogFormat.maskNumber("123456"))
        assertEquals("38383", LogFormat.maskNumber("38383")) // short code, as on the dashboard
        assertEquals("***", LogFormat.maskNumber("INFO"))
        assertEquals("", LogFormat.maskNumber(""))
    }

    @Test
    fun aMaskedNumberNeverShowsTheHiddenDigits() {
        val masked = LogFormat.maskNumber("+212612345689")
        assertFalse(masked.replace(" ", "").contains("1234"))
        assertEquals(6, masked.count { it == '*' })
    }

    @Test
    fun statusesUseTheDashboardMap() {
        assertEquals(LogStatus.Dispatched, LogStatus.of(JournalState.CLAIMED))
        assertEquals(LogStatus.Dispatched, LogStatus.of(JournalState.SENDING))
        assertEquals(LogStatus.Sent, LogStatus.of(JournalState.SENT))
        assertEquals(LogStatus.Delivered, LogStatus.of(JournalState.DELIVERED))
        assertEquals(LogStatus.Failed, LogStatus.of(JournalState.FAILED))
        assertEquals(LogStatus.Expired, LogStatus.of(JournalState.SKIPPED_EXPIRED))
        assertEquals(
            listOf(Tone.Neutral, Tone.Brand, Tone.Brand, Tone.Success, Tone.Danger, Tone.Warning),
            LogStatus.entries.map { it.tone },
        )
    }

    @Test
    fun anItemCarriesTheMaskedNumberAndClaimTime() {
        val row = JournalSummary("m1", "+212612345689", JournalState.DELIVERED, 0, null, 1, 1, 1, 1_000, 5_000)
        assertEquals(LogItem("m1", "+212 6** *** *89", LogStatus.Delivered, 1_000, 0), LogFormat.item(row))
    }

    @Test
    fun aDeliveredTimeline() {
        val d = LogFormat.detail(entry(JournalState.DELIVERED, parts = 2, sent = 3, delivered = 3, reported = setOf(Status.SENT, Status.DELIVERED), acked = setOf(Status.SENT, Status.DELIVERED)))
        assertEquals(
            listOf(
                TimelineStep.Claimed(1_000),
                TimelineStep.Handed(null, 2),
                TimelineStep.Sent(null, 2, 2),
                TimelineStep.Delivered(5_000, 2, 2),
            ),
            d.timeline,
        )
        assertEquals(ReportState.Confirmed, d.report)
        assertEquals("Your code is 4242", d.body)
        assertFalse(d.bodyRemoved)
    }

    @Test
    fun aFailedTimelineHasTheCodeAndFriendlyText() {
        val d = LogFormat.detail(entry(JournalState.FAILED, parts = 3, sent = 1, code = "no_service", reported = setOf(Status.FAILED)))
        assertEquals(TimelineStep.Failed(5_000, "no_service", FriendlyError.NoService), d.timeline.last())
        assertEquals(TimelineStep.Sent(null, 1, 3), d.timeline[2])
        assertEquals(ReportState.Pending, d.report)
    }

    @Test
    fun failedBeforeHandingOverHasNoSendingSteps() {
        val d = LogFormat.detail(entry(JournalState.FAILED, code = "sim_unavailable"))
        assertEquals(listOf(TimelineStep.Claimed(1_000), TimelineStep.Failed(5_000, "sim_unavailable", FriendlyError.SimUnavailable)), d.timeline)
    }

    @Test
    fun skippedExpiredAndInFlightRows() {
        assertEquals(TimelineStep.Expired(5_000), LogFormat.detail(entry(JournalState.SKIPPED_EXPIRED, code = "expired_on_device")).timeline.last())
        val sending = LogFormat.detail(entry(JournalState.SENDING, parts = 1))
        assertEquals(listOf(TimelineStep.Claimed(1_000), TimelineStep.Handed(5_000, 1)), sending.timeline)
        assertEquals(ReportState.None, sending.report)
        assertEquals(listOf(TimelineStep.Claimed(1_000)), LogFormat.detail(entry(JournalState.CLAIMED)).timeline)
    }

    @Test
    fun aPurgedBodyIsNotShownAndSaysSo() {
        val d = LogFormat.detail(entry(JournalState.SENT, parts = 1, sent = 1, body = null))
        assertNull(d.body)
        assertTrue(d.bodyRemoved)
        assertFalse(d.toString().contains("4242"))
    }

    @Test
    fun theDetailNeverPrintsTheBodyOrNumber() {
        val text = LogFormat.detail(entry(JournalState.SENT, parts = 1, sent = 1)).toString()
        assertFalse(text.contains("4242"))
        assertFalse(text.contains("612345"))
    }

    @Test
    fun friendlyErrors() {
        assertEquals(FriendlyError.OutcomeUnknown, FriendlyError.of("outcome_unknown"))
        assertEquals(FriendlyError.ExpiredOnDevice, FriendlyError.of("expired_on_device"))
        assertEquals(FriendlyError.RadioOff, FriendlyError.of("radio_off"))
        assertEquals(FriendlyError.ShortCode, FriendlyError.of("short_code_never_allowed"))
        assertEquals(FriendlyError.Other, FriendlyError.of("something_new"))
        assertEquals(FriendlyError.Other, FriendlyError.of(null))
    }
}
