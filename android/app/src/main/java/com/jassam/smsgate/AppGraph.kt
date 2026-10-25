package com.jassam.smsgate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.work.WorkManager
import com.jassam.smsgate.api.DeviceApi
import com.jassam.smsgate.api.DeviceHttp
import com.jassam.smsgate.api.ServerClock
import com.jassam.smsgate.api.ServerUrl
import com.jassam.smsgate.auth.CredentialStore
import com.jassam.smsgate.auth.KeystoreTokenCipher
import com.jassam.smsgate.journal.JournalDao
import com.jassam.smsgate.journal.JournalDb
import com.jassam.smsgate.push.PushHandler
import com.jassam.smsgate.realtime.AndroidServiceControl
import com.jassam.smsgate.realtime.AndroidSocketLoop
import com.jassam.smsgate.realtime.ConnectionPrefs
import com.jassam.smsgate.realtime.ConnectionState
import com.jassam.smsgate.realtime.Connectivity
import com.jassam.smsgate.realtime.ConnectivityService
import com.jassam.smsgate.realtime.PowerState
import com.jassam.smsgate.realtime.ReconnectBackoff
import com.jassam.smsgate.realtime.ServiceControl
import com.jassam.smsgate.realtime.SocketClient
import com.jassam.smsgate.realtime.launchRevocation
import com.jassam.smsgate.sim.SimInventory
import com.jassam.smsgate.sms.PlatformSmsTransport
import com.jassam.smsgate.sms.SmsIntents
import com.jassam.smsgate.sms.SmsResultHandler
import com.jassam.smsgate.sms.SmsSender
import com.jassam.smsgate.sms.StatusReportQueue
import com.jassam.smsgate.ui.home.HomeDeps
import com.jassam.smsgate.ui.home.PhoneInfo
import com.jassam.smsgate.ui.home.PushStatus
import com.jassam.smsgate.ui.log.LogViewModel
import com.jassam.smsgate.ui.onboarding.Oem
import com.jassam.smsgate.ui.onboarding.OemGuidance
import com.jassam.smsgate.ui.onboarding.OnboardingPrefs
import com.jassam.smsgate.ui.onboarding.Permissions
import com.jassam.smsgate.ui.pairing.Pairer
import com.jassam.smsgate.ui.pairing.PlayServices
import com.jassam.smsgate.ui.pairing.PostPairingHook
import com.jassam.smsgate.ui.pairing.ServerHost
import com.jassam.smsgate.ui.settings.AppSettings
import com.jassam.smsgate.ui.settings.SettingsDeps
import com.jassam.smsgate.work.CrashRecovery
import com.jassam.smsgate.work.DataStoreSyncStatus
import com.jassam.smsgate.work.DeviceHeartbeatSource
import com.jassam.smsgate.work.PrefsSentFcmTokens
import com.jassam.smsgate.work.Revocation
import com.jassam.smsgate.work.Scheduler
import com.jassam.smsgate.work.SyncStatus
import com.jassam.smsgate.work.WorkDeps
import com.jassam.smsgate.work.onPaired
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The app's object graph without a DI framework (D10): lazy singletons, so nothing
 * is built at cold start until first use. Reach it with [Context.appGraph].
 */
class AppGraph(context: Context) {
    private val app = context.applicationContext

    val userAgent: String by lazy {
        DeviceHttp.userAgent(BuildConfig.VERSION_NAME, Build.VERSION.RELEASE ?: "?", Build.MODEL ?: "?")
    }

    /** The single OkHttpClient (one connection pool) for the device API and WebSocket. */
    val httpClient: OkHttpClient by lazy { DeviceHttp.client(userAgent) }

    /** Server-synchronised clock, fed by every response carrying `server_time`. */
    val serverClock: ServerClock by lazy { ServerClock() }

    val credentialStore: CredentialStore by lazy {
        CredentialStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("credentials") },
            ),
            KeystoreTokenCipher(),
        )
    }

    /** Normalises a user/QR server URL; plain HTTP to local hosts only in debug builds. */
    fun normalizeServerUrl(raw: String): String? = ServerUrl.normalize(raw, allowLocalHttp = BuildConfig.DEBUG)

    /**
     * A client for [baseUrl] (already normalised). Use this for `register` during
     * pairing; the token comes from the credential store.
     */
    fun deviceApi(baseUrl: String): DeviceApi = deviceApi(baseUrl) { credentialStore.deviceToken() }

    /** A client for [baseUrl] with an explicit token source (pairing: register, orphan cleanup). */
    fun deviceApi(baseUrl: String, token: suspend () -> String?): DeviceApi =
        DeviceApi(baseUrl, token, httpClient, serverClock)

    /** A client for the paired server, or null when unpaired. */
    suspend fun pairedApi(): DeviceApi? = credentialStore.serverUrl()?.let(::deviceApi)

    /** Active SIMs for register/heartbeat (empty without READ_PHONE_STATE). */
    val simInventory: SimInventory by lazy { SimInventory(app) }

    /**
     * The message journal. Built on first use; Room opens the file on the first
     * (suspend, off-main-thread) query. Main-thread queries are not allowed.
     */
    val journalDb: JournalDb by lazy { JournalDb.build(app) }

    val journalDao: JournalDao by lazy { journalDb.journalDao() }

    /** Background jobs on WorkManager (initialised on first use through App's Configuration.Provider). */
    val scheduler: Scheduler by lazy { Scheduler { WorkManager.getInstance(app) } }

    /**
     * Status reports decided by the journal (sent / delivered / failed): a unique
     * ReportStatusWorker per (id, status), retried with backoff on a network.
     */
    val statusReportQueue: StatusReportQueue get() = scheduler

    /**
     * The single 401 path: wipe credentials (if still the token used), cancel all work,
     * stop the socket service, abandon CLAIMED rows.
     */
    val revocation: Revocation by lazy {
        Revocation(
            credentials = credentialStore,
            cancelAllWork = scheduler::cancelAll,
            abandonClaimed = { CrashRecovery.abandonClaimed(journalDao, System.currentTimeMillis()) },
            log = ::warn,
            stopRealtime = serviceControl::stop,
        )
    }

    /**
     * Process-wide scope for short fire-and-forget work started from callbacks (socket
     * revocation, re-evaluation after the service stopped). Its handler logs anything
     * uncaught instead of crashing the process.
     */
    private val appScope: CoroutineScope by lazy {
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> warn("background task failed: ${e.javaClass.simpleName}") },
        )
    }

    private val serviceControl: ServiceControl by lazy { AndroidServiceControl(app) }

    /** The "Always connected" setting (own small DataStore). */
    val connectionPrefs: ConnectionPrefs by lazy {
        ConnectionPrefs(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("connection") },
            ),
        )
    }

    /**
     * The always-connected mode (D5, Task A7): setting, live state, policy evaluation.
     * A8's settings toggle calls [Connectivity.setAlwaysConnected] and shows
     * [Connectivity.state].
     */
    val connectivity: Connectivity by lazy {
        Connectivity(
            prefs = connectionPrefs,
            isPaired = { credentialStore.load() != null },
            isCharging = { PowerState.isPluggedIn(app) },
            service = serviceControl,
            connectWhenCharging = scheduler::connectWhenCharging,
            log = ::warn,
        )
    }

    /** What [ConnectivityService] needs; one [SocketClient] (own thread and timers) per start. */
    fun connectivityServiceDeps(): ConnectivityService.Deps = ConnectivityService.Deps(
        pairing = credentialStore.state,
        alwaysConnected = connectionPrefs.alwaysConnected,
        credentials = credentialStore::load,
        isCharging = { PowerState.isPluggedIn(app) },
        newClient = { credentials, onState ->
            SocketClient(
                credentials = credentials,
                httpClient = httpClient,
                loop = AndroidSocketLoop(app),
                backoff = ReconnectBackoff(Random.Default),
                queue = scheduler,
                clock = serverClock,
                heartbeat = heartbeatSource,
                onRevoked = { token -> appScope.launchRevocation(token, { revocation.onUnauthorized(it) }, ::warn) },
                onState = onState,
                log = ::warn,
            )
        },
        onState = connectivity::report,
        onRunning = { running ->
            connectivity.serviceRunning = running
            if (!running) connectivity.report(ConnectionState.OFF)
        },
        onDestroyed = { reevaluate -> appScope.launch { connectivity.onServiceDestroyed(reevaluate) } },
        onUnpluggedStop = scheduler::connectWhenCharging,
        log = ::warn,
    )

    /** Battery, charging, signal, SIMs, app version for heartbeats (HTTP and socket). */
    val heartbeatSource: DeviceHeartbeatSource by lazy { DeviceHeartbeatSource(app, simInventory, BuildConfig.VERSION_NAME) }

    /** Hands claimed messages to SmsManager (at most once per journal row). */
    val smsSender: SmsSender by lazy {
        SmsSender(
            dao = journalDao,
            transport = PlatformSmsTransport(app),
            sims = simInventory::activeSims,
            phoneStateGranted = {
                app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            },
            defaultSmsSubscriptionId = PlatformSmsTransport::defaultSmsSubscriptionId,
            intents = { ref -> SmsIntents.pendingIntent(app, ref) },
            reports = statusReportQueue,
            now = System::currentTimeMillis,
            serverNow = { serverClock.serverNow().toEpochMilliseconds() },
            log = { Log.w("SmsGate", it) },
        )
    }

    /** Applies per-part sent/delivery results (SmsResultReceiver). */
    val smsResultHandler: SmsResultHandler by lazy {
        SmsResultHandler(journalDao, statusReportQueue, System::currentTimeMillis)
    }

    /** Dependencies of the workers (see work/WorkDeps.kt, App's WorkerFactory). */
    val workDeps: WorkDeps by lazy {
        WorkDeps(
            credentials = credentialStore,
            api = { c -> deviceApi(c.serverUrl) { c.deviceToken } },
            journal = journalDao,
            sender = smsSender,
            queue = scheduler,
            revocation = revocation,
            heartbeat = heartbeatSource,
            fcmToken = PlayServices::fcmToken,
            sentTokens = sentFcmTokens,
            clockSynced = { serverClock.isSynced },
            now = System::currentTimeMillis,
            log = ::warn,
            evaluateConnection = { connectivity.evaluate() },
            syncStatus = syncStatus,
        )
    }

    /** Which FCM token the server last accepted (sync re-sends; Home shows "push OK"). */
    val sentFcmTokens: PrefsSentFcmTokens by lazy { PrefsSentFcmTokens(app) }

    /** Last successful sync (written by SyncWorker, shown on Home). */
    val syncStatus: SyncStatus by lazy {
        DataStoreSyncStatus(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("sync_status") },
            ),
        )
    }

    /** Theme override, local device label, cached account details (Task A8). */
    val appSettings: AppSettings by lazy {
        AppSettings(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("settings") },
            ),
        )
    }

    /** SIMs and the default SMS SIM (binder calls, off the main thread). */
    suspend fun phoneInfo(): PhoneInfo = withContext(Dispatchers.IO) {
        val default = try {
            PlatformSmsTransport.defaultSmsSubscriptionId().takeIf { it >= 0 }
        } catch (_: RuntimeException) {
            null
        }
        PhoneInfo(simInventory.activeSimsOrNull(), default)
    }

    /**
     * Push readiness for Home: no Play services (D11); or whether the server accepted
     * this phone's current FCM token. Reading the token can take a few seconds.
     */
    suspend fun pushStatus(): PushStatus = withContext(Dispatchers.IO) {
        if (!PlayServices.available(app)) return@withContext PushStatus.NoPlayServices
        val credentials = credentialStore.load() ?: return@withContext PushStatus.NotRegistered
        val token = PlayServices.fcmToken() ?: return@withContext PushStatus.NotRegistered
        if (sentFcmTokens.accepted(credentials.deviceId, token)) PushStatus.Ok else PushStatus.NotRegistered
    }

    /** What the home screen needs for the paired [serverUrl]. */
    fun homeDeps(serverUrl: String): HomeDeps = HomeDeps(
        serverHost = ServerHost.display(serverUrl),
        credentials = credentialStore::load,
        me = { c -> deviceApi(c.serverUrl) { c.deviceToken }.me() },
        revoke = { token -> revocation.onUnauthorized(token) },
        meCache = appSettings,
        lastSync = syncStatus.lastSuccess,
        connection = connectivity.state,
        alwaysConnected = connectivity.alwaysConnected,
        deviceLabel = appSettings.deviceLabel,
        counts = { w -> journalDao.observeCounts(w.startMs, w.endMs) },
        now = System::currentTimeMillis,
        zone = TimeZone::getDefault,
        phone = ::phoneInfo,
        push = ::pushStatus,
        log = ::warn,
    )

    /** The log list and detail sheet over the journal. */
    fun logViewModel(): LogViewModel = LogViewModel(journalDao::observeRecent, journalDao::observe, ::warn)

    /** What the settings screen needs. */
    fun settingsDeps(): SettingsDeps = SettingsDeps(
        credentials = credentialStore::load,
        unregister = { c -> deviceApi(c.serverUrl) { c.deviceToken }.unregister() },
        revoke = { token -> revocation.onUnauthorized(token) },
        alwaysConnected = connectivity.alwaysConnected,
        setAlwaysConnected = connectivity::setAlwaysConnected,
        connection = connectivity.state,
        isCharging = { PowerState.isPluggedIn(app) },
        batteryExempt = { Permissions.batteryExempt(app) },
        prefs = appSettings,
        phone = ::phoneInfo,
        syncNow = {
            revocation.exclusive {
                val paired = credentialStore.load() != null
                if (paired) scheduler.syncNow()
                paired
            }
        },
        lastSync = syncStatus.lastSuccess,
        now = System::currentTimeMillis,
        appVersion = BuildConfig.VERSION_NAME,
        forgetAccount = appSettings::clear,
        log = ::warn,
    )

    /** FCM push and token handling (SmsGateMessagingService). */
    val pushHandler: PushHandler by lazy { PushHandler(scheduler, { credentialStore.load() != null }, ::warn) }

    /** Onboarding flags (checklist done, permissions asked, OEM step done). */
    val onboardingPrefs: OnboardingPrefs by lazy { OnboardingPrefs(app) }

    /** The OEM battery guidance step for this phone (D12), or null on stock-like phones. */
    val oem: Oem? by lazy { OemGuidance.detect(Build.MANUFACTURER, Build.BRAND) }

    /**
     * Runs once credentials are stored after pairing (bounded by Pairer): periodic +
     * immediate sync, serialized with revocation, then the always-connected policy (the
     * app is in the foreground, so the service may start). If the timeout cuts it short,
     * the next sync or app start schedules the periodic job again (KEEP).
     */
    val postPairingHook: PostPairingHook = PostPairingHook {
        revocation.exclusive { scheduler.onPaired() }
        connectivity.evaluate()
    }

    /**
     * App start (MainActivity, off the main thread so WorkManager's initialisation is
     * not on the cold-start path): when paired, make sure the periodic sync exists
     * (KEEP), run one sync now and apply the always-connected policy (in the foreground,
     * so the service may start). Never throws.
     */
    suspend fun onAppStart() {
        try {
            if (credentialStore.load() == null) return
            revocation.exclusive {
                scheduler.schedulePeriodicSync()
                scheduler.syncNow()
            }
            connectivity.evaluate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("app start scheduling failed: ${e.javaClass.simpleName}")
        }
    }

    private fun warn(message: String) {
        Log.w("SmsGate", message)
    }

    /** Registers this phone and stores its credentials (pairing screen). */
    fun pairer(): Pairer = Pairer(
        normalizeServerUrl = ::normalizeServerUrl,
        apiFactory = { url, token -> deviceApi(url, token) },
        credentials = credentialStore,
        sims = simInventory::activeSims,
        fcmToken = PlayServices::fcmToken,
        appVersion = BuildConfig.VERSION_NAME,
        postPairing = postPairingHook,
        warn = ::warn,
    )
}

val Context.appGraph: AppGraph get() = (applicationContext as App).graph
