package com.jassam.smsgate.api

import java.io.IOException

/**
 * Outcome of a device API call. HTTP errors are values, never exceptions:
 * callers branch on the variant.
 */
sealed interface ApiResult<out T> {
    /** 2xx with a parsed body ([Unit] for 204 endpoints). */
    data class Ok<out T>(val value: T) : ApiResult<T>

    /**
     * Non-2xx response. [code] is the backend's `error.code` (e.g. `already_claimed`,
     * `invalid_pairing_code`), or `http_<status>` when the body is not the JSON error
     * envelope (a proxy's HTML 502, say). A 2xx whose body cannot be parsed is reported
     * with code [CODE_INVALID_RESPONSE]. [retryAfterSeconds] comes from a `Retry-After`
     * header in whole seconds (sent with 429 `rate_limited`).
     */
    data class HttpError(
        val status: Int,
        val code: String,
        val message: String,
        val retryAfterSeconds: Long? = null,
    ) : ApiResult<Nothing> {
        /**
         * 401. On every authenticated endpoint this means the device token is no longer
         * valid (revoked, or no credentials stored: [CODE_NO_CREDENTIALS]); the caller wipes
         * credentials and shows pairing. On `register` it means `invalid_credentials`.
         */
        val isUnauthorized: Boolean get() = status == 401
    }

    /** No HTTP response: DNS, connect, TLS, timeout or a dropped connection. */
    data class NetworkError(val cause: IOException) : ApiResult<Nothing>

    companion object {
        /** Synthetic 401 code: an authenticated call was made with no stored device token. */
        const val CODE_NO_CREDENTIALS = "no_credentials"

        /** Synthetic code: a 2xx response whose body could not be parsed. */
        const val CODE_INVALID_RESPONSE = "invalid_response"
    }
}

val ApiResult<*>.isUnauthorized: Boolean get() = this is ApiResult.HttpError && isUnauthorized

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Ok)?.value

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(transform(value))
    is ApiResult.HttpError -> this
    is ApiResult.NetworkError -> this
}
