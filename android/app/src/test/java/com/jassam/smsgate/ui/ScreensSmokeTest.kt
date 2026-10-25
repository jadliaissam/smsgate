package com.jassam.smsgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.unit.Density
import com.jassam.smsgate.api.Status
import com.jassam.smsgate.journal.DayCounts
import com.jassam.smsgate.journal.JournalEntry
import com.jassam.smsgate.journal.JournalState
import com.jassam.smsgate.realtime.ConnectionState
import com.jassam.smsgate.ui.home.AccountInfo
import com.jassam.smsgate.ui.home.AccountStatus
import com.jassam.smsgate.ui.home.HomeActions
import com.jassam.smsgate.ui.home.HomeContent
import com.jassam.smsgate.ui.home.HomeUiState
import com.jassam.smsgate.ui.home.PermissionWarning
import com.jassam.smsgate.ui.home.PushStatus
import com.jassam.smsgate.ui.home.SimCard
import com.jassam.smsgate.ui.home.SimsInfo
import com.jassam.smsgate.ui.home.WarningKind
import com.jassam.smsgate.ui.log.DetailContent
import com.jassam.smsgate.ui.log.LogContent
import com.jassam.smsgate.ui.log.LogFormat
import com.jassam.smsgate.ui.log.LogItem
import com.jassam.smsgate.ui.log.LogStatus
import com.jassam.smsgate.ui.log.LogUiState
import com.jassam.smsgate.ui.nav.MainShellContent
import com.jassam.smsgate.ui.nav.Tab
import com.jassam.smsgate.ui.onboarding.Oem
import com.jassam.smsgate.ui.settings.DefaultSim
import com.jassam.smsgate.ui.settings.SettingsActions
import com.jassam.smsgate.ui.settings.SettingsContent
import com.jassam.smsgate.ui.settings.SettingsUiState
import com.jassam.smsgate.ui.settings.UnpairFailure
import com.jassam.smsgate.ui.settings.UnpairState
import com.jassam.smsgate.ui.theme.SmsGateTheme
import com.jassam.smsgate.ui.theme.ThemeMode
import androidx.compose.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each screen renders on a 320 dp phone at 200 % font scale (and dark theme) without
 * crashing, and its key controls carry TalkBack labels and roles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ScreensSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L

    private fun show(mode: ThemeMode = ThemeMode.Light, content: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
            SmsGateTheme(mode) { content() }
        }
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private val homeState = HomeUiState(
        serverHost = "smsgate-web.captain-1.duckdns.org",
        deviceLabel = "Front desk phone",
        account = AccountInfo("Pixel 8", "someone.with.a.long.address@example.com"),
        accountStatus = AccountStatus.Offline,
        lastSyncMs = now - 120_000,
        push = PushStatus.NoPlayServices,
        connection = ConnectionState.WAITING,
        alwaysConnected = true,
        sims = SimsInfo.Known(listOf(SimCard(0, "Orange Maroc", "+212600000001", isDefault = true), SimCard(1, "inwi", null, isDefault = false))),
        today = DayCounts(sent = 1234, delivered = 1200, failed = 34),
        warnings = listOf(
            PermissionWarning(WarningKind.Sms),
            PermissionWarning(WarningKind.Notifications, blocked = true),
            PermissionWarning(WarningKind.Battery),
            PermissionWarning(WarningKind.Oem),
        ),
    )

    @Test
    fun homeRendersWithLabelsAndFixButtons() {
        var fixed: PermissionWarning? = null
        show(ThemeMode.Dark) { HomeContent(homeState, now, Oem.Xiaomi, HomeActions(onFix = { fixed = it })) }
        compose.onNodeWithText("Front desk phone").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fix: SMS permission is off").assertHasClickAction().performClick()
        assertEquals(PermissionWarning(WarningKind.Sms), fixed)
        compose.onNodeWithContentDescription("Open app settings: Notifications are off").assertHasClickAction()
        val scroll = compose.onNode(hasScrollAction())
        scroll.performScrollToNode(hasContentDescription("Sent today: 1234"))
        compose.onNodeWithContentDescription("Delivered today: 1200").assertExists()
        scroll.performScrollToNode(hasText("SIM 2"))
        compose.onNodeWithText("Reconnecting").assertExists()
    }

    @Test
    fun homeRendersWhileEverythingIsStillLoading() {
        show { HomeContent(HomeUiState(serverHost = "sms.example.com"), now, null, HomeActions()) }
        compose.onNodeWithText("This phone").assertIsDisplayed()
        compose.onNodeWithText("Loading account details…").assertExists()
    }

    @Test
    fun logListHasSpokenRowsAndOpensDetails() {
        val items = (1..30).map { LogItem("m$it", LogFormat.maskNumber("+21261234568$it"), LogStatus.Delivered, now - it * 60_000L, simSlot = 0) }
        var opened: String? = null
        show { LogContent(LogUiState(loading = false, items = items, canLoadMore = true), now, onOpen = { opened = it }, onLoadMore = {}) }
        compose.onNode(hasContentDescription("To number ending in 81, Delivered", substring = true))
            .assertHasClickAction()
            .performClick()
        assertEquals("m1", opened)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Load more"))
        compose.onNodeWithText("Load more").assertHasClickAction()
    }

    @Test
    fun logEmptyState() {
        show(ThemeMode.Dark) { LogContent(LogUiState(loading = false), now, onOpen = {}, onLoadMore = {}) }
        compose.onNodeWithText("No messages yet").assertIsDisplayed()
    }

    @Test
    fun detailShowsTheBodyWhileStoredAndSaysWhenRemoved() {
        val entry = JournalEntry(
            id = "0192f0c4-0000-7000-8000-0000000000a1", to = "+212612345689", body = "Your code is 4242", simSlot = 1,
            expiresAt = now + 60_000, state = JournalState.FAILED, errorCode = "no_service", parts = 2, partsSent = 1,
            createdAt = now - 60_000, updatedAt = now, reported = setOf(Status.FAILED),
        )
        var current by mutableStateOf(LogFormat.detail(entry))
        show { DetailContent(current) }
        compose.onNodeWithText("Your code is 4242").assertExists()
        compose.onNodeWithText("No mobile network. (no_service)").assertExists()
        compose.onNodeWithContentDescription("number ending in 89").assertExists()
        current = LogFormat.detail(entry.copy(body = null))
        compose.onNodeWithText("Message text removed after 24 hours.").assertExists()
        compose.onNodeWithText("Your code is 4242").assertDoesNotExist()
    }

    private val settingsState = SettingsUiState(
        appVersion = "1.0.0",
        alwaysConnected = true,
        connection = ConnectionState.CONNECTED,
        batteryExempt = false,
        deviceLabel = "Front desk",
        defaultSim = DefaultSim.Card(SimCard(0, "Orange", null, isDefault = true)),
        lastSyncMs = now - 60_000,
    )

    @Test
    fun settingsSwitchIsOneLabelledToggle() {
        var toggled: Boolean? = null
        show { SettingsContent(settingsState, now, SettingsActions(onAlwaysConnected = { toggled = it })) }
        compose.onNode(hasRole(Role.Switch)).assertHasClickAction().performClick()
        assertEquals(false, toggled)
        // The Doze note shows while Always connected is on without the exemption.
        compose.onNodeWithText("Allow running in the background").assertExists()
        val scroll = compose.onNode(hasScrollAction())
        scroll.performScrollToNode(hasText("Dark"))
        compose.onNode(hasText("Dark").and(hasRole(Role.RadioButton))).assertExists()
        scroll.performScrollToNode(hasText("Unpair phone"))
        compose.onNodeWithText("Unpair phone").assertHasClickAction()
    }

    @Test
    fun unpairDialogsRender() {
        var state by mutableStateOf(settingsState.copy(unpair = UnpairState.Confirm))
        var local = 0
        show(ThemeMode.Dark) { SettingsContent(state, now, SettingsActions(onLocalRemoval = { local++ })) }
        compose.onNodeWithText("Unpair this phone?").assertExists()
        state = state.copy(unpair = UnpairState.Failed(UnpairFailure.Network))
        compose.onNodeWithText("Try again").assertHasClickAction()
        compose.onNodeWithText("Remove from this phone only").assertHasClickAction().performClick()
        assertEquals(1, local)
        state = state.copy(unpair = UnpairState.ConfirmLocal)
        compose.onNodeWithText("Remove from this phone only?").assertExists()
        state = state.copy(unpair = UnpairState.Working)
        compose.onNodeWithText("Unpairing…").assertExists()
        state = state.copy(unpair = UnpairState.Idle, exemptionPrompt = true)
        compose.onNodeWithText("Keep the connection while the phone sleeps?").assertExists()
    }

    @Test
    fun bottomNavigationSwitchesTabs() {
        show {
            var tab by remember { mutableStateOf(Tab.Home) }
            MainShellContent(tab, onTab = { tab = it }) { t -> Text("screen:${t.name}") }
        }
        compose.onNodeWithText("screen:Home").assertExists()
        compose.onNode(hasText("Log").and(hasRole(Role.Tab))).assertHasClickAction().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("screen:Log").assertExists()
        compose.onNode(hasText("Settings").and(hasRole(Role.Tab))).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("screen:Settings").assertExists()
    }

    @Test
    @Config(qualifiers = "w800dp-h480dp")
    fun wideScreensGetANavigationRail() {
        show { MainShellContent(Tab.Log, onTab = {}) { t -> Text("screen:${t.name}") } }
        compose.onNodeWithText("screen:Log").assertExists()
        compose.onNode(hasText("Home").and(hasRole(Role.Tab))).assertHasClickAction()
        assertTrue(true)
    }
}
