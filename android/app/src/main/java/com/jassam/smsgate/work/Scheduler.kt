package com.jassam.smsgate.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.jassam.smsgate.api.Status
import com.jassam.smsgate.sms.StatusReportQueue
import java.util.concurrent.TimeUnit

/** The background jobs the app enqueues (seam over WorkManager for tests). */
interface WorkQueue : StatusReportQueue {
    /** Claim-and-send for one message id. Unique per id (KEEP): duplicate pushes/frames collapse. */
    fun processMessage(id: String, expedited: Boolean)

    /** One immediate sync (heartbeat, recovery, catch-up). Unique (KEEP). */
    fun syncNow()

    /** The 15-minute periodic sync. Unique (KEEP), survives reboots. */
    fun schedulePeriodicSync()

    /** Sends [token] to the server, or the current one when null. Unique (REPLACE: the newest token wins). */
    fun setFcmToken(token: String?)

    /** Cancels every job (revocation). */
    suspend fun cancelAll()
}

/** After pairing: periodic + immediate sync, and the current FCM token (see [FcmTokenWorker]). */
fun WorkQueue.onPaired() {
    schedulePeriodicSync()
    syncNow()
    setFcmToken(null)
}

/**
 * [WorkQueue] on WorkManager. Every job needs a network connection and backs off
 * exponentially; enqueueing never blocks (WorkManager writes its database on its
 * own executor). [workManager] is resolved lazily so nothing touches WorkManager
 * before the first job.
 */
class Scheduler(private val workManager: () -> WorkManager) : WorkQueue {
    private val wm: WorkManager by lazy(workManager)

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun processMessage(id: String, expedited: Boolean) {
        val request = OneTimeWorkRequestBuilder<ProcessMessageWorker>()
            .setInputData(ProcessMessageWorker.input(id))
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        wm.enqueueUniqueWork(processName(id), ExistingWorkPolicy.KEEP, request)
    }

    override fun enqueue(id: String, status: Status, errorCode: String?, errorMessage: String?) {
        val request = OneTimeWorkRequestBuilder<ReportStatusWorker>()
            .setInputData(ReportStatusWorker.input(id, status, errorCode, errorMessage))
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(reportName(id, status), ExistingWorkPolicy.KEEP, request)
    }

    override fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(SYNC_NOW, ExistingWorkPolicy.KEEP, request)
    }

    override fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        wm.enqueueUniquePeriodicWork(SYNC_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun setFcmToken(token: String?) {
        val request = OneTimeWorkRequestBuilder<FcmTokenWorker>()
            .setInputData(FcmTokenWorker.input(token))
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(FCM_TOKEN, ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun cancelAll() {
        wm.cancelAllWork().await()
    }

    companion object {
        /** Tag on every job of this app. */
        const val TAG = "smsgate"
        const val SYNC_NOW = "sync-now"
        const val SYNC_PERIODIC = "sync-periodic"
        const val FCM_TOKEN = "fcm-token"
        const val SYNC_INTERVAL_MINUTES = 15L
        const val BACKOFF_SECONDS = 10L

        fun processName(id: String) = "process:$id"
        fun reportName(id: String, status: Status) = "report:$id:${status.wire}"
    }
}
