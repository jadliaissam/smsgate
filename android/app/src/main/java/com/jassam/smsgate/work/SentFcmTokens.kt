package com.jassam.smsgate.work

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest

/** Which FCM token the server last accepted for which device, so syncs re-send only a changed one. */
interface SentFcmTokens {
    fun matches(deviceId: String, token: String): Boolean
    fun remember(deviceId: String, token: String)
}

/**
 * [SentFcmTokens] in private SharedPreferences (app backup is off). Only a SHA-256
 * of device id + token is stored, never the token. Read from workers (off main).
 */
class PrefsSentFcmTokens(context: Context) : SentFcmTokens {
    private val prefs by lazy { context.applicationContext.getSharedPreferences("fcm_state", Context.MODE_PRIVATE) }

    override fun matches(deviceId: String, token: String): Boolean = prefs.getString(KEY, null) == digest(deviceId, token)

    override fun remember(deviceId: String, token: String) {
        prefs.edit { putString(KEY, digest(deviceId, token)) }
    }

    private fun digest(deviceId: String, token: String): String =
        MessageDigest.getInstance("SHA-256").digest("$deviceId\n$token".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY = "sent_token_sha256"
    }
}
