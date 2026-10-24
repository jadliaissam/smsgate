package com.jassam.smsgate.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.time.Instant
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Client for the device API (`/device/v1/...`). Cheap to construct: every instance
 * shares the [client] passed in (one connection pool for the whole app, see
 * [DeviceHttp.client]). Calls never throw for HTTP or network failures; they
 * return an [ApiResult]. Coroutine cancellation cancels the HTTP call.
 *
 * Every authenticated call sends `Authorization: Bearer <token>` from
 * [tokenProvider]; when it returns null the call is not sent and the result is a
 * synthetic 401 ([ApiResult.CODE_NO_CREDENTIALS]), so callers run the same
 * "re-pair" path as for a revoked device. Unsafe methods always send
 * `Content-Type: application/json`, with or without a body (the backend
 * answers 415 otherwise).
 *
 * Responses carrying `server_time` update [clock].
 *
 * Retries: GETs may be retried by OkHttp on connection failure; unsafe methods
 * never are (a silently repeated claim would come back 409 `already_claimed`
 * although the first attempt took the message). Callers own retry policy.
 *
 * @param baseUrl server base URL, already normalised with [ServerUrl.normalize].
 */
class DeviceApi(
    baseUrl: String,
    private val tokenProvider: suspend () -> String?,
    client: OkHttpClient,
    private val clock: ServerClock? = null,
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val safeClient = client
    private val unsafeClient = client.newBuilder().retryOnConnectionFailure(false).build()

    /**
     * Pairs this phone (no token needed). 201 → device id and token.
     * Errors: 400 `invalid_pairing_code` (unknown, used or expired code),
     * 401 `invalid_credentials` (NOT a revocation here), 403 `email_not_verified`,
     * 422 `invalid_credentials_input`/`invalid_name`/`invalid_sims`/…,
     * 429 `rate_limited` (retryAfterSeconds), 503 `busy`.
     */
    suspend fun register(request: RegisterRequest): ApiResult<RegisterResponse> =
        call("POST", "device/v1/register", ApiJson.encodeToString(RegisterRequest.serializer(), request), RegisterResponse.serializer(), auth = false)

    /** Which account and name this device is paired as; updates the clock. */
    suspend fun me(): ApiResult<DeviceMe> =
        call("GET", "device/v1/me", null, DeviceMe.serializer())

    /** Records battery/charging/signal/SIMs/app version (422 `invalid_heartbeat`); returns server time. */
    suspend fun heartbeat(heartbeat: Heartbeat): ApiResult<ServerTime> =
        call("POST", "device/v1/heartbeat", ApiJson.encodeToString(Heartbeat.serializer(), heartbeat), ServerTime.serializer())

    /** Replaces the FCM token (204). 422 `invalid_fcm_token` when empty or over 4096 chars. */
    suspend fun setFcmToken(fcmToken: String): ApiResult<Unit> =
        call("PUT", "device/v1/fcm-token", ApiJson.encodeToString(FcmTokenBody.serializer(), FcmTokenBody(fcmToken)), null)

    /** Messages queued for this device, oldest first, with the server time. */
    suspend fun pending(): ApiResult<PendingMessages> =
        call("GET", "device/v1/messages/pending", null, PendingMessages.serializer())

    /**
     * Claims a message. Only a 200 ([ApiResult.Ok]) allows sending. 409
     * `already_claimed` or 410 `expired` → do nothing; 404 `message_not_found`.
     *
     * A cancelled call, a network error after the request was sent, or a 200 with an
     * unparseable body may still mean the server took the message. The caller must
     * not retry the claim or send; the sweeper will expire the message.
     */
    suspend fun claim(id: String): ApiResult<ClaimedMessage> =
        call("POST", messagePath(id, "claim"), null, ClaimedMessage.serializer())

    /**
     * Reports a status (200 with the message). Repeating an already-applied report
     * is a 200 too; 409 `invalid_transition` means the message is in another state;
     * 422 `invalid_status`/`invalid_error_code`/`invalid_error_message`.
     * [errorCode] (`[a-z0-9_]{1,64}`) and [errorMessage] (≤256 chars) only with [Status.FAILED].
     */
    suspend fun reportStatus(id: String, status: Status, errorCode: String? = null, errorMessage: String? = null): ApiResult<MessageState> =
        call(
            "POST",
            messagePath(id, "status"),
            ApiJson.encodeToString(StatusReport.serializer(), StatusReport(status, errorCode, errorMessage)),
            MessageState.serializer(),
        )

    /**
     * Revokes this device (204). A 401 means it was already revoked (or the token is
     * gone): callers treat 401 like success and clear local credentials either way.
     */
    suspend fun unregister(): ApiResult<Unit> =
        call("POST", "device/v1/unregister", null, null)

    private fun messagePath(id: String, action: String): HttpUrl =
        base.newBuilder().addPathSegments("device/v1/messages").addPathSegment(id).addPathSegment(action).build()

    private suspend fun <T> call(method: String, path: String, json: String?, serializer: KSerializer<T>?, auth: Boolean = true): ApiResult<T> =
        call(method, ServerUrl.resolve(base, path), json, serializer, auth)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> call(method: String, url: HttpUrl, json: String?, serializer: KSerializer<T>?, auth: Boolean = true): ApiResult<T> {
        val builder = Request.Builder().url(url)
        if (auth) {
            val token = tokenProvider()
                ?: return ApiResult.HttpError(401, ApiResult.CODE_NO_CREDENTIALS, "no device credentials stored")
            builder.header("Authorization", "Bearer $token")
        }
        val unsafe = method != "GET"
        val body: RequestBody? = if (unsafe) (json ?: "").encodeToByteArray().toRequestBody(JSON) else null
        builder.method(method, body)
        if (unsafe) builder.header("Content-Type", JSON_TYPE)
        builder.header("Accept", JSON_TYPE)

        val sentAt = clock?.mark() ?: 0L
        val raw = try {
            (if (unsafe) unsafeClient else safeClient).newCall(builder.build()).await()
        } catch (e: IOException) {
            return ApiResult.NetworkError(e)
        }
        val receivedAt = clock?.mark() ?: 0L

        if (raw.status !in 200..299) return httpError(raw)
        if (serializer == null) return ApiResult.Ok(Unit as T)
        return try {
            val value = ApiJson.decodeFromString(serializer, raw.body)
            serverTimeOf(value)?.let { clock?.update(it, sentAt, receivedAt) }
            ApiResult.Ok(value)
        } catch (e: SerializationException) {
            ApiResult.HttpError(raw.status, ApiResult.CODE_INVALID_RESPONSE, "unexpected response body")
        } catch (e: IllegalArgumentException) {
            ApiResult.HttpError(raw.status, ApiResult.CODE_INVALID_RESPONSE, "unexpected response body")
        }
    }

    private fun serverTimeOf(value: Any?): Instant? = when (value) {
        is DeviceMe -> value.serverTime
        is ServerTime -> value.serverTime
        is PendingMessages -> value.serverTime
        is ClaimedMessage -> value.serverTime
        else -> null
    }

    private fun httpError(raw: RawResponse): ApiResult.HttpError {
        val envelope = try {
            ApiJson.decodeFromString(ErrorEnvelope.serializer(), raw.body).error
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        val code = envelope?.code?.takeIf { it.isNotBlank() } ?: "http_${raw.status}"
        val message = envelope?.message?.takeIf { it.isNotBlank() } ?: "HTTP ${raw.status}"
        return ApiResult.HttpError(raw.status, code, message, raw.retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 })
    }

    @kotlinx.serialization.Serializable
    private data class FcmTokenBody(@kotlinx.serialization.SerialName("fcm_token") val fcmToken: String)

    private class RawResponse(val status: Int, val body: String, val retryAfter: String?)

    /** Enqueues the call (OkHttp's threads, no Dispatchers.IO hop) and reads the body there. */
    private suspend fun Call.await(): RawResponse = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = try {
                    response.use { RawResponse(it.code, it.body.readCapped(), it.header("Retry-After")) }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(raw) { _, _, _ -> }
            }
        })
    }

    private companion object {
        const val JSON_TYPE = "application/json"
        val JSON = JSON_TYPE.toMediaType()
        const val MAX_BODY_BYTES = 1L shl 20

        fun okhttp3.ResponseBody.readCapped(): String {
            val source = source()
            source.request(MAX_BODY_BYTES + 1)
            if (source.buffer.size > MAX_BODY_BYTES) throw IOException("response body over $MAX_BODY_BYTES bytes")
            return source.buffer.readUtf8()
        }
    }
}

/**
 * The app's single OkHttpClient for the device API (and, in A7, the WebSocket).
 * The WebSocket must use `client.newBuilder().readTimeout(0).pingInterval(..)`: the
 * 30 s read timeout here would kill an idle socket.
 */
object DeviceHttp {
    fun client(userAgent: String): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    /**
     * `SMSGate-Android/<versionName> (Android <release>; <model>)`, reduced to
     * printable ASCII (OkHttp rejects other header characters; models can be non-ASCII).
     */
    fun userAgent(versionName: String, release: String, model: String): String =
        "SMSGate-Android/$versionName (Android $release; $model)"
            .map { if (it in ' '..'~') it else '_' }
            .joinToString("")
}
