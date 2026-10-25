package com.jassam.smsgate.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jassam.smsgate.ui.home.AccountInfo
import com.jassam.smsgate.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun open(scope: CoroutineScope) = AppSettings(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "settings.preferences_pb") }),
    )

    private inline fun <T> withSettings(block: (AppSettings) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            return block(open(scope))
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    private suspend fun Job.cancelAndJoin() {
        cancel()
        join()
    }

    @Test
    fun themeDefaultsToSystemAndPersistsAcrossInstances() {
        withSettings { s ->
            runBlocking {
                assertEquals(ThemeMode.System, s.themeMode.first())
                s.setThemeMode(ThemeMode.Dark)
                assertEquals(ThemeMode.Dark, s.themeMode.first())
            }
        }
        // A new instance on the same file (as after a restart) reads it back.
        withSettings { s -> runBlocking { assertEquals(ThemeMode.Dark, s.themeMode.first()) } }
    }

    @Test
    fun labelIsTrimmedAndBlankClearsIt() = withSettings { s ->
        runBlocking {
            assertNull(s.deviceLabel.first())
            s.setDeviceLabel("  Front desk  ")
            assertEquals("Front desk", s.deviceLabel.first())
            s.setDeviceLabel("   ")
            assertNull(s.deviceLabel.first())
        }
    }

    @Test
    fun accountCacheIsPerDevice() = withSettings { s ->
        runBlocking {
            assertNull(s.get("dev-1"))
            s.put("dev-1", AccountInfo("Pixel", "a@example.com"))
            assertEquals(AccountInfo("Pixel", "a@example.com"), s.get("dev-1"))
            assertNull(s.get("dev-2"))
            s.clear("dev-2") // another device's clear leaves it
            assertEquals(AccountInfo("Pixel", "a@example.com"), s.get("dev-1"))
            s.setDeviceLabel("Front desk")
            s.clear("dev-1")
            assertNull(s.get("dev-1"))
            assertEquals("Front desk", s.deviceLabel.first()) // the label stays
        }
    }
}
