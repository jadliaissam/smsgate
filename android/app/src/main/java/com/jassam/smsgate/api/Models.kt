package com.jassam.smsgate.api

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models of /device/v1/*. Field names match the backend exactly
// (backend/internal/devices/handlers.go, backend/internal/messages/device.go).
// Optional fields are nullable with a null default and are omitted when null
// (ApiJson.explicitNulls = false): the backend rejects unknown fields but not
// missing ones. Instants are RFC 3339 (InstantSerializer).

/** One SIM slot as reported by the phone. `number` is omitted when unknown. */
@Serializable
data class Sim(
    val slot: Int,
    @SerialName("subscription_id") val subscriptionId: Int,
    val carrier: String,
    val number: String? = null,
)

/**
 * `POST /device/v1/register`. Exactly one of [pairingCode] or ([email] + [password]);
 * use [withCode] or [withLogin].
 */
@Serializable
data class RegisterRequest(
    @SerialName("pairing_code") val pairingCode: String? = null,
    val email: String? = null,
    val password: String? = null,
    val name: String,
    val sims: List<Sim>,
    @SerialName("fcm_token") val fcmToken: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
) {
    override fun toString(): String =
        "RegisterRequest(pairingCode=${pairingCode?.let { "***" }}, email=$email, password=${password?.let { "***" }}, " +
            "name=$name, sims=$sims, fcmToken=${fcmToken?.let { "***" }}, appVersion=$appVersion)"

    companion object {
        fun withCode(code: String, name: String, sims: List<Sim>, fcmToken: String?, appVersion: String?) =
            RegisterRequest(pairingCode = code, name = name, sims = sims, fcmToken = fcmToken, appVersion = appVersion)

        fun withLogin(email: String, password: String, name: String, sims: List<Sim>, fcmToken: String?, appVersion: String?) =
            RegisterRequest(email = email, password = password, name = name, sims = sims, fcmToken = fcmToken, appVersion = appVersion)
    }
}

/** 201 from register. The token is shown once: store it with CredentialStore. */
@Serializable
data class RegisterResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_token") val deviceToken: String,
) {
    override fun toString(): String = "RegisterResponse(deviceId=$deviceId, deviceToken=***)"
}

/** `GET /device/v1/me`. */
@Serializable
data class DeviceMe(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    @SerialName("account_email") val accountEmail: String,
    @SerialName("server_time") @Serializable(with = InstantSerializer::class) val serverTime: Instant,
)

/**
 * `POST /device/v1/heartbeat` (also the WebSocket heartbeat frame's payload).
 * [battery] 0–100, [signal] 0–4; [sims] null = unchanged on the server.
 */
@Serializable
data class Heartbeat(
    val battery: Int? = null,
    val charging: Boolean? = null,
    val signal: Int? = null,
    val sims: List<Sim>? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

/** Response of heartbeat: just the server clock. */
@Serializable
data class ServerTime(
    @SerialName("server_time") @Serializable(with = InstantSerializer::class) val serverTime: Instant,
)

/** `GET /device/v1/messages/pending`. */
@Serializable
data class PendingMessages(
    @SerialName("server_time") @Serializable(with = InstantSerializer::class) val serverTime: Instant,
    val messages: List<PendingMessage> = emptyList(),
)

@Serializable
data class PendingMessage(
    val id: String,
    @SerialName("expires_at") @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)

/** 200 from `POST /device/v1/messages/{id}/claim`: the only place the body arrives. */
@Serializable
data class ClaimedMessage(
    val id: String,
    val to: String,
    val body: String,
    /** SIM slot to send from; null = the phone's default SMS SIM. */
    @SerialName("sim_slot") val simSlot: Int? = null,
    @SerialName("expires_at") @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
    @SerialName("server_time") @Serializable(with = InstantSerializer::class) val serverTime: Instant,
) {
    override fun toString(): String =
        "ClaimedMessage(id=$id, to=***, body=<${body.length} chars>, simSlot=$simSlot, expiresAt=$expiresAt, serverTime=$serverTime)"
}

/** Status a device may report. */
@Serializable
enum class Status(val wire: String) {
    @SerialName("sent") SENT("sent"),
    @SerialName("delivered") DELIVERED("delivered"),
    @SerialName("failed") FAILED("failed"),
}

/** `POST /device/v1/messages/{id}/status` body. error_* only with [Status.FAILED]. */
@Serializable
data class StatusReport(
    val status: Status,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)

/** The message as the server now holds it (200 from a status report). */
@Serializable
data class MessageState(
    val id: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("sim_slot") val simSlot: Int? = null,
    val to: String? = null,
    val status: String,
    @SerialName("client_ref") val clientRef: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant? = null,
    @SerialName("expires_at") @Serializable(with = InstantSerializer::class) val expiresAt: Instant? = null,
    @SerialName("dispatched_at") @Serializable(with = InstantSerializer::class) val dispatchedAt: Instant? = null,
    @SerialName("sent_at") @Serializable(with = InstantSerializer::class) val sentAt: Instant? = null,
    @SerialName("delivered_at") @Serializable(with = InstantSerializer::class) val deliveredAt: Instant? = null,
    @SerialName("failed_at") @Serializable(with = InstantSerializer::class) val failedAt: Instant? = null,
)

/** The backend's error envelope: `{"error":{"code":"...","message":"..."}}`. */
@Serializable
internal data class ErrorEnvelope(val error: ErrorBody? = null) {
    @Serializable
    data class ErrorBody(val code: String? = null, val message: String? = null)
}
