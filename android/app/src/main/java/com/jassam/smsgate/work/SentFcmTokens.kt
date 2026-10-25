package com.jassam.smsgate.work

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest

/**
 * Which FCM token the server last accepted for which device, so syncs re-send only a
 * changed one. The memory expires ([PrefsSentFcmTokens.MAX_AGE_MS]): the server drops
 * a token FCM rejects, so a periodic re-send lets that heal on its own.
 */
interface SentFcmTokens {
    fun matches(deviceId: String, token: String): Boolean
    fun remember(deviceId: String, token: String)

    /**
     * Whether the server accepted exactly this token for this device at some point,
     * however long ago (home screen's "push OK"; [matches] also expires the memory).
     */
    fun accepted(deviceId: String, token: String): Boolean = matches(deviceId, token)
}

/**
 * [SentFcmTokens] in private SharedPreferences (app backup is off). Only a SHA-256
 * of device id + token and the time it was accepted are stored, never the token.
 * Read from workers (off main).
 */
class PrefsSentFcmTokens(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) : SentFcmTokens {
    private val prefs by lazy { context.applicationContext.getSharedPreferences("fcm_state", Context.MODE_PRIVATE) }

    override fun matches(deviceId: String, token: String): Boolean {
        if (prefs.getString(KEY, null) != digest(deviceId, token)) return false
        val age = now() - prefs.getLong(KEY_AT, Long.MIN_VALUE)
        return age in 0 until MAX_AGE_MS // a clock moved backwards also re-sends
    }

    override fun accepted(deviceId: String, token: String): Boolean = prefs.getString(KEY, null) == digest(deviceId, token)

    override fun remember(deviceId: String, token: String) {
        prefs.edit {
            putString(KEY, digest(deviceId, token))
            putLong(KEY_AT, now())
        }
    }

    private fun digest(deviceId: String, token: String): String =
        MessageDigest.getInstance("SHA-256").digest("$deviceId\n$token".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** After this, the current token is sent again even if unchanged. */
        const val MAX_AGE_MS: Long = 24L * 60 * 60 * 1000
        private const val KEY = "sent_token_sha256"
        private const val KEY_AT = "sent_token_at"
    }
}
