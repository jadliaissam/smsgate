package com.jassam.smsgate.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jassam.smsgate.api.ApiResult
import com.jassam.smsgate.api.Heartbeat
import com.jassam.smsgate.journal.JournalRetention
import com.jassam.smsgate.sms.enqueue
import kotlin.coroutines.cancellation.CancellationException

/**
 * Heartbeat and catch-up: every 15 minutes ([Scheduler.SYNC_PERIODIC]), and once
 * right away after pairing, on app start and after each push ([Scheduler.SYNC_NOW]).
 *
 * In order: heartbeat (also syncs the server clock); crash recovery
 * ([CrashRecovery]); re-enqueue reports the server has not confirmed (unique per
 * (id, status), KEEP); `pending()` → a [ProcessMessageWorker] per id not yet in the
 * journal (unique per id, KEEP); journal retention. Local steps run even when the
 * network fails; a network failure is retried a few times. A 401 goes to
 * [Revocation] and stops the sync.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val deps: WorkDeps,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val credentials = deps.credentials.load() ?: run {
            deps.revocation.onUnauthorized(null)
            return Result.failure()
        }
        val api = deps.api(credentials)
        var offline = false

        val heartbeat = step("heartbeat source") { deps.heartbeat.read() } ?: Heartbeat()
        when (val hb = api.heartbeat(heartbeat)) {
            is ApiResult.Ok -> Unit
            is ApiResult.NetworkError -> offline = true
            is ApiResult.HttpError -> {
                if (hb.isUnauthorized) return revoked(credentials.deviceToken)
                deps.log("heartbeat: HTTP ${hb.status} ${hb.code}")
            }
        }

        val now = deps.now()
        step("crash recovery") { CrashRecovery.run(deps.journal, deps.sender, deps.queue, now, deps.log) }
        step("unacked reports") { deps.journal.unackedReports().forEach(deps.queue::enqueue) }

        when (val pending = api.pending()) {
            is ApiResult.Ok -> step("catch-up") {
                for (message in pending.value.messages) {
                    val id = MessageIds.normalize(message.id) ?: continue
                    if (deps.journal.get(id) == null) deps.queue.processMessage(id, expedited = false)
                }
            }
            is ApiResult.NetworkError -> offline = true
            is ApiResult.HttpError -> {
                if (pending.isUnauthorized) return revoked(credentials.deviceToken)
                deps.log("pending: HTTP ${pending.status} ${pending.code}")
            }
        }

        step("retention") { JournalRetention.run(deps.journal, deps.now()) }

        return if (offline && runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private suspend fun revoked(token: String): Result {
        deps.revocation.onUnauthorized(token)
        return Result.failure()
    }

    /** Runs one step; a failure is logged and the sync goes on with the next. */
    private suspend fun <T> step(name: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        deps.log("sync step $name failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        /** Runs per sync job when offline before giving up until the next one. */
        const val MAX_ATTEMPTS = 3
    }
}
