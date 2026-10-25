package com.jassam.smsgate.push

import android.app.Application
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.firebase.messaging.RemoteMessage
import com.jassam.smsgate.work.Scheduler
import com.jassam.smsgate.work.testWorkConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsGateMessagingServiceTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var wm: WorkManager
    private var paired = true
    private var pairedError: RuntimeException? = null
    private val id = "0192f0c4-0000-7000-8000-0000000000a1"

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, testWorkConfiguration())
        wm = WorkManager.getInstance(app)
        SmsGateMessagingService.handlerOverride = PushHandler(Scheduler { wm }, isPaired = { pairedError?.let { throw it }; paired })
    }

    @After
    fun tearDown() {
        SmsGateMessagingService.handlerOverride = null
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun push(data: Map<String, String>) =
        SmsGateMessagingService().onMessageReceived(RemoteMessage.Builder("sender@fcm.googleapis.com").setData(data).build())

    private fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get()

    private fun allWork(): List<WorkInfo> = wm.getWorkInfosByTag(Scheduler.TAG).get()

    @Test
    fun newMessagePushEnqueuesOneUniqueProcessJobAndASync() {
        push(mapOf("type" to "new", "message_id" to id))
        push(mapOf("type" to "new", "message_id" to id))
        assertEquals(1, infos(Scheduler.processName(id)).size)
        assertEquals(WorkInfo.State.ENQUEUED, infos(Scheduler.processName(id))[0].state)
        assertEquals(1, infos(Scheduler.SYNC_NOW).size)
    }

    @Test
    fun uppercaseIdIsNormalised() {
        push(mapOf("type" to "new", "message_id" to id.uppercase()))
        assertEquals(1, infos(Scheduler.processName(id)).size)
    }

    @Test
    fun malformedPushesAreIgnored() {
        push(emptyMap())
        push(mapOf("type" to "new"))
        push(mapOf("type" to "new", "message_id" to ""))
        push(mapOf("type" to "new", "message_id" to "not-a-uuid"))
        push(mapOf("type" to "new", "message_id" to "$id/../x"))
        push(mapOf("type" to "revoked", "message_id" to id))
        push(mapOf("message_id" to id))
        assertTrue(allWork().isEmpty())
    }

    @Test
    fun newTokenWhilePairedIsSent() {
        SmsGateMessagingService().onNewToken("fcm-new")
        val info = infos(Scheduler.FCM_TOKEN).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
    }

    @Test
    fun newTokenWhileUnpairedDoesNothing() {
        paired = false
        SmsGateMessagingService().onNewToken("fcm-new")
        SmsGateMessagingService().onNewToken("")
        assertTrue(allWork().isEmpty())
    }

    @Test
    fun anUnreadablePairingStateNeverCrashesFirebasesThread() {
        pairedError = java.security.ProviderException("keystore")
        SmsGateMessagingService().onNewToken("fcm-new")
        SmsGateMessagingService().onDeletedMessages()
        // Enqueued anyway: the workers re-check and do nothing when unpaired.
        assertEquals(1, infos(Scheduler.FCM_TOKEN).size)
        assertEquals(1, infos(Scheduler.SYNC_NOW).size)
    }

    @Test
    fun deletedMessagesTriggerACatchUpWhenPaired() {
        SmsGateMessagingService().onDeletedMessages()
        assertEquals(1, infos(Scheduler.SYNC_NOW).size)
    }

    @Test
    fun deletedMessagesWhileUnpairedDoNothing() {
        paired = false
        SmsGateMessagingService().onDeletedMessages()
        assertTrue(allWork().isEmpty())
    }
}
