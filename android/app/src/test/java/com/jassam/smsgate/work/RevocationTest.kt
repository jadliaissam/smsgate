package com.jassam.smsgate.work

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.jassam.smsgate.auth.CredentialStore
import com.jassam.smsgate.auth.Credentials
import com.jassam.smsgate.auth.FakeTokenCipher
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RevocationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dataStore: DataStore<Preferences>
    private val cipher = FakeTokenCipher()
    private lateinit var store: CredentialStore
    private val cancels = AtomicInteger()
    private val abandons = AtomicInteger()
    private lateinit var revocation: Revocation

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "c.preferences_pb") })
        store = CredentialStore(dataStore, cipher)
        revocation = Revocation(store, { cancels.incrementAndGet() }, { abandons.incrementAndGet() })
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun unauthorizedWithTheStoredTokenWipesAndCancels() = runBlocking {
        store.save("https://a", "dev-1", "dt_old")
        assertTrue(revocation.onUnauthorized("dt_old"))
        assertNull(store.load())
        assertEquals(1, cancels.get())
        assertEquals(1, abandons.get())
    }

    @Test
    fun staleUnauthorizedAfterRePairKeepsNewCredentialsAndWork() = runBlocking {
        store.save("https://a", "dev-2", "dt_new")
        assertFalse(revocation.onUnauthorized("dt_old"))
        assertEquals(Credentials("https://a", "dev-2", "dt_new"), store.load())
        assertEquals(0, cancels.get())
        assertEquals(0, abandons.get()) // the new pairing's CLAIMED rows are untouched
    }

    @Test
    fun anUnreadableTokenCancelsWorkButKeepsClaimedRows() = runBlocking {
        store.save("https://a", "dev-1", "dt_1")
        cipher.keyLost = true // Keystore glitch: the token can't be decrypted right now
        val fresh = CredentialStore(dataStore, cipher) // no cached token
        val r = Revocation(fresh, { cancels.incrementAndGet() }, { abandons.incrementAndGet() })
        assertTrue(r.onUnauthorized(null))
        assertEquals(1, cancels.get())
        assertEquals(0, abandons.get())
        assertEquals(com.jassam.smsgate.auth.StoredCredentials.PRESENT, fresh.stored())
    }

    @Test
    fun noCredentialsCancelsOnlyWhileStillUnpaired() = runBlocking {
        assertTrue(revocation.onUnauthorized(null))
        assertEquals(1, cancels.get())
        assertEquals(1, abandons.get()) // definitively unpaired
        store.save("https://a", "dev-1", "dt_1")
        assertFalse(revocation.onUnauthorized(null))
        assertEquals(1, cancels.get())
        assertEquals(1, abandons.get())
    }

    @Test
    fun aRevocationQueuedBehindPairingSeesTheNewCredentials() = runBlocking {
        // A pairing stores credentials and schedules work inside exclusive(); a
        // no-credentials revocation that arrives meanwhile must wait for it and then
        // find the phone paired (no cancel). The revocation is started UNDISPATCHED
        // while the hook holds the lock, so it is queued on the (FIFO) lock before the
        // hook saves; with the lock in place it can't finish before the hook does.
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val revokeDone = CompletableDeferred<Unit>()
        val hook = async(Dispatchers.IO) {
            revocation.exclusive {
                inside.complete(Unit)
                release.await()
                // Without the lock the revocation would finish here, before the save.
                // With it this only waits (the outcome never depends on the bound).
                withTimeoutOrNull(300) { revokeDone.await() }
                store.save("https://a", "dev-1", "dt_1")
            }
        }
        inside.await()
        val revoke = async(start = CoroutineStart.UNDISPATCHED) {
            revocation.onUnauthorized(null).also { revokeDone.complete(Unit) }
        }
        assertFalse("revocation must wait for the pairing hook", revoke.isCompleted)
        release.complete(Unit)
        withTimeout(5_000) { hook.await() }
        assertFalse(withTimeout(5_000) { revoke.await() })
        assertEquals(0, cancels.get())
        assertEquals(Credentials("https://a", "dev-1", "dt_1"), store.load())
    }

    @Test
    fun revocationStopsTheSocketServiceButAStaleOneDoesNot() = runBlocking {
        val stops = AtomicInteger()
        val r = Revocation(store, { cancels.incrementAndGet() }, stopRealtime = { stops.incrementAndGet() })
        store.save("https://a", "dev-2", "dt_new")
        assertFalse(r.onUnauthorized("dt_old"))
        assertEquals(0, stops.get())
        assertTrue(r.onUnauthorized("dt_new"))
        assertEquals(1, stops.get())
        assertTrue(r.onUnauthorized(null)) // still unpaired: stop again, harmless
        assertEquals(2, stops.get())
    }

    @Test
    fun aFailingServiceStopStillAbandonsClaimedRows() = runBlocking {
        val r = Revocation(store, { cancels.incrementAndGet() }, { abandons.incrementAndGet() }, stopRealtime = { throw IllegalStateException("x") })
        store.save("https://a", "dev-1", "dt_1")
        assertTrue(r.onUnauthorized("dt_1"))
        assertEquals(1, abandons.get())
    }

    @Test
    fun revocationAbandonsClaimedRowsAfterCancelling() = runBlocking {
        val order = mutableListOf<String>()
        val r = Revocation(store, { order += "cancel" }, abandonClaimed = { order += "abandon" })
        store.save("https://a", "dev-1", "dt_1")
        assertTrue(r.onUnauthorized("dt_1"))
        assertEquals(listOf("cancel", "abandon"), order)
    }
}
