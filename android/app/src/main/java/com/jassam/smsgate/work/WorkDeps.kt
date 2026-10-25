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

/** Retryable without risk of a duplicate effect: no response, timeouts, rate limits, server errors. */
internal val ApiResult<*>.isTransient: Boolean
    get() = when (this) {
        is ApiResult.NetworkError -> true
        is ApiResult.HttpError -> status == 408 || status == 429 || status >= 500
        is ApiResult.Ok -> false
    }
