package com.jassam.smsgate.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jassam.smsgate.api.ApiResult
import com.jassam.smsgate.api.DeviceMe
import com.jassam.smsgate.auth.Credentials
import com.jassam.smsgate.journal.DayCounts
import com.jassam.smsgate.realtime.ConnectionState
import com.jassam.smsgate.ui.onboarding.OnboardingSnapshot
import com.jassam.smsgate.work.isRevocation
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the home screen reads (built from AppGraph; fakes in tests). */
class HomeDeps(
    /** Shown as the server host (ServerHost.display). */
    val serverHost: String,
    val credentials: suspend () -> Credentials?,
    val me: suspend (Credentials) -> ApiResult<DeviceMe>,
    /** The single 401 path (Revocation.onUnauthorized) with the token the call used. */
    val revoke: suspend (String) -> Unit,
    val meCache: MeCache,
    val lastSync: Flow<Long?>,
    val connection: Flow<ConnectionState>,
    val alwaysConnected: Flow<Boolean>,
    val deviceLabel: Flow<String?>,
    /** Journal counts for a window (JournalDao.observeCounts). */
    val counts: (DayWindow) -> Flow<DayCounts>,
    val now: () -> Long,
    val zone: () -> TimeZone,
    /** SIMs + default SMS SIM; binder calls, so implementations switch off the main thread. */
    val phone: suspend () -> PhoneInfo,
    /** Push readiness (Play services, FCM token known to the server); may take a few seconds. */
    val push: suspend () -> PushStatus,
    val log: (String) -> Unit = {},
)

/**
 * Home: account (cached, refreshed on demand, never blocking), connection and sync
 * state, SIMs, today's counts and permission warnings. Reactive parts come from flows;
 * [refresh] and [onPermissions] update the rest. Nothing here touches the main thread
 * with I/O: every dependency suspends or is a flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val deps: HomeDeps) : ViewModel() {
    private data class Local(
        val account: AccountInfo? = null,
        val accountStatus: AccountStatus = AccountStatus.Loading,
        val push: PushStatus = PushStatus.Checking,
        val sims: SimsInfo = SimsInfo.Loading,
        val warnings: List<PermissionWarning> = emptyList(),
    )

    private val local = MutableStateFlow(Local())
    private var accountJob: Job? = null
    private var deviceJob: Job? = null
    private var pushJob: Job? = null
    private var lastAccountFetch = 0L

    private data class Live(val lastSync: Long?, val connection: ConnectionState, val always: Boolean, val label: String?)

    private val live: Flow<Live> = combine(
        deps.lastSync.safe(null),
        deps.connection.safe(ConnectionState.OFF),
        deps.alwaysConnected.safe(false),
        deps.deviceLabel.safe(null),
        ::Live,
    )

    private val today: Flow<DayCounts> = dayWindows(deps.now, deps.zone)
        .flatMapLatest { deps.counts(it).safe(DayCounts.ZERO) }

    val state: StateFlow<HomeUiState> = combine(local, live, today) { l, v, counts ->
        HomeUiState(
            serverHost = deps.serverHost,
            deviceLabel = v.label,
            account = l.account,
            accountStatus = l.accountStatus,
            lastSyncMs = v.lastSync,
            push = l.push,
            connection = v.connection,
            alwaysConnected = v.always,
            sims = l.sims,
            today = counts,
            warnings = l.warnings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState(serverHost = deps.serverHost))

    init {
        refreshAccount()
        refreshDevice()
        // A sync may have sent the FCM token: re-check push readiness after each one.
        viewModelScope.launch {
            deps.lastSync.catch { }.distinctUntilChanged().drop(1).collect { refreshPush(restart = true) }
        }
    }

    /** Screen resumed: re-read SIMs and push; refetch the account if it is stale or failed. */
    fun onResume() {
        refreshDevice()
        val stale = deps.now() - lastAccountFetch > ACCOUNT_MAX_AGE_MS
        if (stale || local.value.accountStatus != AccountStatus.Fresh) refreshAccount()
    }

    /** The user asked to retry (or pulled to refresh). */
    fun refresh() {
        refreshAccount()
        refreshDevice()
    }

    /** Permission state read by the screen on resume (it needs the Activity). */
    fun onPermissions(snapshot: OnboardingSnapshot, notificationsEnabled: Boolean) {
        local.update { it.copy(warnings = HomeLogic.warnings(snapshot, notificationsEnabled)) }
    }

    private fun refreshAccount() {
        if (accountJob?.isActive == true) return
        accountJob = viewModelScope.launch {
            val credentials = try {
                deps.credentials()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.log("home: credentials unreadable: ${e.javaClass.simpleName}")
                null
            } ?: return@launch // unpaired: AppRoot is leaving this screen
            if (local.value.account == null) {
                val cached = try {
                    deps.meCache.get(credentials.deviceId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (cached != null) local.update { if (it.account == null) it.copy(account = cached) else it }
            }
            local.update { it.copy(accountStatus = AccountStatus.Loading) }
            val result = try {
                deps.me(credentials)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.log("home: me failed: ${e.javaClass.simpleName}")
                null
            }
            lastAccountFetch = deps.now()
            when {
                result is ApiResult.Ok -> {
                    val info = AccountInfo(result.value.name, result.value.accountEmail)
                    local.update { it.copy(account = info, accountStatus = AccountStatus.Fresh) }
                    try {
                        deps.meCache.put(credentials.deviceId, info)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        deps.log("home: account cache not saved: ${e.javaClass.simpleName}")
                    }
                }
                result is ApiResult.NetworkError -> local.update { it.copy(accountStatus = AccountStatus.Offline) }
                result != null && result.isRevocation -> {
                    local.update { it.copy(accountStatus = AccountStatus.Failed) }
                    deps.revoke(credentials.deviceToken)
                }
                else -> local.update { it.copy(accountStatus = AccountStatus.Failed) }
            }
        }
    }

    private fun refreshDevice() {
        if (deviceJob?.isActive == true) return
        deviceJob = viewModelScope.launch {
            val info = guarded("phone info") { deps.phone() }
            local.update { it.copy(sims = info?.let(HomeLogic::sims) ?: SimsInfo.NoPermission) }
        }
        refreshPush()
    }

    /** [restart]: a check already running may predate what changed; start over. */
    private fun refreshPush(restart: Boolean = false) {
        if (pushJob?.isActive == true) {
            if (!restart) return
            pushJob?.cancel()
        }
        pushJob = viewModelScope.launch {
            val push = guarded("push status") { deps.push() } ?: PushStatus.NotRegistered
            local.update { it.copy(push = push) }
        }
    }

    private suspend fun <T> guarded(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        deps.log("home: $what failed: ${e.javaClass.simpleName}")
        null
    }

    private fun <T> Flow<T>.safe(fallback: T): Flow<T> = catch { e ->
        deps.log("home: source failed: ${e.javaClass.simpleName}")
        emit(fallback)
    }

    companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** On resume, the account is fetched again when older than this. */
        const val ACCOUNT_MAX_AGE_MS = 10L * 60 * 1000
    }
}
