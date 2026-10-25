package com.jassam.smsgate.journal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jassam.smsgate.api.ClaimedMessage
import com.jassam.smsgate.api.Status

/**
 * One claimed message. The row is written (state [JournalState.CLAIMED], with its
 * body) before SmsManager is called, and its primary key is the message id, so a
 * second claim/push/worker for the same id can never send again.
 *
 * Times are epoch milliseconds from an injected clock. [partsSent] and
 * [partsDelivered] are bitmasks (bit n = part n), so a duplicated or reordered
 * broadcast can't be counted twice; at most [MAX_PARTS] parts.
 * [reported] holds the statuses decided and handed to the report queue, written in
 * the same transaction that decides to report, so each is enqueued at most once.
 * [acked] holds the subset the server has confirmed ([JournalDao.markAcked]);
 * `reported - acked` is what must still be (re-)enqueued ([JournalDao.unackedReports]).
 */
@Entity(
    tableName = "journal",
    indices = [Index("createdAt"), Index("state", "updatedAt")],
)
data class JournalEntry(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "to_number") val to: String,
    /** Nulled 24 h after the claim ([JournalDao.purgeBodies]). */
    val body: String?,
    /** SIM slot from the claim; null = the phone's default SMS SIM. */
    val simSlot: Int?,
    val expiresAt: Long,
    val state: JournalState = JournalState.CLAIMED,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** Number of SMS parts, set when the message is handed to SmsManager (0 before). */
    val parts: Int = 0,
    val partsSent: Long = 0,
    val partsDelivered: Long = 0,
    val createdAt: Long,
    /** Last state or part change: [JournalDao.staleSending] uses it for crash recovery (D7). */
    val updatedAt: Long,
    val reported: Set<Status> = emptySet(),
    val acked: Set<Status> = emptySet(),
) {
    override fun toString(): String =
        "JournalEntry(id=$id, to=***, body=${body?.let { "<${it.length} chars>" }}, simSlot=$simSlot, " +
            "expiresAt=$expiresAt, state=$state, errorCode=$errorCode, errorMessage=$errorMessage, parts=$parts, " +
            "partsSent=$partsSent, partsDelivered=$partsDelivered, createdAt=$createdAt, updatedAt=$updatedAt, " +
            "reported=$reported, acked=$acked)"

    companion object {
        /** SMS concatenation is tracked in a 64-bit mask per kind. */
        const val MAX_PARTS = 64

        /** A new [JournalState.CLAIMED] row for a 200 claim response. */
        fun claimed(message: ClaimedMessage, now: Long): JournalEntry = JournalEntry(
            id = message.id,
            to = message.to,
            body = message.body,
            simSlot = message.simSlot,
            expiresAt = message.expiresAt.toEpochMilliseconds(),
            createdAt = now,
            updatedAt = now,
        )

        /** Bitmask with the low [parts] bits set. */
        fun allParts(parts: Int): Long {
            require(parts in 1..MAX_PARTS) { "parts must be 1..$MAX_PARTS" }
            return if (parts == MAX_PARTS) -1L else (1L shl parts) - 1
        }
    }
}

/** A status the journal decided to report: hand it to the report queue exactly once. */
data class PendingReport(
    val id: String,
    val status: Status,
    /** Only for [Status.FAILED]. */
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/** Outcome of [JournalDao.moveTo]: whether the row moved and what to report. */
data class MoveResult(val moved: Boolean, val report: PendingReport?) {
    companion object {
        val REFUSED = MoveResult(false, null)
    }
}
