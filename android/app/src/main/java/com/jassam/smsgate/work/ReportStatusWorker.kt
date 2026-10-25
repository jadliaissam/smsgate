package com.jassam.smsgate.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.jassam.smsgate.api.ApiResult
import com.jassam.smsgate.api.Status
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Reports one decided status (`sent` / `delivered` / `failed{code,message}`),
 * retried until the server settles it (Review Focus 3). Unique per (id, status)
 * with KEEP ([Scheduler.reportName]); needs a network; exponential backoff, no cap
 * on attempts (the 15-minute sync also re-enqueues unacked reports).
 *
 * Final, so the journal marks it acked and it is never sent again:
 * - 200 (also the server's answer to a repeated, already-applied report);
 * - any 409 `invalid_transition` (the message is already in that or a later state);
 * - 404 / 422 `invalid_*` / other 4xx: the server will never accept it; logged and dropped.
 *
 * No response, 408, 429 and 5xx retry. 401 goes to [Revocation].
 */
class ReportStatusWorker(
    context: Context,
    params: WorkerParameters,
    private val deps: WorkDeps,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = MessageIds.normalize(inputData.getString(KEY_ID)) ?: return Result.failure()
        val status = Status.entries.firstOrNull { it.wire == inputData.getString(KEY_STATUS) } ?: return Result.failure()
        val failed = status == Status.FAILED
        val code = if (failed) inputData.getString(KEY_CODE) else null
        val message = if (failed) inputData.getString(KEY_MESSAGE) else null

        val credentials = deps.credentials.load() ?: run {
            deps.revocation.onUnauthorized(null)
            return Result.failure()
        }
        val result = deps.api(credentials).reportStatus(id, status, code, message)
        return when {
            result is ApiResult.Ok -> ack(id, status)
            result.isTransient -> {
                deps.log("report ${status.wire} for $id: ${(result as? ApiResult.HttpError)?.code ?: "network"}; retrying")
                Result.retry()
            }
            result is ApiResult.HttpError && result.isUnauthorized -> {
                deps.revocation.onUnauthorized(credentials.deviceToken)
                Result.failure()
            }
            result is ApiResult.HttpError -> {
                if (result.status !in 200..299 && !(result.status == 409 && result.code == INVALID_TRANSITION)) {
                    deps.log("report ${status.wire} for $id dropped: HTTP ${result.status} ${result.code}")
                }
                ack(id, status)
            }
            else -> Result.retry()
        }
    }

    private suspend fun ack(id: String, status: Status): Result = try {
        withContext(NonCancellable) { deps.journal.markAcked(id, status) }
        Result.success()
    } catch (e: Exception) {
        // Not recorded: retry; a repeated report is a 200 on the server.
        deps.log("ack ${status.wire} for $id failed: ${e.javaClass.simpleName}")
        Result.retry()
    }

    companion object {
        const val KEY_ID = "message_id"
        const val KEY_STATUS = "status"
        const val KEY_CODE = "error_code"
        const val KEY_MESSAGE = "error_message"
        private const val INVALID_TRANSITION = "invalid_transition"

        fun input(id: String, status: Status, errorCode: String?, errorMessage: String?): Data =
            Data.Builder()
                .putString(KEY_ID, id)
                .putString(KEY_STATUS, status.wire)
                .apply {
                    if (status == Status.FAILED) {
                        errorCode?.let { putString(KEY_CODE, it) }
                        errorMessage?.let { putString(KEY_MESSAGE, it) }
                    }
                }
                .build()
    }
}
