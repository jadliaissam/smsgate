package com.jassam.smsgate.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jassam.smsgate.ui.home.AccountInfo
import com.jassam.smsgate.ui.home.MeCache
import com.jassam.smsgate.ui.theme.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Local, non-secret preferences the settings screen edits. */
interface LocalPrefs {
    /** Light, dark or follow the system. */
    val themeMode: Flow<ThemeMode>

    /** This phone's local label (shown on Home; never sent to the server), or null. */
    val deviceLabel: Flow<String?>

    suspend fun setThemeMode(mode: ThemeMode)

    /** Blank clears the label. */
    suspend fun setDeviceLabel(label: String?)
}

/**
 * Small UI preferences in their own Preferences DataStore: theme override, the local
 * device label and the last `me()` answer (so Home shows the account offline). An
 * unreadable store reads as defaults. Writes throw [IOException] on failure.
 */
class AppSettings(private val dataStore: DataStore<Preferences>) : LocalPrefs, MeCache {
    private val prefs: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val themeMode: Flow<ThemeMode> = prefs
        .map { p -> ThemeMode.entries.firstOrNull { it.name == p[THEME] } ?: ThemeMode.System }
        .distinctUntilChanged()

    override val deviceLabel: Flow<String?> = prefs.map { it[LABEL]?.takeIf(String::isNotBlank) }.distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME] = mode.name }
    }

    override suspend fun setDeviceLabel(label: String?) {
        val clean = label?.trim().orEmpty()
        dataStore.edit { if (clean.isEmpty()) it.remove(LABEL) else it[LABEL] = clean }
    }

    override suspend fun get(deviceId: String): AccountInfo? {
        val p = prefs.first()
        if (p[ME_DEVICE] != deviceId) return null
        val name = p[ME_NAME] ?: return null
        val email = p[ME_EMAIL] ?: return null
        return AccountInfo(name, email)
    }

    override suspend fun put(deviceId: String, info: AccountInfo) {
        dataStore.edit {
            it[ME_DEVICE] = deviceId
            it[ME_NAME] = info.name
            it[ME_EMAIL] = info.email
        }
    }

    override suspend fun clear(deviceId: String) {
        dataStore.edit {
            if (it[ME_DEVICE] == deviceId) {
                it.remove(ME_DEVICE)
                it.remove(ME_NAME)
                it.remove(ME_EMAIL)
            }
        }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val LABEL = stringPreferencesKey("device_label")
        val ME_DEVICE = stringPreferencesKey("me_device_id")
        val ME_NAME = stringPreferencesKey("me_name")
        val ME_EMAIL = stringPreferencesKey("me_email")
    }
}
