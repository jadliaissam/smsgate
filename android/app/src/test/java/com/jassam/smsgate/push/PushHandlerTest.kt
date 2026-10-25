package com.jassam.smsgate.push

import com.jassam.smsgate.work.RecordingWorkQueue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PushHandlerTest {
    private val queue = RecordingWorkQueue()

    @Test(timeout = 10_000)
    fun aPairingStateThatNeverAnswersStillEnqueuesAfterTheTimeout() = runBlocking {
        val handler = PushHandler(queue, isPaired = { awaitCancellation() }, pairedCheckTimeoutMs = 50)
        handler.onNewToken("fcm-new")
        handler.onDeletedMessages()
        assertEquals(listOf("fcm-new"), queue.tokens)
        assertEquals(1, queue.syncs)
    }

    @Test
    fun enqueueErrorsAreSwallowed() = runBlocking {
        val failing = object : com.jassam.smsgate.work.WorkQueue by queue {
            override fun processMessage(id: String, expedited: Boolean) = throw IllegalStateException("wm down")
        }
        PushHandler(failing, isPaired = { true }).onMessage(mapOf("type" to "new", "message_id" to "0192f0c4-0000-7000-8000-0000000000a1"))
    }
}
