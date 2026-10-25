package com.jassam.smsgate.work

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * When the last successful sync finished (heartbeat and catch-up both answered by the
 * server), for the home screen. Written by [SyncWorker].
 */
interface SyncStatus {
    /** Epoch ms of the last successful sync, or null before the first one. */
    val lastSuccess: Flow<Long?>

    suspend fun markSuccess(atMs: Long)

    /** Records nothing (tests and callers without a store). */
    object None : SyncStatus {
        override val lastSuccess: Flow<Long?> = flowOf(null)
        override suspend fun markSuccess(atMs: Long) = Unit
    }
}

/** [SyncStatus] in its own small Preferences DataStore. An unreadable store reads as "never". */
class DataStoreSyncStatus(private val dataStore: DataStore<Preferences>) : SyncStatus {
    override val lastSuccess: Flow<Long?> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[LAST_SUCCESS] }
        .distinctUntilChanged()

    /** Throws [IOException] if the store cannot be written (SyncWorker logs and goes on). */
    override suspend fun markSuccess(atMs: Long) {
        dataStore.edit { it[LAST_SUCCESS] = atMs }
    }

    private companion object {
        val LAST_SUCCESS = longPreferencesKey("last_success_ms")
    }
}
