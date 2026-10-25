package com.jassam.smsgate.work

import com.jassam.smsgate.auth.CredentialStore
import com.jassam.smsgate.auth.StoredCredentials
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single revocation path (Review Focus 4). Every backend 401 from the device API
 * (enveloped, see [isRevocation]), and the synthetic
 * [com.jassam.smsgate.api.ApiResult.CODE_NO_CREDENTIALS], comes here: the
 * credentials are wiped only if they still hold the token the request used
 * ([CredentialStore.clearIfToken], never an unconditional clear), then all
 * WorkManager work is cancelled (process, report, sync and token jobs, so nothing
 * driven by FCM keeps running) and the always-connected socket service is stopped
 * (its socket also stops itself on `revoked`). Journal rows still CLAIMED are
 * abandoned ([CrashRecovery.abandonClaimed]) so a later pairing never sends them, but only
 * when the credentials were really cleared or the store is definitively empty: a
 * token that is just unreadable right now (Keystore/DataStore glitch) leaves them for
 * recovery. The journal itself is kept. The UI follows the credential state back to
 * pairing on its own.
 *
 * Not undone: a send already handed to Android (its row stays SENDING and a later
 * pairing's recovery settles it as `outcome_unknown`), and a claim that returned 200
 * just before the revocation, whose worker may journal the row after the abandon step
 * and send it (that part is non-cancellable after a 200, by design); its report then
 * fails against the new pairing with 404 and is dropped.
 *
 * [exclusive] runs the post-pairing scheduling under the same lock, so a revocation
 * decided while unpaired can't cancel the work a concurrent pairing just scheduled.
 */
class Revocation(
    private val credentials: CredentialStore,
    private val cancelAllWork: suspend () -> Unit,
    private val abandonClaimed: suspend () -> Unit = {},
    private val log: (String) -> Unit = {},
    /** Stops the always-connected socket service (cancelling work does not stop a service). */
    private val stopRealtime: () -> Unit = {},
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
            try {
                stopRealtime()
            } catch (e: Exception) {
                log("stopping the socket service failed: ${e.javaClass.simpleName}")
            }
            if (cleared || credentials.stored() == StoredCredentials.NONE) {
                try {
                    abandonClaimed()
                } catch (e: Exception) {
                    log("abandoning claimed rows failed: ${e.javaClass.simpleName}")
                }
            }
            true
        }
    }

    /** Runs [block] (post-pairing scheduling) so it never interleaves with [onUnauthorized]. */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }
}
