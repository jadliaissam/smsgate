package com.jassam.smsgate.journal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.jassam.smsgate.api.Status
import kotlinx.coroutines.flow.Flow

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
        require(entry.state == JournalState.CLAIMED && entry.parts == 0 && entry.reported.isEmpty() && entry.acked.isEmpty()) {
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
     * Moves the row from [expectedFrom] to [to], only if the row is still in
     * [expectedFrom] and [JournalState.canMove] allows the move; otherwise changes
     * nothing ([MoveResult.REFUSED]). The expected source makes a decision taken on
     * a stale read (e.g. crash recovery of a SENDING row whose late result already
     * moved it to SENT) a no-op. Entering SENT, DELIVERED, FAILED or SKIPPED_EXPIRED
     * adds the matching status to `reported` in the same transaction and returns it
     * as the report to enqueue. [errorCode]/[errorMessage] are required for FAILED
     * and SKIPPED_EXPIRED.
     */
    @Transaction
    open suspend fun moveTo(
        id: String,
        expectedFrom: JournalState,
        to: JournalState,
        now: Long,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): MoveResult {
        val failing = to == JournalState.FAILED || to == JournalState.SKIPPED_EXPIRED
        require(!failing || errorCode != null) { "$to needs an error code" }
        if (!JournalState.canMove(expectedFrom, to)) return MoveResult.REFUSED
        val entry = get(id) ?: return MoveResult.REFUSED
        if (entry.state != expectedFrom) return MoveResult.REFUSED
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

    @Query("UPDATE journal SET acked = :acked WHERE id = :id")
    protected abstract suspend fun writeAcked(id: String, acked: Set<Status>): Int

    /**
     * Records that the server confirmed [status] for [id] (a 200, or a 409
     * invalid_transition the reporter treats as final). Idempotent; only statuses in
     * `reported` can be acked. Returns true when this call added it.
     */
    @Transaction
    open suspend fun markAcked(id: String, status: Status): Boolean {
        val entry = get(id) ?: return false
        if (status !in entry.reported || status in entry.acked) return false
        return writeAcked(id, entry.acked + status) == 1
    }

    // The converter writes sets in a canonical order and acked ⊆ reported, so equal sets are equal strings.
    @Query("SELECT * FROM journal WHERE reported != acked ORDER BY updatedAt")
    protected abstract suspend fun rowsWithUnackedReports(): List<JournalEntry>

    /**
     * Statuses decided (`reported`) but not confirmed by the server (`acked`), with
     * the error code/message a `failed` report needs, oldest first. Re-enqueue them
     * (unique per (id, status)) to close the gap between a commit and the enqueue.
     */
    suspend fun unackedReports(): List<PendingReport> =
        rowsWithUnackedReports().flatMap { e ->
            (e.reported - e.acked).sortedBy { it.ordinal }.map { reportOf(e.id, it, e.errorCode, e.errorMessage) }
        }

    private fun reportOf(id: String, status: Status, code: String?, message: String?): PendingReport =
        if (status == Status.FAILED) PendingReport(id, status, code, message) else PendingReport(id, status)

    /** Retention: drops the body of every row created before [olderThanMs] (epoch ms), any state. */
    @Query("UPDATE journal SET body = NULL WHERE createdAt < :olderThanMs AND body IS NOT NULL")
    abstract suspend fun purgeBodies(olderThanMs: Long): Int

    /** Retention: deletes rows created before [ms] (epoch ms), any state. */
    @Query("DELETE FROM journal WHERE createdAt < :ms")
    abstract suspend fun deleteOlderThan(ms: Long): Int

    /**
     * Resume: rows still CLAIMED (claimed and journaled but never handed to
     * Android) with no progress since [beforeMs] (epoch ms). Safe to pass to
     * SmsSender.send again: markSending still guards, and expiry is checked there.
     */
    @Query("SELECT * FROM journal WHERE state = 'CLAIMED' AND updatedAt < :beforeMs ORDER BY updatedAt")
    abstract suspend fun staleClaimed(beforeMs: Long): List<JournalEntry>

    /**
     * The log list: the newest [limit] rows (claim time, then id; ids are UUIDv7 so
     * they break ties in time order), without bodies. Re-emits on every journal change.
     */
    @Query(
        "SELECT id, to_number, state, simSlot, errorCode, parts, partsSent, partsDelivered, createdAt, updatedAt " +
            "FROM journal ORDER BY createdAt DESC, id DESC LIMIT :limit",
    )
    abstract fun observeRecent(limit: Int): Flow<List<JournalSummary>>

    /** One row with its body (the log's detail sheet); null once it is gone. */
    @Query("SELECT * FROM journal WHERE id = :id")
    abstract fun observe(id: String): Flow<JournalEntry?>

    /**
     * Counts of messages claimed in `[fromMs, toMs)` (epoch ms): sent (SENT or
     * DELIVERED), delivered, failed (FAILED or SKIPPED_EXPIRED). See [DayCounts].
     */
    @Query(
        "SELECT COUNT(CASE WHEN state IN ('SENT', 'DELIVERED') THEN 1 END) AS sent, " +
            "COUNT(CASE WHEN state = 'DELIVERED' THEN 1 END) AS delivered, " +
            "COUNT(CASE WHEN state IN ('FAILED', 'SKIPPED_EXPIRED') THEN 1 END) AS failed " +
            "FROM journal WHERE createdAt >= :fromMs AND createdAt < :toMs",
    )
    abstract fun observeCounts(fromMs: Long, toMs: Long): Flow<DayCounts>

    /** Crash recovery (D7): rows still SENDING with no progress since [beforeMs] (epoch ms). */
    @Query("SELECT * FROM journal WHERE state = 'SENDING' AND updatedAt < :beforeMs ORDER BY updatedAt")
    abstract suspend fun staleSending(beforeMs: Long): List<JournalEntry>
}
