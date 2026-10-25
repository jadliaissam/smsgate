package com.jassam.smsgate.sms

import android.app.PendingIntent
import com.jassam.smsgate.api.Status
import com.jassam.smsgate.journal.PendingReport
import java.util.Collections

internal class RecordingQueue : StatusReportQueue {
    val reports: MutableList<PendingReport> = Collections.synchronizedList(mutableListOf())
    override fun enqueue(id: String, status: Status, errorCode: String?, errorMessage: String?) {
        reports += PendingReport(id, status, errorCode, errorMessage)
    }
}

internal class FakeTransport : SmsTransport, SmsChannel {
    class Sent(val to: String, val parts: List<String>, val sent: List<PendingIntent>, val delivered: List<PendingIntent>)

    var available = true
    var partsOf: (String) -> List<String> = { listOf(it) }
    var throwOnSend: Exception? = null
    var throwOnChannel: Exception? = null
    var throwOnDivide: Exception? = null
    var onSend: () -> Unit = {}
    val channelRequests: MutableList<Int?> = Collections.synchronizedList(mutableListOf())
    val sends: MutableList<Sent> = Collections.synchronizedList(mutableListOf())

    override fun channel(subscriptionId: Int?): SmsChannel? {
        channelRequests += subscriptionId
        throwOnChannel?.let { throw it }
        return if (available) this else null
    }

    override fun divideMessage(body: String): List<String> {
        throwOnDivide?.let { throw it }
        return partsOf(body)
    }

    override fun sendMultipartTextMessage(
        destination: String,
        parts: List<String>,
        sentIntents: List<PendingIntent>,
        deliveryIntents: List<PendingIntent>,
    ) {
        onSend()
        throwOnSend?.let { throw it }
        sends += Sent(destination, parts, sentIntents, deliveryIntents)
    }
}
