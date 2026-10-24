package com.jassam.smsgate.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private val cipher = FakeTokenCipher()
    private lateinit var store: CredentialStore

    @Before
    fun setUp() {
        file = File(tmp.root, "credentials.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = CredentialStore(dataStore, cipher)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun emptyStoreIsUnpaired() = runBlocking {
        assertNull(store.load())
        assertNull(store.deviceToken())
        assertEquals(PairingState.Unpaired, store.state.first())
    }

    @Test
    fun saveLoadRoundTripAndTokenEncryptedAtRest() = runBlocking {
        store.save("https://sms.example.com", "dev-1", "dt_secret")
        assertEquals(Credentials("https://sms.example.com", "dev-1", "dt_secret"), store.load())
        assertEquals("https://sms.example.com", store.serverUrl())
        assertEquals("dev-1", store.deviceId())
        assertEquals("dt_secret", store.deviceToken())
        assertEquals(PairingState.Paired("https://sms.example.com", "dev-1"), store.state.first())
        val raw = dataStore.data.first()
        assertEquals("enc:terces_td", raw[stringPreferencesKey("device_token_enc")])
        assertFalse(file.readBytes().decodeToString().contains("dt_secret"))
        assertFalse(store.load().toString().contains("dt_secret"))
    }

    @Test
    fun tokenIsCachedAfterFirstDecrypt() = runBlocking {
        store.save("https://a", "d", "dt_1")
        val fresh = CredentialStore(dataStore, cipher)
        repeat(5) { assertEquals("dt_1", fresh.deviceToken()) }
        assertEquals(1, cipher.decrypts)
    }

    @Test
    fun survivesNewInstanceOnSameFile() = runBlocking {
        store.save("https://a", "d", "dt_1")
        scope.cancel()
        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val ds2 = PreferenceDataStoreFactory.create(scope = scope2, produceFile = { file })
            assertEquals("dt_1", CredentialStore(ds2, FakeTokenCipher()).deviceToken())
        } finally {
            scope2.cancel()
        }
    }

    @Test
    fun lostKeyReadsAsUnpaired() = runBlocking {
        store.save("https://a", "d", "dt_1")
        cipher.keyLost = true
        val fresh = CredentialStore(dataStore, cipher) // new process: nothing cached
        assertNull(fresh.load())
        assertNull(fresh.deviceToken())
        assertEquals(PairingState.Unpaired, fresh.state.first())
        // Re-pairing overwrites the stale blob with a fresh key.
        fresh.save("https://a", "d2", "dt_2")
        assertEquals(Credentials("https://a", "d2", "dt_2"), fresh.load())
    }

    @Test
    fun corruptBlobReadsAsUnpaired() = runBlocking {
        store.save("https://a", "d", "dt_1")
        dataStore.edit { it[stringPreferencesKey("device_token_enc")] = "garbage" }
        assertNull(CredentialStore(dataStore, cipher).load())
    }

    @Test
    fun clearWipesEverythingAndDeletesKey() = runBlocking {
        store.save("https://a", "d", "dt_1")
        assertEquals("dt_1", store.deviceToken())
        store.clear()
        assertNull(store.load())
        assertNull(store.serverUrl())
        assertTrue(dataStore.data.first().asMap().isEmpty())
        assertEquals(1, cipher.resets)
        assertEquals(PairingState.Unpaired, store.state.first())
    }

    @Test
    fun saveFailureStoresNothing() = runBlocking {
        val failing = object : TokenCipher by cipher {
            override fun encrypt(plaintext: String): String = throw java.security.GeneralSecurityException("keystore down")
        }
        val s = CredentialStore(dataStore, failing)
        val thrown = runCatching { s.save("https://a", "d", "dt_1") }.exceptionOrNull()
        assertTrue(thrown is java.security.GeneralSecurityException)
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }
}
