package com.jassam.smsgate.journal

import androidx.room.ColumnInfo

/**
 * One journal row for the log list ([JournalDao.observeRecent]): everything but the
 * body, so a long list never holds message text in memory.
 */
data class JournalSummary(
    val id: String,
    @ColumnInfo(name = "to_number") val to: String,
    val state: JournalState,
    val simSlot: Int?,
    val errorCode: String?,
    val parts: Int,
    val partsSent: Long,
    val partsDelivered: Long,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun toString(): String =
        "JournalSummary(id=$id, to=***, state=$state, simSlot=$simSlot, errorCode=$errorCode, parts=$parts, " +
            "partsSent=$partsSent, partsDelivered=$partsDelivered, createdAt=$createdAt, updatedAt=$updatedAt)"
}

/**
 * Messages claimed in a time window ([JournalDao.observeCounts]). [sent] counts every
 * message the radio accepted, delivered ones included; [failed] includes messages
 * skipped because they expired before sending.
 */
data class DayCounts(val sent: Int, val delivered: Int, val failed: Int) {
    companion object {
        val ZERO = DayCounts(0, 0, 0)
    }
}
