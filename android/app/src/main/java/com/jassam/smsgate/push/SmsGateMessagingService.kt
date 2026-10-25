package com.jassam.smsgate.push

import androidx.annotation.VisibleForTesting
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jassam.smsgate.appGraph
import kotlinx.coroutines.runBlocking

/**
 * FCM entry point (not exported; Firebase delivers through its own receiver). Both
 * callbacks run on Firebase's background thread, so the short, bounded blocking read
 * of the pairing state in [onNewToken] / [onDeletedMessages] is fine. All logic is in
 * [PushHandler], which never throws.
 */
class SmsGateMessagingService : FirebaseMessagingService() {
    private fun handler(): PushHandler = handlerOverride ?: applicationContext.appGraph.pushHandler

    override fun onMessageReceived(message: RemoteMessage) {
        handler().onMessage(message.data)
    }

    override fun onNewToken(token: String) {
        runBlocking { handler().onNewToken(token) }
    }

    override fun onDeletedMessages() {
        runBlocking { handler().onDeletedMessages() }
    }

    internal companion object {
        /** Tests replace the AppGraph handler. */
        @VisibleForTesting
        @Volatile
        var handlerOverride: PushHandler? = null
    }
}
