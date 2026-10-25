package com.jassam.smsgate.sms

import android.app.PendingIntent
import com.jassam.smsgate.api.Sim
import com.jassam.smsgate.journal.JournalDao
import com.jassam.smsgate.journal.JournalEntry
import com.jassam.smsgate.journal.JournalState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What [SmsSender.send] did. */
sealed interface SendOutcome {
    /** Nothing handed to Android and nothing reported: another caller owns the row, or the journal failed. */
    data object NotSent : SendOutcome

    /** The validity window had passed: the row is SKIPPED_EXPIRED and `failed/expired_on_device` was enqueued. */
    data object Expired : SendOutcome

    /** The row is FAILED and a `failed` report was enqueued. */
    data class Failed(val errorCode: String) : SendOutcome

    /** Handed to SmsManager as [parts] parts; results arrive in [SmsResultReceiver]. */
    data class HandedOff(val parts: Int) : SendOutcome
}

/**
 * Hands a journaled message to SmsManager at most once, and never after it expired.
 *
 * Order: expiry check on the server clock (expired → CLAIMED → SKIPPED_EXPIRED),
 * then [JournalDao.markSending] (CLAIMED → SENDING) which only one caller can win,
 * then SIM resolution, split, part count, hand-off. Once started the whole
 * sequence is non-cancellable, so a cancelled caller can't leave a row in SENDING
 * without its part count. Every synchronous failure after markSending moves the
 * row SENDING → FAILED and enqueues `failed`; [send] never throws (except a
 * CancellationException before anything was done). Numbers and bodies are never
 * logged.
 */
class SmsSender(
    private val dao: JournalDao,
    private val transport: SmsTransport,
    /** Active SIMs (SimInventory.activeSims); empty without READ_PHONE_STATE. A binder call: run off main. */
    private val sims: () -> List<Sim>,
    private val phoneStateGranted: () -> Boolean,
    /** SubscriptionManager.getDefaultSmsSubscriptionId(); negative when no default SMS SIM is set. */
    private val defaultSmsSubscriptionId: () -> Int,
    private val intents: (PartRef) -> PendingIntent,
    private val reports: StatusReportQueue,
    /** Local wall clock (epoch ms) for journal timestamps. */
    private val now: () -> Long,
    /** Server clock (epoch ms) for the expiry check (ServerClock). */
    private val serverNow: () -> Long,
    private val log: (String) -> Unit = {},
) {
    suspend fun send(entry: JournalEntry): SendOutcome {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) { start(entry) }
    }

    private suspend fun start(entry: JournalEntry): SendOutcome {
        val id = entry.id
        try {
            if (entry.expiresAt <= serverNow()) return expire(id)
            if (dao.markSending(id, now()) != 1) return SendOutcome.NotSent
        } catch (e: Exception) {
            log("journal error before sending $id: ${e.javaClass.simpleName}")
            return SendOutcome.NotSent
        }
        return handOff(entry)
    }

    private suspend fun expire(id: String): SendOutcome {
        val result = dao.moveTo(
            id, JournalState.CLAIMED, JournalState.SKIPPED_EXPIRED, now(),
            ErrorCodes.EXPIRED_ON_DEVICE, "The validity window passed before the SMS could be sent",
        )
        if (!result.moved) return SendOutcome.NotSent
        log("send $id skipped: ${ErrorCodes.EXPIRED_ON_DEVICE}")
        result.report?.let(reports::enqueue)
        return SendOutcome.Expired
    }

    private sealed interface Subscription {
        data class Found(val id: Int) : Subscription
        data class Missing(val code: String, val message: String) : Subscription
    }

    private fun resolve(slot: Int?): Subscription = try {
        if (slot != null) {
            when {
                !phoneStateGranted() ->
                    Subscription.Missing(ErrorCodes.PERMISSION_DENIED, "Phone state permission is needed to find the SIM in slot $slot")
                else -> sims().firstOrNull { it.slot == slot }?.let { Subscription.Found(it.subscriptionId) }
                    ?: Subscription.Missing(ErrorCodes.SIM_UNAVAILABLE, "No active SIM in slot $slot")
            }
        } else {
            val default = defaultSmsSubscriptionId()
            when {
                default >= 0 && default != Int.MAX_VALUE -> Subscription.Found(default)
                !phoneStateGranted() ->
                    Subscription.Missing(ErrorCodes.PERMISSION_DENIED, "No default SMS SIM and no phone state permission to pick one")
                else -> {
                    val active = sims()
                    active.singleOrNull()?.let { Subscription.Found(it.subscriptionId) }
                        ?: Subscription.Missing(ErrorCodes.NO_DEFAULT_SIM, "No default SMS SIM is set and ${active.size} SIMs are active")
                }
            }
        }
    } catch (_: SecurityException) {
        Subscription.Missing(ErrorCodes.PERMISSION_DENIED, "Phone state permission denied while finding the SIM")
    } catch (e: Exception) {
        Subscription.Missing(ErrorCodes.SEND_EXCEPTION, "Platform error while finding the SIM: ${e.javaClass.simpleName}")
    }

    private suspend fun handOff(entry: JournalEntry): SendOutcome {
        val id = entry.id
        val body = entry.body
        if (body.isNullOrBlank()) return fail(id, ErrorCodes.BODY_MISSING, "Message body is empty or no longer available")
        val subscriptionId = when (val s = resolve(entry.simSlot)) {
            is Subscription.Found -> s.id
            is Subscription.Missing -> return fail(id, s.code, s.message)
        }
        return try {
            val channel = transport.channel(subscriptionId)
                ?: return fail(id, ErrorCodes.NO_SMS_SERVICE, "SMS service is not available on this phone")
            val parts = channel.divideMessage(body).ifEmpty { listOf(body) }
            if (parts.size > JournalEntry.MAX_PARTS) {
                return fail(id, ErrorCodes.TOO_MANY_PARTS, "Message needs ${parts.size} SMS parts (max ${JournalEntry.MAX_PARTS})")
            }
            if (dao.setParts(id, parts.size, now()) != 1) {
                log("journal row $id changed before hand-off; not sent")
                return SendOutcome.NotSent
            }
            val sent = parts.indices.map { intents(PartRef(id, it, PartKind.SENT)) }
            val delivered = parts.indices.map { intents(PartRef(id, it, PartKind.DELIVERED)) }
            channel.sendMultipartTextMessage(entry.to, parts, sent, delivered)
            SendOutcome.HandedOff(parts.size)
        } catch (_: SecurityException) {
            fail(id, ErrorCodes.PERMISSION_DENIED, "Sending SMS is not permitted (SEND_SMS denied)")
        } catch (_: IllegalArgumentException) {
            fail(id, ErrorCodes.INVALID_DESTINATION, "The platform rejected the destination or text")
        } catch (_: UnsupportedOperationException) {
            // Thrown by SmsManager when the phone has no SMS service (e.g. a tablet without telephony).
            fail(id, ErrorCodes.NO_SMS_SERVICE, "SMS service is not available on this phone")
        } catch (e: Exception) {
            fail(id, ErrorCodes.SEND_EXCEPTION, "Platform error while sending: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun fail(id: String, code: String, message: String): SendOutcome {
        log("send $id failed: $code")
        try {
            val result = dao.moveTo(id, JournalState.SENDING, JournalState.FAILED, now(), code, ErrorCodes.message(message))
            result.report?.let(reports::enqueue)
        } catch (e: Exception) {
            // The row stays SENDING; crash recovery (D7) reports it as outcome_unknown.
            log("journal error failing $id: ${e.javaClass.simpleName}")
        }
        return SendOutcome.Failed(code)
    }
}
