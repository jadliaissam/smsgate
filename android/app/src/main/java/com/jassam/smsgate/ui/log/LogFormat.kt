package com.jassam.smsgate.ui.log

import androidx.compose.runtime.Immutable
import com.jassam.smsgate.journal.JournalEntry
import com.jassam.smsgate.journal.JournalState
import com.jassam.smsgate.journal.JournalSummary
import com.jassam.smsgate.sms.ErrorCodes
import com.jassam.smsgate.ui.theme.Tone

/**
 * The dashboard's message statuses (web/src/ui/messageStatus.ts) and their chip tones:
 * queued neutral, dispatched/sent brand, delivered success, failed danger, expired warning.
 */
enum class LogStatus(val tone: Tone) {
    Queued(Tone.Neutral),
    Dispatched(Tone.Brand),
    Sent(Tone.Brand),
    Delivered(Tone.Success),
    Failed(Tone.Danger),
    Expired(Tone.Warning),
    ;

    companion object {
        /**
         * A journal state in the dashboard's words. The journal only holds claimed
         * messages, so nothing here is Queued; CLAIMED and SENDING are "dispatched"
         * (picked up, being sent), and a message skipped because it expired on the
         * phone shows as Expired.
         */
        fun of(state: JournalState): LogStatus = when (state) {
            JournalState.CLAIMED, JournalState.SENDING -> Dispatched
            JournalState.SENT -> Sent
            JournalState.DELIVERED -> Delivered
            JournalState.FAILED -> Failed
            JournalState.SKIPPED_EXPIRED -> Expired
        }
    }
}

/** One row of the log list. */
@Immutable
data class LogItem(val id: String, val maskedTo: String, val status: LogStatus, val atMs: Long, val simSlot: Int?)

/** Friendly explanations for the error codes the phone reports (codes stay visible too). */
enum class FriendlyError {
    NoService, RadioOff, Generic, LimitExceeded, ShortCode, Permission, SimUnavailable, NoDefaultSim,
    InvalidDestination, ExpiredOnDevice, OutcomeUnknown, DeviceRevoked, DeliveryFailed, BodyMissing,
    TooManyParts, Fdn, NoSmsService, Other;

    companion object {
        fun of(code: String?): FriendlyError = when (code) {
            null -> Other
            "no_service", "ril_network_reject", "network_reject", "network_error" -> NoService
            "radio_off", "radio_not_available", "ril_radio_not_available" -> RadioOff
            "generic_failure", ErrorCodes.SEND_EXCEPTION -> Generic
            "limit_exceeded", "ril_request_rate_limited" -> LimitExceeded
            "short_code_not_allowed", "short_code_never_allowed" -> ShortCode
            ErrorCodes.PERMISSION_DENIED -> Permission
            ErrorCodes.SIM_UNAVAILABLE -> SimUnavailable
            ErrorCodes.NO_DEFAULT_SIM -> NoDefaultSim
            ErrorCodes.INVALID_DESTINATION -> InvalidDestination
            ErrorCodes.EXPIRED_ON_DEVICE -> ExpiredOnDevice
            ErrorCodes.OUTCOME_UNKNOWN -> OutcomeUnknown
            ErrorCodes.DEVICE_REVOKED -> DeviceRevoked
            ErrorCodes.DELIVERY_FAILED -> DeliveryFailed
            ErrorCodes.BODY_MISSING -> BodyMissing
            ErrorCodes.TOO_MANY_PARTS -> TooManyParts
            "fdn_check_failure" -> Fdn
            ErrorCodes.NO_SMS_SERVICE -> NoSmsService
            else -> Other
        }
    }
}

/** One step of the detail timeline. [atMs] is null when the journal doesn't keep that time. */
@Immutable
sealed interface TimelineStep {
    val atMs: Long?

    /** Claimed from the server and journaled by this phone. */
    data class Claimed(override val atMs: Long) : TimelineStep

    /** Handed to Android in [parts] SMS parts (0 = not known yet). */
    data class Handed(override val atMs: Long?, val parts: Int) : TimelineStep

    data class Sent(override val atMs: Long?, val sent: Int, val parts: Int) : TimelineStep

    data class Delivered(override val atMs: Long?, val delivered: Int, val parts: Int) : TimelineStep

    data class Failed(override val atMs: Long, val code: String?, val friendly: FriendlyError) : TimelineStep

    /** Not sent: the validity window had passed. */
    data class Expired(override val atMs: Long) : TimelineStep
}

/** Whether the final status reached the server. */
enum class ReportState { None, Pending, Confirmed }

/** Everything the detail sheet shows. */
@Immutable
data class LogDetail(
    val id: String,
    val maskedTo: String,
    val status: LogStatus,
    val simSlot: Int?,
    /** The message text while the journal still holds it (purged 24 h after the claim). */
    val body: String?,
    val bodyRemoved: Boolean,
    val expiresAtMs: Long,
    val timeline: List<TimelineStep>,
    val report: ReportState,
) {
    override fun toString(): String =
        "LogDetail(id=$id, maskedTo=$maskedTo, status=$status, simSlot=$simSlot, body=${body?.let { "<${it.length} chars>" }}, " +
            "bodyRemoved=$bodyRemoved, expiresAtMs=$expiresAtMs, timeline=$timeline, report=$report)"
}

/** Pure log mapping (unit-tested). */
object LogFormat {
    /**
     * A phone number with its middle hidden, grouped in threes: `+212612345689` →
     * `+212 6** *** *89`. Keeps a leading `+`, the first 4 digits (country and network,
     * for numbers of 10+ digits; 2 for 8–9 digits; none below) and the last 2.
     * Up to 5 digits (short codes) are shown as they are; anything without digits is
     * masked entirely.
     */
    fun maskNumber(raw: String): String {
        val digits = raw.filter { it in '0'..'9' }
        val plus = if (raw.trimStart().startsWith("+")) "+" else ""
        if (digits.isEmpty()) return if (raw.isEmpty()) "" else "***"
        if (digits.length <= 5) return plus + digits
        val head = when {
            digits.length >= 10 -> 4
            digits.length >= 8 -> 2
            else -> 0
        }
        val tail = 2
        val front = digits.take(head) + "*".repeat(digits.length - head - tail)
        val groups = front.chunked(3).toMutableList()
        val last = digits.takeLast(tail)
        if (groups.last().length < 3) groups[groups.lastIndex] = groups.last() + last else groups += last
        return plus + groups.joinToString(" ")
    }

    fun item(row: JournalSummary): LogItem =
        LogItem(row.id, maskNumber(row.to), LogStatus.of(row.state), row.createdAt, row.simSlot)

    fun detail(e: JournalEntry): LogDetail = LogDetail(
        id = e.id,
        maskedTo = maskNumber(e.to),
        status = LogStatus.of(e.state),
        simSlot = e.simSlot,
        body = e.body,
        // A row without a body has passed the purge (24 h after the claim); a body can't be missing otherwise.
        bodyRemoved = e.body == null,
        expiresAtMs = e.expiresAt,
        timeline = timeline(e),
        report = report(e),
    )

    fun timeline(e: JournalEntry): List<TimelineStep> = buildList {
        add(TimelineStep.Claimed(e.createdAt))
        val sent = e.partsSent.countOneBits()
        val delivered = e.partsDelivered.countOneBits()
        val handed = e.state in setOf(JournalState.SENDING, JournalState.SENT, JournalState.DELIVERED) ||
            (e.state == JournalState.FAILED && e.parts > 0)
        if (handed) add(TimelineStep.Handed(if (e.state == JournalState.SENDING) e.updatedAt else null, e.parts))
        if (e.state == JournalState.SENT || e.state == JournalState.DELIVERED || sent > 0) {
            add(TimelineStep.Sent(if (e.state == JournalState.SENT) e.updatedAt else null, sent, e.parts))
        }
        if (e.state == JournalState.DELIVERED || delivered > 0) {
            add(TimelineStep.Delivered(if (e.state == JournalState.DELIVERED) e.updatedAt else null, delivered, e.parts))
        }
        when (e.state) {
            JournalState.FAILED -> add(TimelineStep.Failed(e.updatedAt, e.errorCode, FriendlyError.of(e.errorCode)))
            JournalState.SKIPPED_EXPIRED -> add(TimelineStep.Expired(e.updatedAt))
            else -> Unit
        }
    }

    private fun report(e: JournalEntry): ReportState {
        val status = JournalState.reportFor(e.state) ?: return ReportState.None
        return if (status in e.acked) ReportState.Confirmed else ReportState.Pending
    }
}
