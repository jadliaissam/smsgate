package com.jassam.smsgate.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.jassam.smsgate.journal.JournalEntry

/** Which result a part's PendingIntent carries. */
enum class PartKind(val wire: String) { SENT("sent"), DELIVERED("delivered") }

/** Identifies one part result: message [id], zero-based [part], [kind]. */
data class PartRef(val id: String, val part: Int, val kind: PartKind)

/**
 * The per-part result PendingIntents. Identity (and so uniqueness across parts and
 * messages) comes from the Intent data `smsgate://sms/<id>/<part>/<sent|delivered>`;
 * the request code is also stable per (id, part, kind). The component is explicit
 * ([SmsResultReceiver], not exported).
 *
 * Sent intents are `FLAG_IMMUTABLE`: only their result code matters. Delivery
 * intents must stay mutable (`FLAG_MUTABLE` on API 31+, the default below):
 * telephony attaches the status report (`pdu`, `format` extras) as fill-in, which
 * the platform drops for immutable PendingIntents, and without it a failed
 * delivery would read as delivered. They are explicit, so nothing can redirect
 * them.
 */
object SmsIntents {
    const val ACTION = "com.jassam.smsgate.action.SMS_RESULT"
    const val SCHEME = "smsgate"
    const val HOST = "sms"

    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val PART = Regex("0|[1-9][0-9]?")

    fun uri(ref: PartRef): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(HOST)
        .appendPath(ref.id)
        .appendPath(ref.part.toString())
        .appendPath(ref.kind.wire)
        .build()

    /** Strict parse of one of our result intents; anything else (wrong action, odd URI) is null. */
    fun parse(intent: Intent?): PartRef? {
        if (intent == null || intent.action != ACTION) return null
        return parse(intent.data)
    }

    fun parse(uri: Uri?): PartRef? {
        if (uri == null || uri.scheme != SCHEME || uri.encodedAuthority != HOST) return null
        if (uri.encodedQuery != null || uri.encodedFragment != null) return null
        val segments = uri.pathSegments
        if (segments.size != 3) return null
        val (id, partRaw, kindRaw) = segments
        if (!ID.matches(id) || !PART.matches(partRaw)) return null
        val part = partRaw.toInt()
        if (part >= JournalEntry.MAX_PARTS) return null
        val kind = PartKind.entries.firstOrNull { it.wire == kindRaw } ?: return null
        val ref = PartRef(id, part, kind)
        // Canonical form only (no extra slashes or encodings).
        return ref.takeIf { uri(it).toString() == uri.toString() }
    }

    /** Stable per (id, part, kind); distinct for every part and kind of one message. */
    fun requestCode(ref: PartRef): Int {
        val h = 31 * ref.id.hashCode() + ref.part
        return 2 * h + ref.kind.ordinal
    }

    fun intent(context: Context, ref: PartRef): Intent =
        Intent(ACTION, uri(ref), context, SmsResultReceiver::class.java)

    fun flags(kind: PartKind): Int = when {
        kind == PartKind.SENT -> PendingIntent.FLAG_IMMUTABLE
        Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_MUTABLE
        else -> 0
    }

    fun pendingIntent(context: Context, ref: PartRef): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode(ref), intent(context, ref), flags(ref.kind))
}
