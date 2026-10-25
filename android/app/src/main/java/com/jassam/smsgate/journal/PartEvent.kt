package com.jassam.smsgate.journal

/** One SMS part result from a sent or delivery broadcast. */
sealed interface PartEvent {
    val part: Int

    /** The radio accepted the part. */
    data class Sent(override val part: Int) : PartEvent

    /** The radio refused the part. */
    data class SendFailed(override val part: Int, val errorCode: String, val errorMessage: String) : PartEvent

    /** A status report says the part reached the recipient (implies it was sent). */
    data class Delivered(override val part: Int) : PartEvent

    /** A status report says the network is still trying (implies the part was sent). */
    data class DeliveryPending(override val part: Int) : PartEvent

    /** A status report says the part will not be delivered. */
    data class DeliveryFailed(override val part: Int, val errorCode: String, val errorMessage: String) : PartEvent
}

/** The row after applying a [PartEvent]. */
internal data class PartChange(
    val state: JournalState,
    val partsSent: Long,
    val partsDelivered: Long,
    val errorCode: String?,
    val errorMessage: String?,
)

/**
 * Pure aggregation of part results into the row. Returns null when the event
 * changes nothing: the row is not SENDING/SENT, the part index is out of range,
 * or the event repeats or contradicts an outcome already recorded for that part
 * (duplicated/reordered broadcasts). Any failed part fails the whole message once.
 */
internal object PartAggregator {
    fun apply(entry: JournalEntry, event: PartEvent): PartChange? {
        if (entry.state != JournalState.SENDING && entry.state != JournalState.SENT) return null
        if (entry.parts !in 1..JournalEntry.MAX_PARTS || event.part !in 0 until entry.parts) return null
        val bit = 1L shl event.part
        val sent = entry.partsSent
        val delivered = entry.partsDelivered
        return when (event) {
            is PartEvent.Sent ->
                if (sent and bit != 0L) null else progress(entry, sent or bit, delivered)
            is PartEvent.DeliveryPending ->
                if (sent and bit != 0L) null else progress(entry, sent or bit, delivered)
            is PartEvent.Delivered ->
                if (delivered and bit != 0L) null else progress(entry, sent or bit, delivered or bit)
            is PartEvent.SendFailed ->
                if (sent and bit != 0L) null
                else PartChange(JournalState.FAILED, sent, delivered, event.errorCode, event.errorMessage)
            is PartEvent.DeliveryFailed ->
                if (delivered and bit != 0L) null
                else PartChange(JournalState.FAILED, sent or bit, delivered, event.errorCode, event.errorMessage)
        }
    }

    private fun progress(entry: JournalEntry, sent: Long, delivered: Long): PartChange {
        val all = JournalEntry.allParts(entry.parts)
        val state = when {
            delivered == all -> JournalState.DELIVERED
            sent == all -> JournalState.SENT
            else -> entry.state
        }
        return PartChange(state, sent, delivered, entry.errorCode, entry.errorMessage)
    }
}
