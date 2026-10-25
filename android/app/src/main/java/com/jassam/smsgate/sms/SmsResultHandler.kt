package com.jassam.smsgate.sms

import android.app.Activity
import com.jassam.smsgate.journal.JournalDao
import com.jassam.smsgate.journal.PartEvent
import com.jassam.smsgate.journal.PendingReport

/** Receives one parsed part result (seam for [SmsResultReceiver]). */
fun interface SmsResultSink {
    /** [resultCode] is the broadcast's result code; [delivery] is set for delivery results only. */
    suspend fun handle(ref: PartRef, resultCode: Int, delivery: DeliveryReport?)
}

/**
 * Turns part results into journal updates and enqueues the report the journal
 * decided on (at most once per status, see [JournalDao.applyPartEvent]).
 */
class SmsResultHandler(
    private val dao: JournalDao,
    private val reports: StatusReportQueue,
    private val now: () -> Long,
) : SmsResultSink {
    override suspend fun handle(ref: PartRef, resultCode: Int, delivery: DeliveryReport?) {
        apply(ref, resultCode, delivery)
    }

    suspend fun apply(ref: PartRef, resultCode: Int, delivery: DeliveryReport?): PendingReport? {
        val report = dao.applyPartEvent(ref.id, event(ref, resultCode, delivery), now())
        report?.let(reports::enqueue)
        return report
    }

    internal fun event(ref: PartRef, resultCode: Int, delivery: DeliveryReport?): PartEvent {
        val part = ref.part
        return when (ref.kind) {
            PartKind.SENT ->
                if (resultCode == Activity.RESULT_OK) {
                    PartEvent.Sent(part)
                } else {
                    val code = ErrorCodes.forSendResult(resultCode)
                    PartEvent.SendFailed(part, code, ErrorCodes.message("SMS part ${part + 1} was not sent: $code (result $resultCode)"))
                }
            PartKind.DELIVERED -> when (DeliveryStatus.outcome(resultCode, delivery)) {
                DeliveryOutcome.DELIVERED -> PartEvent.Delivered(part)
                DeliveryOutcome.PENDING -> PartEvent.DeliveryPending(part)
                DeliveryOutcome.FAILED -> PartEvent.DeliveryFailed(
                    part,
                    ErrorCodes.DELIVERY_FAILED,
                    ErrorCodes.message(
                        "Delivery report for SMS part ${part + 1}: not delivered " +
                            "(${delivery?.format ?: "3gpp"} status 0x${Integer.toHexString(delivery?.status ?: 0)})",
                    ),
                )
            }
        }
    }
}
