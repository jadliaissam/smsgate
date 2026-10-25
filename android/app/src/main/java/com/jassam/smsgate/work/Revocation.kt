package com.jassam.smsgate.work

import com.jassam.smsgate.auth.CredentialStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single revocation path (Review Focus 4). Any 401 from the device API, including
 * the synthetic [com.jassam.smsgate.api.ApiResult.CODE_NO_CREDENTIALS], comes here:
 * the credentials are wiped only if they still hold the token the request used
 * ([CredentialStore.clearIfToken], never an unconditional clear), then all
 * WorkManager work is cancelled (process, report, sync and token jobs, so nothing
 * driven by FCM keeps running). The journal is kept. The UI follows the credential
 * state back to pairing on its own.
 *
 * [exclusive] runs the post-pairing scheduling under the same lock, so a revocation
 * decided while unpaired can't cancel the work a concurrent pairing just scheduled.
 */
class Revocation(
    private val credentials: CredentialStore,
    private val cancelAllWork: suspend () -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val lock = Mutex()

    /**
     * Handles a 401 for a request made with [tokenUsed] (null: no credentials were
     * stored). Returns true when work was cancelled; false for a stale 401 (the phone
     * has been re-paired since), which changes nothing. Not cancellable: the worker
     * calling this is itself cancelled by it.
     */
    suspend fun onUnauthorized(tokenUsed: String?): Boolean = withContext(NonCancellable) {
        lock.withLock {
            val cleared = tokenUsed != null && credentials.clearIfToken(tokenUsed)
            if (!cleared && credentials.load() != null) {
                log("ignored a 401 for an old device token")
                return@withLock false
            }
            log(if (cleared) "device revoked: credentials wiped, work cancelled" else "no credentials: work cancelled")
            cancelAllWork()
            true
        }
    }

    /** Runs [block] (post-pairing scheduling) so it never interleaves with [onUnauthorized]. */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }
}
