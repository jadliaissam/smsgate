package com.jassam.smsgate.push

import com.jassam.smsgate.work.MessageIds
import com.jassam.smsgate.work.WorkQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What an FCM push or token refresh does (logic of [SmsGateMessagingService]).
 * Pushes only wake the app: the payload is `{type:"new", message_id}` and the body
 * comes from the claim over HTTPS. Never throws (it runs on Firebase's thread) and
 * never logs payloads, numbers or tokens.
 */
class PushHandler(
    private val queue: WorkQueue,
    private val isPaired: suspend () -> Boolean,
    private val log: (String) -> Unit = {},
    private val pairedCheckTimeoutMs: Long = PAIRED_CHECK_TIMEOUT_MS,
) {
    /**
     * `type == "new"` with a UUID `message_id` → expedited claim-and-send, unique per
     * id (KEEP, so duplicate pushes collapse), and an immediate sync (catch-up for
     * anything else queued; it also re-asserts the periodic sync). Anything else is ignored.
     */
    fun onMessage(data: Map<String, String>) {
        if (data[KEY_TYPE] != TYPE_NEW) return
        val id = MessageIds.normalize(data[KEY_MESSAGE_ID]) ?: return
        guarded("push") {
            queue.processMessage(id, expedited = true)
            queue.syncNow()
        }
    }

    /**
     * A refreshed FCM token: sent to the server when paired, dropped otherwise (the next
     * sync after pairing re-sends the current token if the server may not have it). If
     * the pairing state can't be read (timeout, Keystore error) the job is enqueued
     * anyway; it does nothing when unpaired.
     */
    suspend fun onNewToken(token: String) {
        if (token.isBlank() || token.length > MAX_TOKEN) return
        if (pairedOrUnknown()) guarded("token") { queue.setFcmToken(token) }
    }

    /** FCM dropped pending pushes for this phone (too many, or offline too long): catch up now. */
    suspend fun onDeletedMessages() {
        if (pairedOrUnknown()) guarded("deleted messages") { queue.syncNow() }
    }

    private suspend fun pairedOrUnknown(): Boolean = try {
        withTimeoutOrNull(pairedCheckTimeoutMs) { isPaired() } ?: true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("pairing state unreadable: ${e.javaClass.simpleName}")
        true
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("$what: could not enqueue work: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val KEY_TYPE = "type"
        const val KEY_MESSAGE_ID = "message_id"
        const val TYPE_NEW = "new"
        const val MAX_TOKEN = 4096
        const val PAIRED_CHECK_TIMEOUT_MS = 5_000L
    }
}
