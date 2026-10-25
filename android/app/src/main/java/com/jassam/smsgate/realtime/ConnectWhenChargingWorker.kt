package com.jassam.smsgate.realtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jassam.smsgate.work.WorkDeps

/**
 * Runs once the phone is plugged in (WorkManager's charging constraint, armed by
 * [Connectivity.evaluate] while paired with "Always connected" off) and applies the
 * policy again, which starts the socket service. Implicit power broadcasts no longer
 * reach manifest receivers on API 26+, so this is how plugging in reconnects while the
 * service is not running. Needs no network.
 */
class ConnectWhenChargingWorker(
    context: Context,
    params: WorkerParameters,
    private val deps: WorkDeps,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        deps.evaluateConnection()
        return Result.success()
    }
}
