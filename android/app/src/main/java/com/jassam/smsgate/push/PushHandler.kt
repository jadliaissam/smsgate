package com.jassam.smsgate.push

import com.jassam.smsgate.work.MessageIds
import com.jassam.smsgate.work.WorkQueue
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What an FCM push or token refresh does (logic of [SmsGateMessagingService]).
 * Pushes only wake the app: the payload is `{type:"new", message_id}` and the body
 * comes from the claim over HTTPS. Never logs payloads, numbers or tokens.
 */
class PushHandler(
    private val queue: WorkQueue,
    private val isPaired: suspend () -> Boolean,
) {
    /**
     * `type == "new"` with a UUID `message_id` → expedited claim-and-send, unique per
     * id (KEEP, so duplicate pushes collapse), and an immediate sync (catch-up for
     * anything else queued). Anything else is ignored.
     */
    fun onMessage(data: Map<String, String>) {
        if (data[KEY_TYPE] != TYPE_NEW) return
        val id = MessageIds.normalize(data[KEY_MESSAGE_ID]) ?: return
        queue.processMessage(id, expedited = true)
        queue.syncNow()
    }

    /**
     * A refreshed FCM token: sent to the server when paired, dropped otherwise (pairing
     * registers with the current token and re-sends it afterwards, see
     * [com.jassam.smsgate.work.FcmTokenWorker]). If the pairing state can't be read in
     * time the job is enqueued anyway; it does nothing when unpaired.
     */
    suspend fun onNewToken(token: String) {
        if (token.isBlank() || token.length > MAX_TOKEN) return
        val paired = withTimeoutOrNull(PAIRED_CHECK_TIMEOUT_MS) { isPaired() } ?: true
        if (paired) queue.setFcmToken(token)
    }

    private companion object {
        const val KEY_TYPE = "type"
        const val KEY_MESSAGE_ID = "message_id"
        const val TYPE_NEW = "new"
        const val MAX_TOKEN = 4096
        const val PAIRED_CHECK_TIMEOUT_MS = 5_000L
    }
}
