package com.jassam.smsgate.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.jassam.smsgate.api.ApiResult
import com.jassam.smsgate.api.DeviceApi
import com.jassam.smsgate.auth.CredentialStore
import com.jassam.smsgate.auth.Credentials
import com.jassam.smsgate.journal.JournalDao
import com.jassam.smsgate.sms.SmsSender

/**
 * What the workers need, built by AppGraph (no DI framework) and handed to each
 * worker by [SmsGateWorkerFactory]; tests pass fakes the same way.
 */
class WorkDeps(
    val credentials: CredentialStore,
    /** A client bound to exactly these credentials (fixed token, so a 401 names the token it was for). */
    val api: (Credentials) -> DeviceApi,
    val journal: JournalDao,
    val sender: SmsSender,
    val queue: WorkQueue,
    val revocation: Revocation,
    val heartbeat: HeartbeatSource,
    /** The current FCM registration token, or null without Play services. Never logged. */
    val fcmToken: suspend () -> String?,
    /** The FCM token the server last accepted (per device). */
    val sentTokens: SentFcmTokens,
    /** Whether the server clock has been synced in this process (ServerClock.isSynced). */
    val clockSynced: () -> Boolean,
    /** Local wall clock (epoch ms) for journal timestamps. */
    val now: () -> Long = System::currentTimeMillis,
    /** Diagnostics; never pass numbers, bodies or tokens. */
    val log: (String) -> Unit = {},
)

/**
 * Builds the app's workers with their [WorkDeps] (set on WorkManager's
 * Configuration in App). [deps] is called lazily, on the first worker.
 */
class SmsGateWorkerFactory(private val deps: () -> WorkDeps) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            ProcessMessageWorker::class.java.name -> ProcessMessageWorker(appContext, workerParameters, deps())
            ReportStatusWorker::class.java.name -> ReportStatusWorker(appContext, workerParameters, deps())
            SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters, deps())
            FcmTokenWorker::class.java.name -> FcmTokenWorker(appContext, workerParameters, deps())
            else -> null
        }
}

/**
 * Retryable without risk of a duplicate effect: no response, timeouts, rate limits,
 * server errors, and any 4xx without the backend's own error code (a proxy or
 * misrouted request answering `http_<status>`: the backend never saw it).
 */
internal val ApiResult<*>.isTransient: Boolean
    get() = when (this) {
        is ApiResult.NetworkError -> true
        is ApiResult.HttpError -> status == 408 || status == 429 || status >= 500 || (status in 400..499 && !hasBackendCode)
        is ApiResult.Ok -> false
    }

/** The response carried the backend's JSON error envelope (not a synthetic `http_<status>` code). */
internal val ApiResult.HttpError.hasBackendCode: Boolean
    get() = !code.startsWith("http_") && code != ApiResult.CODE_INVALID_RESPONSE
