package com.jassam.smsgate.work

import com.jassam.smsgate.api.Status
import com.jassam.smsgate.journal.JournalDao
import com.jassam.smsgate.journal.JournalEntry
import com.jassam.smsgate.journal.JournalState
import com.jassam.smsgate.sms.ErrorCodes
import com.jassam.smsgate.sms.SmsSender
import com.jassam.smsgate.sms.StatusReportQueue
import com.jassam.smsgate.sms.enqueue

/**
 * Journal rows left behind by a dead process (D7), run by [SyncWorker], and rows a
 * revoked device can no longer act on ([abandonClaimed], run by [Revocation]).
 *
 * - SENDING with no progress for [STALE_AFTER_MS]: the SMS may or may not have left
 *   the phone. Never resent: moved SENDING → FAILED with `outcome_unknown` (guarded,
 *   so a late sent/delivery result that moved the row first wins) and reported.
 * - CLAIMED for [STALE_AFTER_MS]: claimed and journaled but never handed to Android.
 *   Resumed through [SmsSender.send], which still guards with markSending (one
 *   caller wins) and checks expiry on the server clock, so the caller must only
 *   resume once that clock is synced in this process.
 *
 * Staleness is measured on the phone's wall clock (journal timestamps): a user moving
 * the clock can make a row look stale early (SENDING → reported `outcome_unknown`,
 * never resent) or late (recovered at a later sync). Neither can cause a second send.
 */
object CrashRecovery {
    /**
     * How long a row may sit in SENDING/CLAIMED before it is treated as abandoned.
     * Well above the radio's own sent-result latency (seconds, retries included), so
     * a live send is not declared unknown; the sync runs every 15 minutes anyway.
     */
    const val STALE_AFTER_MS: Long = 5 * 60 * 1000L

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

    /** Resumes CLAIMED rows untouched since [cutoff]. Only with a synced server clock. */
    suspend fun resumeStaleClaimed(dao: JournalDao, sender: SmsSender, cutoff: Long, log: (String) -> Unit = {}) {
        for (entry in dao.staleClaimed(cutoff)) {
            log("resuming claimed message ${entry.id}")
            sender.send(entry)
        }
    }

    /**
     * On revocation: every row still CLAIMED (never handed to Android) becomes FAILED
     * `device_revoked` and is acked locally, so neither this pairing's recovery nor a
     * later pairing (a different device) ever sends it, and nobody reports it to a
     * server that no longer accepts this device. Guarded per row: a send that wins
     * markSending first goes ahead (its row stays SENDING and is settled as
     * `outcome_unknown` by the next pairing's recovery).
     */
    suspend fun abandonClaimed(dao: JournalDao, now: Long) {
        for (entry in dao.staleClaimed(Long.MAX_VALUE)) {
            val result = dao.moveTo(
                entry.id, JournalState.CLAIMED, JournalState.FAILED, now,
                ErrorCodes.DEVICE_REVOKED, "The phone was unpaired before the SMS was sent",
            )
            if (result.moved) dao.markAcked(entry.id, Status.FAILED)
        }
    }
}
