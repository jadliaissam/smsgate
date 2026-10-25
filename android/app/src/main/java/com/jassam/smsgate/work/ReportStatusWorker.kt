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
 * - 2xx (also the server's answer to a repeated, already-applied report);
 * - 409 `invalid_transition` (the message is already in that or a later state);
 * - 4xx with one of the backend's codes [FINAL_CODES] or `invalid_*`
 *   (`message_not_found`, `invalid_status`, `invalid_error_code`, `invalid_json`,
 *   `body_too_large`, ...): the server will never accept it; logged and dropped.
 *
 * Every other answer retries (no response, 3xx, 5xx, `route_not_found`, a proxy's
 * `http_<status>`, a bare 401). A backend 401 ([isRevocation]) goes to [Revocation].
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
        return when (val result = deps.api(credentials).reportStatus(id, status, code, message)) {
            is ApiResult.Ok -> ack(id, status)
            is ApiResult.NetworkError -> retry(id, status, "network")
            is ApiResult.HttpError -> when {
                result.isRevocation -> {
                    deps.revocation.onUnauthorized(credentials.deviceToken)
                    Result.failure()
                }
                result.status in 200..299 -> ack(id, status) // accepted; body unreadable
                isFinal(result) -> {
                    // The backend's own verdict: it will never accept this report.
                    if (!(result.status == 409 && result.code == INVALID_TRANSITION)) {
                        deps.log("report ${status.wire} for $id dropped: HTTP ${result.status} ${result.code}")
                    }
                    ack(id, status)
                }
                else -> retry(id, status, result.code)
            }
        }
    }

    private fun isFinal(e: ApiResult.HttpError): Boolean =
        e.status in 400..499 && (e.code in FINAL_CODES || (e.code.startsWith("invalid_") && e.hasBackendCode))

    private fun retry(id: String, status: Status, why: String): Result {
        deps.log("report ${status.wire} for $id: $why; retrying")
        return Result.retry()
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

        /**
         * The backend's codes that make a report final (plus every `invalid_*`:
         * invalid_status, invalid_error_code, invalid_error_message, invalid_json).
         * Everything else, even enveloped (route_not_found, ...), is retried.
         */
        private val FINAL_CODES = setOf("message_not_found", INVALID_TRANSITION, "body_too_large", "unsupported_media_type")

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
