package com.jassam.smsgate.work

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.jassam.smsgate.api.Status
import com.jassam.smsgate.work.WorkTestEnv.Companion.ID
import com.jassam.smsgate.work.WorkTestEnv.Companion.ID2
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** WorkManager wiring: unique names, policies, constraints (work does not run here). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchedulerTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var wm: WorkManager
    private lateinit var scheduler: Scheduler

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, testWorkConfiguration())
        wm = WorkManager.getInstance(app)
        scheduler = Scheduler { wm }
    }

    @After
    fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    private fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get()

    private fun expedited(info: WorkInfo): Boolean =
        WorkManagerImpl.getInstance(app).workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!.expedited

    @Test
    fun processMessageIsUniquePerIdKeptExpeditedAndNeedsNetwork() {
        scheduler.processMessage(ID, expedited = true)
        scheduler.processMessage(ID, expedited = true)
        scheduler.processMessage(ID, expedited = false)
        scheduler.processMessage(ID2, expedited = false)

        val first = infos(Scheduler.processName(ID))
        assertEquals(1, first.size)
        val info = first[0]
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(expedited(info))
        assertTrue(Scheduler.TAG in info.tags)
        assertEquals(1, infos(Scheduler.processName(ID2)).size)
        assertFalse(expedited(infos(Scheduler.processName(ID2))[0]))
    }

    @Test
    fun reportsAreUniquePerIdAndStatus() {
        scheduler.enqueue(ID, Status.SENT, null, null)
        scheduler.enqueue(ID, Status.SENT, null, null)
        scheduler.enqueue(ID, Status.DELIVERED, null, null)
        scheduler.enqueue(ID, Status.FAILED, "radio_off", "m")

        assertEquals(1, infos(Scheduler.reportName(ID, Status.SENT)).size)
        assertEquals(1, infos(Scheduler.reportName(ID, Status.DELIVERED)).size)
        val failed = infos(Scheduler.reportName(ID, Status.FAILED)).single()
        assertEquals(NetworkType.CONNECTED, failed.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, failed.state)
    }

    @Test
    fun aNewerFcmTokenReplacesAnOlderPendingOne() {
        scheduler.setFcmToken("t1")
        scheduler.setFcmToken("t2")
        val all = infos(Scheduler.FCM_TOKEN)
        assertEquals(1, all.count { it.state == WorkInfo.State.ENQUEUED })
        assertTrue(all.filter { it.state != WorkInfo.State.ENQUEUED }.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun periodicSyncIsKeptAtFifteenMinutes() {
        scheduler.schedulePeriodicSync()
        val id = infos(Scheduler.SYNC_PERIODIC).single().id
        scheduler.schedulePeriodicSync()
        val info = infos(Scheduler.SYNC_PERIODIC).single()
        assertEquals(id, info.id)
        assertEquals(TimeUnit.MINUTES.toMillis(15), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test
    fun immediateSyncIsUnique() {
        scheduler.syncNow()
        scheduler.syncNow()
        assertEquals(1, infos(Scheduler.SYNC_NOW).size)
    }

    @Test
    fun cancelAllCancelsEveryJob() = runBlocking {
        scheduler.processMessage(ID, expedited = true)
        scheduler.enqueue(ID, Status.SENT, null, null)
        scheduler.schedulePeriodicSync()
        scheduler.syncNow()
        scheduler.setFcmToken("t")
        scheduler.cancelAll()
        for (name in listOf(Scheduler.processName(ID), Scheduler.reportName(ID, Status.SENT), Scheduler.SYNC_PERIODIC, Scheduler.SYNC_NOW, Scheduler.FCM_TOKEN)) {
            assertTrue(name, infos(name).all { it.state == WorkInfo.State.CANCELLED })
        }
    }
}

internal fun testWorkConfiguration(): Configuration = Configuration.Builder()
    .setMinimumLoggingLevel(Log.DEBUG)
    .setExecutor(SynchronousExecutor())
    .setWorkerFactory(SmsGateWorkerFactory { error("workers do not run in this test") })
    .build()
