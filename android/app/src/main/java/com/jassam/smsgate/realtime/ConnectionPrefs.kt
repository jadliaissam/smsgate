package com.jassam.smsgate.realtime

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The "Always connected" setting (default off), in its own small Preferences
 * DataStore. Use [Connectivity.setAlwaysConnected] to change it, so the service is
 * started or stopped at once. An unreadable store reads as off.
 */
class ConnectionPrefs(private val dataStore: DataStore<Preferences>) {
    /** The setting, emitted on change. */
    val alwaysConnected: Flow<Boolean> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[ALWAYS_CONNECTED] ?: false }
        .distinctUntilChanged()

    suspend fun alwaysConnected(): Boolean = alwaysConnected.first()

    /** Throws [IOException] if the store cannot be written. */
    suspend fun setAlwaysConnected(enabled: Boolean) {
        dataStore.edit { it[ALWAYS_CONNECTED] = enabled }
    }

    private companion object {
        val ALWAYS_CONNECTED = booleanPreferencesKey("always_connected")
    }
}
