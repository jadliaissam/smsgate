package com.jassam.smsgate.journal

import com.jassam.smsgate.api.Status

/**
 * Lifecycle of a claimed message on this phone. Every move is one conditional
 * UPDATE (or a transaction) in [JournalDao] restricted to the source states this
 * table allows; anything else changes nothing.
 *
 * ```
 * CLAIMED ──► SENDING ──► SENT ──► DELIVERED
 *    │           │  └──────────────► DELIVERED   (delivery report overtook the sent result)
 *    │           └──► FAILED ◄─────── SENT       (a delivery report said it failed)
 *    ├──► FAILED
 *    └──► SKIPPED_EXPIRED
 * ```
 *
 * The moves after SENDING mirror what the backend accepts
 * (backend/internal/messages/device.go `allowedFrom`): `delivered` may follow
 * `dispatched` or `sent`, and `failed` may follow `dispatched` or `sent`.
 * DELIVERED, FAILED and SKIPPED_EXPIRED are terminal: nothing moves them.
 */
enum class JournalState {
    /** Claimed (HTTP 200) and journaled with its body; not handed to Android yet. */
    CLAIMED,

    /** Handed (or being handed) to SmsManager. Never sent again from here (D7). */
    SENDING,

    /** Every part was accepted by the radio. */
    SENT,

    /** Every part was delivered to the recipient. */
    DELIVERED,

    /** Failed before, while or after sending (see errorCode). */
    FAILED,

    /** Not sent because the validity window had passed (reported as failed/expired_on_device). */
    SKIPPED_EXPIRED,
    ;

    val isTerminal: Boolean get() = this == DELIVERED || this == FAILED || this == SKIPPED_EXPIRED

    companion object {
        private val ALLOWED: Map<JournalState, Set<JournalState>> = mapOf(
            CLAIMED to setOf(SENDING, FAILED, SKIPPED_EXPIRED),
            SENDING to setOf(SENT, DELIVERED, FAILED),
            SENT to setOf(DELIVERED, FAILED),
        )

        /** True when the journal may move a row from [from] to [to]. */
        fun canMove(from: JournalState, to: JournalState): Boolean = ALLOWED[from]?.contains(to) == true

        /** The status reported to the server when a row enters [state], if any. */
        fun reportFor(state: JournalState): Status? = when (state) {
            SENT -> Status.SENT
            DELIVERED -> Status.DELIVERED
            FAILED, SKIPPED_EXPIRED -> Status.FAILED
            CLAIMED, SENDING -> null
        }
    }
}
