package com.jassam.smsgate.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Pairing credentials. The token is secret: [toString] redacts it. */
data class Credentials(val serverUrl: String, val deviceId: String, val deviceToken: String) {
    override fun toString(): String = "Credentials(serverUrl=$serverUrl, deviceId=$deviceId, deviceToken=***)"
}

/** Whether the app holds usable credentials. */
sealed interface PairingState {
    data object Unpaired : PairingState
    data class Paired(val serverUrl: String, val deviceId: String) : PairingState
}

/**
 * Device credentials (D8): server URL and device id in a Preferences DataStore,
 * the device token in the same store encrypted by [cipher] (Keystore AES-GCM in
 * production). Credentials whose token cannot be decrypted (key lost after a
 * restore, OEM Keystore bug, corrupt data) read as absent, which sends the app
 * back to pairing instead of crashing. The decrypted token is cached in memory
 * so API calls do not hit the Keystore each time.
 *
 * Keystore work runs on [Dispatchers.IO]. [save] and the clear functions are
 * serialized by a mutex; use [clearIfToken] / [clearIfDevice] on the 401 path so a
 * late 401 from an old request cannot wipe credentials that were just re-paired.
 *
 * Use one instance per process (AppGraph): DataStore forbids two stores on one file.
 */
class CredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
) {
    @Volatile
    private var cache: Pair<String, String>? = null // encrypted blob → token

    private val writeLock = Mutex()

    /** Paired/unpaired, emitted on change. */
    val state: Flow<PairingState> = prefs()
        .map { p -> read(p)?.let { PairingState.Paired(it.serverUrl, it.deviceId) } ?: PairingState.Unpaired }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    /**
     * Stores credentials after a successful register. Throws
     * [java.security.GeneralSecurityException] if the token cannot be encrypted, or
     * [IOException] if the store cannot be written; nothing is stored then.
     */
    suspend fun save(serverUrl: String, deviceId: String, deviceToken: String) {
        writeLock.withLock {
            val blob = withContext(Dispatchers.IO) { cipher.encrypt(deviceToken) }
            dataStore.edit {
                it[SERVER_URL] = serverUrl
                it[DEVICE_ID] = deviceId
                it[TOKEN] = blob
            }
            cache = blob to deviceToken
        }
    }

    /** All three values, or null when unpaired or the token is undecryptable. */
    suspend fun load(): Credentials? = withContext(Dispatchers.IO) { read(prefs().first()) }

    suspend fun serverUrl(): String? = load()?.serverUrl
    suspend fun deviceId(): String? = load()?.deviceId
    suspend fun deviceToken(): String? = load()?.deviceToken

    /** Wipes every stored value and destroys the Keystore key (unpair / revoked). */
    suspend fun clear() {
        writeLock.withLock { clearLocked() }
    }

    /**
     * [clear], but only if the stored token is still [token]. For the 401 path: a
     * response to a request made with an old token must not wipe credentials saved
     * since. Returns whether it cleared.
     */
    suspend fun clearIfToken(token: String): Boolean = writeLock.withLock {
        if (load()?.deviceToken != token) return@withLock false
        clearLocked()
        true
    }

    /** [clear], but only if the stored device id is still [deviceId]. Returns whether it cleared. */
    suspend fun clearIfDevice(deviceId: String): Boolean = writeLock.withLock {
        if (load()?.deviceId != deviceId) return@withLock false
        clearLocked()
        true
    }

    // Not cancellable: a cancelled unpair must still wipe the store and destroy the key.
    private suspend fun clearLocked() = withContext(NonCancellable) {
        cache = null
        try {
            dataStore.edit { it.clear() }
        } finally {
            withContext(Dispatchers.IO) { cipher.reset() }
        }
        cache = null // a concurrent load() may have repopulated it from the old blob
    }

    private fun prefs(): Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private fun read(p: Preferences): Credentials? {
        val url = p[SERVER_URL] ?: return null
        val id = p[DEVICE_ID] ?: return null
        val blob = p[TOKEN] ?: return null
        val token = token(blob) ?: return null
        return Credentials(url, id, token)
    }

    private fun token(blob: String): String? {
        cache?.let { (b, t) -> if (b == blob) return t }
        val t = cipher.decrypt(blob) ?: return null
        cache = blob to t
        return t
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val TOKEN = stringPreferencesKey("device_token_enc")
    }
}
