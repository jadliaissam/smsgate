package com.jassam.smsgate.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jassam.smsgate.api.ApiResult
import com.jassam.smsgate.api.ClaimedMessage
import com.jassam.smsgate.journal.JournalEntry
import com.jassam.smsgate.sms.SendOutcome
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Claim-and-send for one message id (from a push, the catch-up or, in A7, the
 * WebSocket). At most once (Review Focus 1):
 *
 * 1. A journal row for the id already exists → stop (a duplicate push or job).
 * 2. `claim(id)`: only a 200 allows sending. 409 `already_claimed`, 410 `expired`,
 *    404 → done, nothing sent. No response / 408 / 429 / 5xx → retry with
 *    exponential backoff, at most [MAX_CLAIM_ATTEMPTS] runs: a repeated claim is safe
 *    (409 if the server took it the first time, and then nothing is sent).
 * 3. After a 200, never retried: `insertClaimed` (false → another job owns it, don't
 *    send), then [com.jassam.smsgate.sms.SmsSender.send], which checks expiry on the
 *    server clock and reports `failed/expired_on_device` itself. This part is not
 *    cancellable, so a stopped job can't drop a claimed message between the two.
 *
 * A 401 goes to [Revocation]. Bodies and numbers are never logged.
 */
class ProcessMessageWorker(
    context: Context,
    params: WorkerParameters,
    private val deps: WorkDeps,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = MessageIds.normalize(inputData.getString(KEY_ID)) ?: return Result.failure()
        if (deps.journal.get(id) != null) return Result.success()
        val credentials = deps.credentials.load() ?: run {
            deps.revocation.onUnauthorized(null)
            return Result.failure()
        }
        return when (val claim = deps.api(credentials).claim(id)) {
            is ApiResult.Ok -> withContext(NonCancellable) { journalAndSend(claim.value) }
            is ApiResult.HttpError -> when {
                claim.isUnauthorized -> {
                    deps.revocation.onUnauthorized(credentials.deviceToken)
                    Result.failure()
                }
                claim.status in 200..299 -> {
                    // A 2xx we could not read: the server may have handed us the message. Never retry.
                    deps.log("claim $id: unreadable response; not sent")
                    Result.failure()
                }
                claim.status == 404 || claim.status == 409 || claim.status == 410 -> {
                    deps.log("claim $id: ${claim.code}")
                    Result.success()
                }
                claim.isTransient -> retryClaim(id, claim.code)
                else -> {
                    deps.log("claim $id: HTTP ${claim.status} ${claim.code}")
                    Result.failure()
                }
            }
            is ApiResult.NetworkError -> retryClaim(id, "network")
        }
    }

    private fun retryClaim(id: String, why: String): Result {
        val last = runAttemptCount + 1 >= MAX_CLAIM_ATTEMPTS
        deps.log("claim $id: $why (attempt ${runAttemptCount + 1}${if (last) ", giving up" else ""})")
        return if (last) Result.failure() else Result.retry()
    }

    private suspend fun journalAndSend(message: ClaimedMessage): Result {
        val entry = JournalEntry.claimed(message, deps.now())
        val inserted = try {
            deps.journal.insertClaimed(entry)
        } catch (e: Exception) {
            // Claimed but not journaled: sending now could not be guarded. The server's
            // sweeper settles the message.
            deps.log("journal error for ${message.id}: ${e.javaClass.simpleName}; not sent")
            return Result.failure()
        }
        if (!inserted) return Result.success()
        when (val outcome = deps.sender.send(entry)) {
            is SendOutcome.HandedOff -> deps.log("message ${message.id} handed off (${outcome.parts} parts)")
            is SendOutcome.Failed -> deps.log("message ${message.id} failed: ${outcome.errorCode}")
            SendOutcome.Expired -> deps.log("message ${message.id} expired before sending")
            SendOutcome.NotSent -> deps.log("message ${message.id} not sent")
        }
        return Result.success()
    }

    /** Shown by WorkManager when it runs this expedited job as a foreground service (API < 31). */
    override suspend fun getForegroundInfo(): ForegroundInfo = SendingNotification.foregroundInfo(applicationContext)

    companion object {
        const val KEY_ID = "message_id"
        const val MAX_CLAIM_ATTEMPTS = 5

        fun input(id: String): Data = workDataOf(KEY_ID to id)
    }
}
