package com.jassam.smsgate.ui.pairing

import com.jassam.smsgate.api.ApiResult

/** Why pairing failed, each with its own copy (PairingScreen `messageOf`). */
sealed interface PairingError {
    /** The server address is not a valid HTTPS URL (plain HTTP only to local hosts in debug). */
    data object InvalidServer : PairingError

    /** Device name empty or over 64 characters (also the server's 422 `invalid_name`). */
    data object InvalidName : PairingError

    /** 400 `invalid_pairing_code`: unknown, already used or expired (the server doesn't say which). */
    data object InvalidCode : PairingError

    /** 401 `invalid_credentials` on the login path. */
    data object InvalidCredentials : PairingError

    /** 403 `email_not_verified`. */
    data object EmailNotVerified : PairingError

    /** 429 `rate_limited`, with the `Retry-After` seconds when sent. */
    data class RateLimited(val retryAfterSeconds: Long?) : PairingError

    /** No response: offline, DNS, TLS, timeout. */
    data object Network : PairingError

    /** 5xx or 503 `busy`. */
    data object ServerUnavailable : PairingError

    /** 404/405 or a 2xx body that isn't the register response: the address isn't an SMSGate server. */
    data object NotSmsGateServer : PairingError

    /** Registered, but the token could not be stored (Keystore or disk failure). */
    data object StorageFailed : PairingError

    /** A scanned QR code that isn't an SMSGate pairing code. */
    data object InvalidQr : PairingError

    /** The Google code scanner failed to start (module missing, Play services error). */
    data object ScannerUnavailable : PairingError

    /** Anything else; [code] is the backend's error code (never shown, kept for diagnostics). */
    data class Unknown(val code: String) : PairingError

    companion object {
        /** Maps a failed register call. */
        fun of(result: ApiResult<*>): PairingError = when (result) {
            is ApiResult.Ok -> Unknown("ok")
            is ApiResult.NetworkError -> Network
            is ApiResult.HttpError -> when {
                result.code == "invalid_pairing_code" || result.code == "expired" -> InvalidCode
                result.code == "invalid_credentials" -> InvalidCredentials
                result.code == "email_not_verified" -> EmailNotVerified
                result.code == "rate_limited" || result.status == 429 -> RateLimited(result.retryAfterSeconds)
                result.code == "invalid_name" -> InvalidName
                result.code == ApiResult.CODE_INVALID_RESPONSE -> NotSmsGateServer
                result.status == 404 || result.status == 405 -> NotSmsGateServer
                result.status >= 500 -> ServerUnavailable
                else -> Unknown(result.code)
            }
        }
    }
}
