package com.jassam.smsgate.realtime

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import com.jassam.smsgate.work.WorkTestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectWhenChargingWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var env: WorkTestEnv

    @Before
    fun setUp() {
        env = WorkTestEnv(tmp.root)
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun pluggingInAppliesThePolicyWithoutAnyRequest() = runBlocking {
        assertEquals(Result.success(), env.worker<ConnectWhenChargingWorker>(Data.EMPTY).doWork())
        assertEquals(1, env.evaluations)
        assertEquals(0, env.server.requestCount)
    }
}
