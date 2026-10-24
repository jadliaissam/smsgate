package com.jassam.smsgate

import android.content.Context
import android.os.Build
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
    fun deviceApi(baseUrl: String): DeviceApi =
        DeviceApi(baseUrl, { credentialStore.deviceToken() }, httpClient, serverClock)

    /** A client for the paired server, or null when unpaired. */
    suspend fun pairedApi(): DeviceApi? = credentialStore.serverUrl()?.let(::deviceApi)
}

val Context.appGraph: AppGraph get() = (applicationContext as App).graph
