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
import com.jassam.smsgate.api.DeviceApi
import com.jassam.smsgate.api.DeviceHttp
import com.jassam.smsgate.api.ServerClock
import com.jassam.smsgate.api.ServerUrl
import com.jassam.smsgate.auth.CredentialStore
import com.jassam.smsgate.auth.KeystoreTokenCipher
import com.jassam.smsgate.journal.JournalDao
import com.jassam.smsgate.journal.JournalDb
import com.jassam.smsgate.sim.SimInventory
import com.jassam.smsgate.sms.PlatformSmsTransport
import com.jassam.smsgate.sms.SmsIntents
import com.jassam.smsgate.sms.SmsResultHandler
import com.jassam.smsgate.sms.SmsSender
import com.jassam.smsgate.sms.StatusReportQueue
import com.jassam.smsgate.ui.onboarding.Oem
import com.jassam.smsgate.ui.onboarding.OemGuidance
import com.jassam.smsgate.ui.onboarding.OnboardingPrefs
import com.jassam.smsgate.ui.pairing.Pairer
import com.jassam.smsgate.ui.pairing.PlayServices
import com.jassam.smsgate.ui.pairing.PostPairingHook
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

    /**
     * Status reports decided by the journal (sent / delivered / failed).
     * TODO(A6): replace with the WorkManager-backed queue (unique ReportStatusWorker per
     * (id, status), exponential backoff, network constraint).
     */
    val statusReportQueue: StatusReportQueue = StatusReportQueue { _, _, _, _ -> }

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

    /** Onboarding flags (checklist done, permissions asked, OEM step done). */
    val onboardingPrefs: OnboardingPrefs by lazy { OnboardingPrefs(app) }

    /** The OEM battery guidance step for this phone (D12), or null on stock-like phones. */
    val oem: Oem? by lazy { OemGuidance.detect(Build.MANUFACTURER, Build.BRAND) }

    /**
     * Runs once credentials are stored after pairing.
     * TODO(A6): schedule the periodic SyncWorker (heartbeat + pending catch-up) and
     * enqueue an immediate sync through work/Scheduler.kt once it exists.
     */
    val postPairingHook: PostPairingHook = PostPairingHook { }

    /** Registers this phone and stores its credentials (pairing screen). */
    fun pairer(): Pairer = Pairer(
        normalizeServerUrl = ::normalizeServerUrl,
        apiFactory = { url, token -> deviceApi(url, token) },
        credentials = credentialStore,
        sims = simInventory::activeSims,
        fcmToken = PlayServices::fcmToken,
        appVersion = BuildConfig.VERSION_NAME,
        postPairing = postPairingHook,
        warn = { Log.w("SmsGate", it) },
    )
}

val Context.appGraph: AppGraph get() = (applicationContext as App).graph
