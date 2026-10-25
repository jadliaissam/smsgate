package com.jassam.smsgate.push

import androidx.annotation.VisibleForTesting
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jassam.smsgate.appGraph
import kotlinx.coroutines.runBlocking

/**
 * FCM entry point (not exported; Firebase delivers through its own receiver). Both
 * callbacks run on Firebase's background thread, so the short blocking read of the
 * pairing state in [onNewToken] is fine. All logic is in [PushHandler].
 */
class SmsGateMessagingService : FirebaseMessagingService() {
    private fun handler(): PushHandler = handlerOverride ?: applicationContext.appGraph.pushHandler

    override fun onMessageReceived(message: RemoteMessage) {
        handler().onMessage(message.data)
    }

    override fun onNewToken(token: String) {
        runBlocking { handler().onNewToken(token) }
    }

    internal companion object {
        /** Tests replace the AppGraph handler. */
        @VisibleForTesting
        @Volatile
        var handlerOverride: PushHandler? = null
    }
}
