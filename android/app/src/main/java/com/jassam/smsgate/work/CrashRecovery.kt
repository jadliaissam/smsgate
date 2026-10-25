package com.jassam.smsgate.work

import com.jassam.smsgate.journal.JournalDao
import com.jassam.smsgate.journal.JournalEntry
import com.jassam.smsgate.journal.JournalState
import com.jassam.smsgate.sms.ErrorCodes
import com.jassam.smsgate.sms.SmsSender
import com.jassam.smsgate.sms.StatusReportQueue
import com.jassam.smsgate.sms.enqueue

/**
 * Journal rows left behind by a dead process (D7), run by [SyncWorker].
 *
 * - SENDING with no progress for [STALE_AFTER_MS]: the SMS may or may not have left
 *   the phone. Never resent: moved SENDING → FAILED with `outcome_unknown` (guarded,
 *   so a late sent/delivery result that moved the row first wins) and reported.
 * - CLAIMED for [STALE_AFTER_MS]: claimed and journaled but never handed to Android.
 *   Resumed through [SmsSender.send], which still guards with markSending (one
 *   caller wins) and checks expiry on the server clock.
 */
object CrashRecovery {
    /**
     * How long a row may sit in SENDING/CLAIMED before it is treated as abandoned.
     * Well above the radio's own sent-result latency (seconds, retries included), so
     * a live send is not declared unknown; the sync runs every 15 minutes anyway.
     */
    const val STALE_AFTER_MS: Long = 5 * 60 * 1000L

    suspend fun run(dao: JournalDao, sender: SmsSender, reports: StatusReportQueue, now: Long, log: (String) -> Unit = {}) {
        val cutoff = now - STALE_AFTER_MS
        failStaleSending(dao, reports, dao.staleSending(cutoff), now, log)
        for (entry in dao.staleClaimed(cutoff)) {
            log("resuming claimed message ${entry.id}")
            sender.send(entry)
        }
    }

    /** Fails [rows] (a possibly stale read of SENDING rows) that are still SENDING. */
    suspend fun failStaleSending(dao: JournalDao, reports: StatusReportQueue, rows: List<JournalEntry>, now: Long, log: (String) -> Unit = {}) {
        for (entry in rows) {
            val result = dao.moveTo(
                entry.id, JournalState.SENDING, JournalState.FAILED, now,
                ErrorCodes.OUTCOME_UNKNOWN, ErrorCodes.message("The app stopped before Android reported whether the SMS was sent"),
            )
            if (result.moved) log("message ${entry.id}: ${ErrorCodes.OUTCOME_UNKNOWN}")
            result.report?.let(reports::enqueue)
        }
    }
}
