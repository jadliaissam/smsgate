package com.jassam.smsgate.journal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.jassam.smsgate.api.Status

/**
 * The message journal. Every state change is a single conditional UPDATE (or a
 * transaction around one) restricted to the source states [JournalState.canMove]
 * allows, so concurrent callers can't both win and illegal moves change nothing.
 * Times are passed in (epoch ms from an injected clock), never read here.
 */
@Dao
abstract class JournalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOrIgnore(entry: JournalEntry): Long

    /**
     * Journals a freshly claimed message (INSERT OR IGNORE on the id). False means a
     * row for this id already exists: the caller must not send.
     */
    suspend fun insertClaimed(entry: JournalEntry): Boolean {
        require(entry.state == JournalState.CLAIMED && entry.parts == 0 && entry.reported.isEmpty()) {
            "insertClaimed takes a fresh CLAIMED entry"
        }
        return insertOrIgnore(entry) != -1L
    }

    @Query("SELECT * FROM journal WHERE id = :id")
    abstract suspend fun get(id: String): JournalEntry?

    /**
     * CLAIMED → SENDING. Returns the number of rows moved: only the caller that gets
     * 1 may hand the message to SmsManager.
     */
    @Query("UPDATE journal SET state = 'SENDING', updatedAt = :now WHERE id = :id AND state = 'CLAIMED'")
    abstract suspend fun markSending(id: String, now: Long): Int

    /** Records the part count once, while SENDING and before anything is handed to Android. */
    @Query("UPDATE journal SET parts = :parts, updatedAt = :now WHERE id = :id AND state = 'SENDING' AND parts = 0")
    abstract suspend fun setParts(id: String, parts: Int, now: Long): Int

    @Query(
        "UPDATE journal SET state = :to, partsSent = :partsSent, partsDelivered = :partsDelivered, " +
            "errorCode = :errorCode, errorMessage = :errorMessage, reported = :reported, updatedAt = :now " +
            "WHERE id = :id AND state = :from",
    )
    protected abstract suspend fun write(
        id: String,
        from: JournalState,
        to: JournalState,
        partsSent: Long,
        partsDelivered: Long,
        errorCode: String?,
        errorMessage: String?,
        reported: Set<Status>,
        now: Long,
    ): Int

    /**
     * Moves the row to [to] if [JournalState.canMove] allows it from its current
     * state; otherwise changes nothing ([MoveResult.REFUSED]). Entering SENT,
     * DELIVERED, FAILED or SKIPPED_EXPIRED adds the matching status to `reported`
     * in the same transaction and returns it as the report to enqueue.
     * [errorCode]/[errorMessage] are required for FAILED and SKIPPED_EXPIRED.
     */
    @Transaction
    open suspend fun moveTo(
        id: String,
        to: JournalState,
        now: Long,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): MoveResult {
        val failing = to == JournalState.FAILED || to == JournalState.SKIPPED_EXPIRED
        require(!failing || errorCode != null) { "$to needs an error code" }
        val entry = get(id) ?: return MoveResult.REFUSED
        if (!JournalState.canMove(entry.state, to)) return MoveResult.REFUSED
        val code = if (failing) errorCode else entry.errorCode
        val message = if (failing) errorMessage else entry.errorMessage
        val report = JournalState.reportFor(to)?.takeIf { it !in entry.reported }
        val reported = if (report != null) entry.reported + report else entry.reported
        val n = write(id, entry.state, to, entry.partsSent, entry.partsDelivered, code, message, reported, now)
        if (n != 1) return MoveResult.REFUSED
        return MoveResult(true, report?.let { reportOf(id, it, code, message) })
    }

    /**
     * Applies one part result ([PartAggregator]) and, when it completes the message
     * (all sent → SENT, all delivered → DELIVERED, any failure → FAILED), returns the
     * report to enqueue, recorded in `reported` in the same transaction. Duplicate,
     * reordered, late or foreign results return null and change nothing.
     */
    @Transaction
    open suspend fun applyPartEvent(id: String, event: PartEvent, now: Long): PendingReport? {
        val entry = get(id) ?: return null
        val change = PartAggregator.apply(entry, event) ?: return null
        val moved = change.state != entry.state
        if (moved && !JournalState.canMove(entry.state, change.state)) return null
        val report = if (moved) JournalState.reportFor(change.state)?.takeIf { it !in entry.reported } else null
        val reported = if (report != null) entry.reported + report else entry.reported
        val n = write(
            id, entry.state, change.state, change.partsSent, change.partsDelivered,
            change.errorCode, change.errorMessage, reported, now,
        )
        if (n != 1) return null
        return report?.let { reportOf(id, it, change.errorCode, change.errorMessage) }
    }

    private fun reportOf(id: String, status: Status, code: String?, message: String?): PendingReport =
        if (status == Status.FAILED) PendingReport(id, status, code, message) else PendingReport(id, status)

    /** Retention: drops the body of every row created before [olderThanMs] (epoch ms), any state. */
    @Query("UPDATE journal SET body = NULL WHERE createdAt < :olderThanMs AND body IS NOT NULL")
    abstract suspend fun purgeBodies(olderThanMs: Long): Int

    /** Retention: deletes rows created before [ms] (epoch ms), any state. */
    @Query("DELETE FROM journal WHERE createdAt < :ms")
    abstract suspend fun deleteOlderThan(ms: Long): Int

    /** Crash recovery (D7): rows still SENDING with no progress since [beforeMs] (epoch ms). */
    @Query("SELECT * FROM journal WHERE state = 'SENDING' AND updatedAt < :beforeMs ORDER BY updatedAt")
    abstract suspend fun staleSending(beforeMs: Long): List<JournalEntry>
}
