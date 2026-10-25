package com.jassam.smsgate.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.jassam.smsgate.appGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the per-part sent/delivery results of [SmsIntents] PendingIntents
 * (manifest-registered, not exported). Foreign or malformed intents are ignored.
 * The journal work runs off the main thread under [goAsync]; the pending result is
 * finished on every path.
 */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ref = SmsIntents.parse(intent) ?: return
        val code = resultCode
        var format: String? = null
        var status: Int? = null
        if (ref.kind == PartKind.DELIVERED) {
            format = intent.getStringExtra(EXTRA_FORMAT)
            status = readStatus(intent.getByteArrayExtra(EXTRA_PDU), format)
        }
        val pending: PendingResult? = goAsync()
        scope.launch {
            try {
                val sink = sinkOverride ?: context.appGraph.smsResultHandler
                sink.handle(ref, code, format, status)
            } catch (e: Exception) {
                Log.w(TAG, "result ${ref.kind.wire} for ${ref.id} part ${ref.part} not recorded: ${e.javaClass.simpleName}")
            } finally {
                pending?.finish()
            }
        }
    }

    private fun readStatus(pdu: ByteArray?, format: String?): Int? {
        if (pdu == null) return null
        return try {
            SmsMessage.createFromPdu(pdu, format)?.status
        } catch (_: Exception) {
            null
        }
    }

    internal companion object {
        private const val TAG = "SmsGate"
        const val EXTRA_PDU = "pdu"
        const val EXTRA_FORMAT = "format"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Tests replace the AppGraph handler. */
        @VisibleForTesting
        @Volatile
        var sinkOverride: SmsResultSink? = null
    }
}
