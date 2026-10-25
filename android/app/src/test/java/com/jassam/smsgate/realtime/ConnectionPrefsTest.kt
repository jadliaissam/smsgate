package com.jassam.smsgate.realtime

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConnectionPrefsTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    private fun prefs() = ConnectionPrefs(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "connection.preferences_pb") }),
    )

    @Test
    fun alwaysConnectedDefaultsToOffAndPersists() = runBlocking {
        val prefs = prefs()
        assertFalse(prefs.alwaysConnected())
        assertFalse(prefs.alwaysConnected.first())
        prefs.setAlwaysConnected(true)
        assertTrue(prefs.alwaysConnected())
        assertTrue(prefs.alwaysConnected.first())
        prefs.setAlwaysConnected(false)
        assertFalse(prefs.alwaysConnected())
    }
}
