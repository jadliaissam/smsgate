package com.jassam.smsgate.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jassam.smsgate.api.ApiResult

/**
 * Sends the FCM token to the server (`PUT /device/v1/fcm-token`). Enqueued by
 * `onNewToken` with the new token, and after pairing with none: it then reads the
 * current token. That second run closes the gap where a token refreshed between
 * pairing's `getToken` and `register` (when `onNewToken` found the phone unpaired
 * and dropped it) would leave the server with a stale token.
 *
 * Unpaired → nothing (no revocation: pairing may be in progress). Unique with
 * REPLACE, so the newest token wins. The token is never logged.
 */
class FcmTokenWorker(
    context: Context,
    params: WorkerParameters,
    private val deps: WorkDeps,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val credentials = deps.credentials.load() ?: return Result.success()
        val token = inputData.getString(KEY_TOKEN) ?: deps.fcmToken() ?: return Result.success()
        if (token.isBlank()) return Result.success()
        val result = deps.api(credentials).setFcmToken(token)
        return when {
            result is ApiResult.Ok -> Result.success()
            result is ApiResult.HttpError && result.isUnauthorized -> {
                deps.revocation.onUnauthorized(credentials.deviceToken)
                Result.failure()
            }
            result.isTransient -> if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
            else -> {
                deps.log("fcm token rejected: ${(result as? ApiResult.HttpError)?.code}")
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_TOKEN = "fcm_token"
        const val MAX_ATTEMPTS = 8

        fun input(token: String?): Data = if (token == null) Data.EMPTY else workDataOf(KEY_TOKEN to token)
    }
}
