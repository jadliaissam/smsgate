package com.jassam.smsgate.ui.pairing

import com.jassam.smsgate.api.ApiResult
import com.jassam.smsgate.api.DeviceApi
import com.jassam.smsgate.api.RegisterRequest
import com.jassam.smsgate.api.Sim
import com.jassam.smsgate.auth.CredentialStore
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** How the user pairs. Secrets never appear in [toString]. */
sealed interface PairingMethod {
    val server: String

    /** Dashboard pairing code (typed or from the QR). */
    data class Code(override val server: String, val code: String) : PairingMethod {
        override fun toString(): String = "Code(server=$server, code=***)"
    }

    /** Account email and password ("Log in instead"). */
    data class Login(override val server: String, val email: String, val password: String) : PairingMethod {
        override fun toString(): String = "Login(server=$server, email=***, password=***)"
    }
}

sealed interface PairingResult {
    data object Success : PairingResult
    data class Failure(val error: PairingError) : PairingResult
}

/**
 * Runs after credentials are stored. Task A6 implements it to schedule the
 * periodic sync/heartbeat worker and run a first catch-up; until then the app
 * wires a no-op (see AppGraph.postPairingHook).
 */
fun interface PostPairingHook {
    suspend fun onPaired()
}

/** Device name rules shared by the form and [Pairer] (backend: 1–64 characters after trimming). */
object DeviceName {
    const val MAX = 64

    fun isValid(name: String): Boolean = name.trim().let { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX }

    /** A default from `Build.MODEL`, trimmed and capped, or [fallback] when blank. */
    fun default(model: String?, fallback: String): String {
        val m = model?.trim().orEmpty().ifEmpty { fallback }
        return if (m.codePointCount(0, m.length) <= MAX) m else m.substring(0, m.offsetByCodePoints(0, MAX))
    }
}

/**
 * Registers this phone (`POST /device/v1/register`) and stores the credentials.
 * Validates input first (nothing invalid reaches the server), collects the SIM
 * list and, when [pair] is told push is available, the FCM token (D11: without
 * Play services the device registers without one).
 *
 * Once the server has answered 201, storing the token and running the hook are
 * not cancellable: the server-side device exists, and leaving the screen must
 * not lose its only token. Never logs the token, code or password.
 */
class Pairer(
    private val normalizeServerUrl: (String) -> String?,
    private val apiFactory: (String) -> DeviceApi,
    private val credentials: CredentialStore,
    private val sims: () -> List<Sim>,
    private val fcmToken: suspend () -> String?,
    private val appVersion: String,
    private val postPairing: PostPairingHook,
    /** Diagnostics sink (android.util.Log in the app); messages never carry secrets. */
    private val warn: (String) -> Unit = {},
) {
    suspend fun pair(method: PairingMethod, name: String, withPush: Boolean): PairingResult {
        val deviceName = name.trim()
        if (!DeviceName.isValid(deviceName)) return PairingResult.Failure(PairingError.InvalidName)
        val server = normalizeServerUrl(method.server) ?: return PairingResult.Failure(PairingError.InvalidServer)
        val code = (method as? PairingMethod.Code)?.let { PairingCode.normalize(it.code) }
        if (code != null && !PairingCode.isComplete(code)) return PairingResult.Failure(PairingError.InvalidCode)

        val simList = withContext(Dispatchers.IO) { sims() }
        val token = if (withPush) fcmToken() else null
        val version = appVersion.take(MAX_APP_VERSION)
        val request = when (method) {
            is PairingMethod.Code -> RegisterRequest.withCode(checkNotNull(code), deviceName, simList, token, version)
            is PairingMethod.Login -> RegisterRequest.withLogin(method.email.trim(), method.password, deviceName, simList, token, version)
        }

        val result = apiFactory(server).register(request)
        if (result !is ApiResult.Ok) return PairingResult.Failure(PairingError.of(result))

        return withContext(NonCancellable) {
            try {
                credentials.save(server, result.value.deviceId, result.value.deviceToken)
            } catch (e: GeneralSecurityException) {
                warn("could not store credentials: ${e.javaClass.simpleName}")
                return@withContext PairingResult.Failure(PairingError.StorageFailed)
            } catch (e: IOException) {
                warn("could not store credentials: ${e.javaClass.simpleName}")
                return@withContext PairingResult.Failure(PairingError.StorageFailed)
            }
            try {
                postPairing.onPaired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Paired regardless: the periodic sync (A6) reschedules itself on app start.
                warn("post-pairing hook failed: ${e.javaClass.simpleName}")
            }
            PairingResult.Success
        }
    }

    private companion object {
        const val MAX_APP_VERSION = 32
    }
}
