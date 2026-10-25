package com.jassam.smsgate.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jassam.smsgate.api.ApiResult
import com.jassam.smsgate.auth.Credentials
import com.jassam.smsgate.realtime.ConnectionState
import com.jassam.smsgate.ui.home.HomeLogic
import com.jassam.smsgate.ui.home.PhoneInfo
import com.jassam.smsgate.ui.home.SimCard
import com.jassam.smsgate.ui.home.SimsInfo
import com.jassam.smsgate.ui.pairing.DeviceName
import com.jassam.smsgate.ui.theme.ThemeMode
import com.jassam.smsgate.work.isRevocation
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the settings screen uses (built from AppGraph; fakes in tests). */
class SettingsDeps(
    val credentials: suspend () -> Credentials?,
    /** `POST /device/v1/unregister` with exactly these credentials' token. */
    val unregister: suspend (Credentials) -> ApiResult<Unit>,
    /**
     * The single revocation path (Revocation.onUnauthorized): clears credentials only if
     * they still hold this token, cancels work, stops the socket service, abandons
     * CLAIMED rows. Returns whether it acted.
     */
    val revoke: suspend (tokenUsed: String?) -> Boolean,
    val alwaysConnected: Flow<Boolean>,
    /** Connectivity.setAlwaysConnected: false when the setting could not be saved. */
    val setAlwaysConnected: suspend (Boolean) -> Boolean,
    val connection: Flow<ConnectionState>,
    /** Plugged into power (binder call; run on [io]). */
    val isCharging: () -> Boolean,
    /** Exempt from battery optimisation (binder call; run on [io]). */
    val batteryExempt: () -> Boolean,
    val prefs: LocalPrefs,
    val phone: suspend () -> PhoneInfo,
    /** One immediate sync (unique, KEEP). False when nothing was enqueued (not paired). */
    val syncNow: suspend () -> Boolean,
    val lastSync: Flow<Long?>,
    val now: () -> Long,
    val appVersion: String,
    /** Drops the cached account name/email of an unpaired device (the local label stays). */
    val forgetAccount: suspend (deviceId: String) -> Unit = {},
    val io: CoroutineDispatcher = Dispatchers.IO,
    val log: (String) -> Unit = {},
)

/** The default SMS SIM, as Settings shows it. */
@Immutable
sealed interface DefaultSim {
    data object Loading : DefaultSim

    /** READ_PHONE_STATE missing. */
    data object NoPermission : DefaultSim

    /** No default set (Android asks, or the only SIM is used). */
    data object None : DefaultSim

    data class Card(val sim: SimCard) : DefaultSim
}

enum class UnpairFailure { Network, Server }

/** Feedback after "Sync now"; [None] shows the last sync time. */
enum class SyncFeedback { None, Requested, Done }

/** The unpair flow. */
@Immutable
sealed interface UnpairState {
    data object Idle : UnpairState

    /** "Unpair this phone?" */
    data object Confirm : UnpairState

    data object Working : UnpairState

    /** unregister failed: nothing was cleared; retry, or remove locally. */
    data class Failed(val reason: UnpairFailure) : UnpairState

    /** "Remove from this phone only?" (the device stays listed on the dashboard). */
    data object ConfirmLocal : UnpairState
}

@Immutable
data class SettingsUiState(
    val appVersion: String,
    val alwaysConnected: Boolean = false,
    val toggleBusy: Boolean = false,
    /** The last toggle could not be saved. */
    val toggleFailed: Boolean = false,
    val connection: ConnectionState = ConnectionState.OFF,
    /** Ask for the battery exemption (D12): just enabled, unplugged and not exempt. */
    val exemptionPrompt: Boolean = false,
    val batteryExempt: Boolean = true,
    val deviceLabel: String? = null,
    val defaultSim: DefaultSim = DefaultSim.Loading,
    val theme: ThemeMode = ThemeMode.System,
    val sync: SyncFeedback = SyncFeedback.None,
    val lastSyncMs: Long? = null,
    val unpair: UnpairState = UnpairState.Idle,
    /** A local-preference write failed (label or theme). */
    val saveFailed: Boolean = false,
) {
    /** Always connected without the exemption: Doze may drop the socket while unplugged. */
    val dozeWarning: Boolean get() = alwaysConnected && !batteryExempt
}

/**
 * Settings: Always connected (with the Doze rule), local label, default SIM, sync,
 * theme, and the unpair flow. Unpairing never clears credentials or stops the service
 * itself: success (2xx) or a backend 401 (already revoked) hands the token captured
 * before the call to [SettingsDeps.revoke]; anything else leaves everything in place.
 */
class SettingsViewModel(private val deps: SettingsDeps) : ViewModel() {
    private data class Local(
        val toggleBusy: Boolean = false,
        val toggleFailed: Boolean = false,
        val exemptionPrompt: Boolean = false,
        val batteryExempt: Boolean = true,
        val defaultSim: DefaultSim = DefaultSim.Loading,
        val sync: SyncFeedback = SyncFeedback.None,
        val unpair: UnpairState = UnpairState.Idle,
        val saveFailed: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    private data class Prefs(val always: Boolean, val connection: ConnectionState, val label: String?, val theme: ThemeMode, val lastSync: Long?)

    private val prefs: Flow<Prefs> = combine(
        deps.alwaysConnected.catch { emit(false) },
        deps.connection.catch { emit(ConnectionState.OFF) },
        deps.prefs.deviceLabel.catch { emit(null) },
        deps.prefs.themeMode.catch { emit(ThemeMode.System) },
        deps.lastSync.catch { emit(null) },
        ::Prefs,
    )

    val state: StateFlow<SettingsUiState> = combine(local, prefs) { l, p ->
        SettingsUiState(
            appVersion = deps.appVersion,
            alwaysConnected = p.always,
            toggleBusy = l.toggleBusy,
            toggleFailed = l.toggleFailed,
            connection = p.connection,
            exemptionPrompt = l.exemptionPrompt,
            batteryExempt = l.batteryExempt,
            deviceLabel = p.label,
            defaultSim = l.defaultSim,
            theme = p.theme,
            sync = l.sync,
            lastSyncMs = p.lastSync,
            unpair = l.unpair,
            saveFailed = l.saveFailed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState(appVersion = deps.appVersion))

    init {
        onResume()
    }

    /** Screen resumed: the exemption or SIMs may have changed in system settings. */
    fun onResume() {
        viewModelScope.launch {
            val exempt = io { deps.batteryExempt() } ?: true
            // The user granted the exemption from the prompt: nothing left to ask.
            local.update { it.copy(batteryExempt = exempt, exemptionPrompt = it.exemptionPrompt && !exempt) }
        }
        viewModelScope.launch {
            val info = guarded("phone info") { deps.phone() }
            local.update { it.copy(defaultSim = defaultSim(info)) }
        }
    }

    // ---- Always connected ----

    fun setAlwaysConnected(enabled: Boolean) {
        if (local.value.toggleBusy) return
        local.update { it.copy(toggleBusy = true, toggleFailed = false, exemptionPrompt = if (enabled) it.exemptionPrompt else false) }
        viewModelScope.launch {
            val saved = guarded("always-connected toggle") { deps.setAlwaysConnected(enabled) } ?: false
            if (!saved) {
                local.update { it.copy(toggleBusy = false, toggleFailed = true) }
                return@launch
            }
            val charging = io { deps.isCharging() } ?: false
            val exempt = io { deps.batteryExempt() } ?: true
            local.update {
                it.copy(
                    toggleBusy = false,
                    batteryExempt = exempt,
                    exemptionPrompt = enabled && needsExemptionPrompt(charging, exempt),
                )
            }
        }
    }

    /** The prompt was answered (allowed, or "not now"); the Doze note stays while not exempt. */
    fun dismissExemptionPrompt() = local.update { it.copy(exemptionPrompt = false) }

    fun dismissToggleError() = local.update { it.copy(toggleFailed = false) }

    // ---- Local preferences ----

    /** Saves the local label; blank clears it. Returns false (and saves nothing) when too long. */
    fun saveDeviceLabel(label: String): Boolean {
        val clean = label.trim()
        if (clean.isNotEmpty() && !DeviceName.isValid(clean)) return false
        viewModelScope.launch {
            val ok = guarded("device label") { deps.prefs.setDeviceLabel(clean.ifEmpty { null }) } != null
            local.update { it.copy(saveFailed = !ok) }
        }
        return true
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch {
            val ok = guarded("theme") { deps.prefs.setThemeMode(mode) } != null
            local.update { it.copy(saveFailed = !ok) }
        }
    }

    fun dismissSaveError() = local.update { it.copy(saveFailed = false) }

    // ---- Sync ----

    private var syncJob: Job? = null

    /**
     * Requests one sync and shows "Syncing…" until a successful sync lands (then "Synced
     * just now" for [SYNC_DONE_SHOW_MS]) or [SYNC_WAIT_MS] passes (offline: the job runs
     * later), after which the last sync time shows again. Nothing enqueued (not paired)
     * shows nothing.
     */
    fun syncNow() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            val at = deps.now()
            val enqueued = guarded("sync now") { deps.syncNow() } ?: false
            if (!enqueued) {
                local.update { it.copy(sync = SyncFeedback.None) }
                return@launch
            }
            local.update { it.copy(sync = SyncFeedback.Requested) }
            val landed = withTimeoutOrNull(SYNC_WAIT_MS) {
                deps.lastSync.catch { emit(null) }.first { it != null && it >= at }
            } != null
            if (landed) {
                local.update { it.copy(sync = SyncFeedback.Done) }
                delay(SYNC_DONE_SHOW_MS)
            }
            local.update { it.copy(sync = SyncFeedback.None) }
        }
    }

    // ---- Unpair ----

    fun requestUnpair() = local.update {
        if (it.unpair == UnpairState.Idle || it.unpair is UnpairState.Failed) it.copy(unpair = UnpairState.Confirm) else it
    }

    fun requestLocalRemoval() = local.update {
        if (it.unpair == UnpairState.Working) it else it.copy(unpair = UnpairState.ConfirmLocal)
    }

    fun cancelUnpair() = local.update { if (it.unpair == UnpairState.Working) it else it.copy(unpair = UnpairState.Idle) }

    /** Confirmed (or retried): unregister on the server, then revoke locally with the token used. */
    fun confirmUnpair() {
        val current = local.value.unpair
        if (current != UnpairState.Confirm && current !is UnpairState.Failed) return
        local.update { it.copy(unpair = UnpairState.Working) }
        viewModelScope.launch {
            val credentials = guarded("credentials") { deps.credentials() }
            if (credentials == null) {
                // Nothing usable stored: the revocation path cancels leftover work if the store is empty.
                finish(guarded("revoke") { deps.revoke(null) } ?: false, deviceId = null)
                return@launch
            }
            val result = try {
                deps.unregister(credentials)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.log("unpair: unregister threw ${e.javaClass.simpleName}")
                null
            }
            when {
                result is ApiResult.Ok || (result != null && result.isRevocation) ->
                    finish(guarded("revoke") { deps.revoke(credentials.deviceToken) } ?: false, credentials.deviceId)
                result is ApiResult.HttpError -> {
                    deps.log("unpair: HTTP ${result.status} ${result.code}")
                    local.update { it.copy(unpair = UnpairState.Failed(UnpairFailure.Server)) }
                }
                else -> local.update { it.copy(unpair = UnpairState.Failed(UnpairFailure.Network)) }
            }
        }
    }

    /** "Remove from this phone only", confirmed: the same revocation path, no server call. */
    fun confirmLocalRemoval() {
        if (local.value.unpair != UnpairState.ConfirmLocal) return
        local.update { it.copy(unpair = UnpairState.Working) }
        viewModelScope.launch {
            val credentials = guarded("credentials") { deps.credentials() }
            finish(guarded("revoke") { deps.revoke(credentials?.deviceToken) } ?: false, credentials?.deviceId)
        }
    }

    /**
     * After the revocation path ran. When it acted, the credential state flow takes
     * the app back to pairing; keep showing progress until then. When it did nothing
     * (the phone was re-paired meanwhile, so the old token no longer matches), go idle.
     * When it acted, the unpaired device's cached account details are dropped.
     */
    private suspend fun finish(acted: Boolean, deviceId: String?) {
        if (acted && deviceId != null) guarded("forget account") { deps.forgetAccount(deviceId) }
        local.update { it.copy(unpair = if (acted) UnpairState.Working else UnpairState.Idle) }
    }

    private suspend fun <T> io(block: () -> T): T? = guarded("system state") { withContext(deps.io) { block() } }

    private suspend fun <T> guarded(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        deps.log("settings: $what failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** How long "Syncing…" waits for the sync to land before falling back to the last sync time. */
        const val SYNC_WAIT_MS = 60_000L

        /** How long "Synced just now" stays. */
        const val SYNC_DONE_SHOW_MS = 4_000L

        /**
         * The Doze rule (D12): ask for the exemption only when the socket would have to
         * survive Doze, i.e. on battery and not exempt. On the charger the phone does
         * not doze; an exempt app keeps its network.
         */
        fun needsExemptionPrompt(charging: Boolean, exempt: Boolean): Boolean = !charging && !exempt

        fun defaultSim(info: PhoneInfo?): DefaultSim {
            if (info == null) return DefaultSim.NoPermission
            return when (val sims = HomeLogic.sims(info)) {
                SimsInfo.NoPermission, SimsInfo.Loading -> DefaultSim.NoPermission
                is SimsInfo.Known -> sims.sims.firstOrNull { it.isDefault }?.let { DefaultSim.Card(it) }
                    ?: sims.sims.singleOrNull()?.let { DefaultSim.Card(it) }
                    ?: DefaultSim.None
            }
        }
    }
}
