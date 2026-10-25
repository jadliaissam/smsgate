package com.jassam.smsgate.sms

import android.app.PendingIntent
import com.jassam.smsgate.api.Sim
import com.jassam.smsgate.journal.JournalDao
import com.jassam.smsgate.journal.JournalEntry
import com.jassam.smsgate.journal.JournalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What [SmsSender.send] did. */
sealed interface SendResult {
    /** Nothing handed to Android and nothing reported: another caller owns the row, or the journal failed. */
    data object NotSent : SendResult

    /** The row is FAILED and a `failed` report was enqueued. */
    data class Failed(val errorCode: String) : SendResult

    /** Handed to SmsManager as [parts] parts; results arrive in [SmsResultReceiver]. */
    data class HandedOff(val parts: Int) : SendResult
}

/**
 * Hands a journaled message to SmsManager at most once.
 *
 * The only way in is [JournalDao.markSending] (CLAIMED → SENDING) returning 1, so
 * of any number of concurrent callers exactly one proceeds. From there on the
 * work is non-cancellable and every synchronous failure moves the row to FAILED
 * and enqueues `failed`: a row is never left in SENDING after a synchronous
 * failure, and [send] never throws. Phone numbers and bodies are never logged.
 */
class SmsSender(
    private val dao: JournalDao,
    private val transport: SmsTransport,
    /** Active SIMs (SimInventory.activeSims); a binder call, so [send] runs off the main thread. */
    private val sims: () -> List<Sim>,
    private val intents: (PartRef) -> PendingIntent,
    private val reports: StatusReportQueue,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
) {
    suspend fun send(entry: JournalEntry): SendResult {
        val owned = try {
            dao.markSending(entry.id, now()) == 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("journal error before sending ${entry.id}: ${e.javaClass.simpleName}")
            return SendResult.NotSent
        }
        if (!owned) return SendResult.NotSent
        return withContext(NonCancellable) { handOff(entry) }
    }

    private suspend fun handOff(entry: JournalEntry): SendResult {
        val id = entry.id
        val body = entry.body ?: return fail(id, ErrorCodes.BODY_MISSING, "Message body is no longer available")
        val subscriptionId: Int? = entry.simSlot?.let { slot ->
            sims().firstOrNull { it.slot == slot }?.subscriptionId
                ?: return fail(id, ErrorCodes.SIM_UNAVAILABLE, "No active SIM in slot $slot")
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
                return SendResult.NotSent
            }
            val sent = parts.indices.map { intents(PartRef(id, it, PartKind.SENT)) }
            val delivered = parts.indices.map { intents(PartRef(id, it, PartKind.DELIVERED)) }
            channel.sendMultipartTextMessage(entry.to, parts, sent, delivered)
            SendResult.HandedOff(parts.size)
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

    private suspend fun fail(id: String, code: String, message: String): SendResult {
        log("send $id failed: $code")
        try {
            val result = dao.moveTo(id, JournalState.FAILED, now(), code, ErrorCodes.message(message))
            result.report?.let(reports::enqueue)
        } catch (e: Exception) {
            // The row stays SENDING; crash recovery (D7) reports it as outcome_unknown.
            log("journal error failing $id: ${e.javaClass.simpleName}")
        }
        return SendResult.Failed(code)
    }
}
