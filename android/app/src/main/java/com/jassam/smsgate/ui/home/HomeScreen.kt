package com.jassam.smsgate.ui.home

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jassam.smsgate.AppGraph
import com.jassam.smsgate.R
import com.jassam.smsgate.auth.PairingState
import com.jassam.smsgate.journal.DayCounts
import com.jassam.smsgate.realtime.ConnectionState
import com.jassam.smsgate.ui.Wordmark
import com.jassam.smsgate.ui.components.Banner
import com.jassam.smsgate.ui.components.CardDivider
import com.jassam.smsgate.ui.components.GhostButton
import com.jassam.smsgate.ui.components.IconTile
import com.jassam.smsgate.ui.components.InfoRow
import com.jassam.smsgate.ui.components.ScreenColumn
import com.jassam.smsgate.ui.components.SecondaryButton
import com.jassam.smsgate.ui.components.SectionCard
import com.jassam.smsgate.ui.components.SgCard
import com.jassam.smsgate.ui.components.StatusChip
import com.jassam.smsgate.ui.format.rememberNow
import com.jassam.smsgate.ui.format.sinceText
import com.jassam.smsgate.ui.onboarding.Oem
import com.jassam.smsgate.ui.onboarding.Permissions
import com.jassam.smsgate.ui.theme.Radii
import com.jassam.smsgate.ui.theme.SmsGateTheme
import com.jassam.smsgate.ui.theme.Tone
import com.jassam.smsgate.ui.theme.toneColors

/** What the home screen's buttons do (the screen wires them to Android; tests pass no-ops). */
class HomeActions(
    val onRetry: () -> Unit = {},
    val onFix: (PermissionWarning) -> Unit = {},
    val onOemDone: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

/**
 * Home for a paired phone. Permission state is read on every resume (it can change in
 * system settings); fixes reuse the onboarding helpers (request, or the app's settings
 * page once Android stopped asking, battery exemption, OEM screens).
 */
@Composable
fun HomeScreen(graph: AppGraph, paired: PairingState.Paired, onOpenSettings: () -> Unit) {
    val vm = viewModel(key = "home:${paired.deviceId}") { HomeViewModel(graph.homeDeps(paired.serverUrl)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = checkNotNull(LocalActivity.current) { "HomeScreen needs an Activity" }
    val prefs = graph.onboardingPrefs
    val oem = graph.oem
    val readPermissions = {
        vm.onPermissions(Permissions.snapshot(activity, prefs, oem), NotificationManagerCompat.from(activity).areNotificationsEnabled())
    }
    LifecycleResumeEffect(Unit) {
        readPermissions()
        vm.onResume()
        onPauseOrDispose { }
    }
    val afterGrant = {
        readPermissions()
        vm.refresh()
    }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { afterGrant() }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { afterGrant() }

    val actions = remember(vm, activity, oem) {
        HomeActions(
        onRetry = vm::refresh,
        onFix = { w ->
            when {
                w.kind == WarningKind.Battery -> Permissions.requestBatteryExemption(activity)
                w.kind == WarningKind.Oem -> oem?.let { Permissions.openOemSettings(activity, it) }
                w.blocked -> Permissions.openAppSettings(activity)
                w.kind == WarningKind.Sms -> {
                    prefs.markAsked(listOf(Permissions.SMS))
                    single.launch(Permissions.SMS)
                }
                w.kind == WarningKind.PhoneState -> {
                    prefs.markAsked(Permissions.phone)
                    multiple.launch(Permissions.phone.toTypedArray())
                }
                w.kind == WarningKind.Notifications -> {
                    prefs.markAsked(listOf(Permissions.NOTIFICATIONS))
                    single.launch(Permissions.NOTIFICATIONS)
                }
            }
        },
        onOemDone = {
            prefs.oemAcknowledged = true
            readPermissions()
        },
        onOpenSettings = onOpenSettings,
        )
    }
    HomeContent(state, rememberNow(), oem, actions)
}

@Composable
fun HomeContent(state: HomeUiState, nowMs: Long, oem: Oem?, actions: HomeActions) {
    val colors = SmsGateTheme.colors
    ScreenColumn {
        Wordmark()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val title = state.deviceLabel ?: state.account?.name ?: stringResource(R.string.home_title_fallback)
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.fg,
                modifier = Modifier.semantics { heading() },
            )
            val subtitle = when {
                state.account != null -> stringResource(R.string.home_paired_as, state.account.email)
                state.accountStatus == AccountStatus.Loading -> stringResource(R.string.home_account_loading)
                else -> stringResource(R.string.home_account_unknown)
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.fgMuted)
            if (state.deviceLabel != null && state.account != null && state.account.name != state.deviceLabel) {
                Text(
                    stringResource(R.string.home_dashboard_name, state.account.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgMuted,
                )
            }
        }

        when (state.accountStatus) {
            AccountStatus.Offline -> RetryBanner(stringResource(R.string.home_account_offline), actions.onRetry)
            AccountStatus.Failed -> RetryBanner(stringResource(R.string.home_account_failed), actions.onRetry)
            else -> Unit
        }

        if (!state.playServices) {
            Banner(
                stringResource(if (state.alwaysConnected) R.string.home_no_play_services_always else R.string.home_no_play_services),
                if (state.alwaysConnected) Tone.Brand else Tone.Warning,
                Modifier.fillMaxWidth(),
            )
        }

        if (state.warnings.isNotEmpty()) Warnings(state.warnings, oem, actions)

        ConnectionCard(state, nowMs, actions)
        TodayCard(state.today)
        val simFix = HomeLogic.simFix(state.warnings)
        SimsCard(state.sims, blocked = simFix.blocked, onFix = { actions.onFix(simFix) })
    }
}

@Composable
private fun RetryBanner(text: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Banner(text, Tone.Warning, Modifier.fillMaxWidth())
        GhostButton(stringResource(R.string.action_retry), onRetry)
    }
}

@Composable
private fun ConnectionCard(state: HomeUiState, nowMs: Long, actions: HomeActions) {
    SectionCard(stringResource(R.string.home_connection_title)) {
        InfoRow(stringResource(R.string.home_server), state.serverHost)
        CardDivider()
        InfoRow(
            stringResource(R.string.home_last_sync),
            state.lastSyncMs?.let { sinceText(it, nowMs) } ?: stringResource(R.string.home_last_sync_never),
        )
        CardDivider()
        val (pushText, pushTone) = pushLabel(state.push)
        InfoRow(stringResource(R.string.home_push), pushHint(state.push)) {
            StatusChip(pushText, pushTone)
        }
        CardDivider()
        val (liveText, liveTone) = connectionLabel(state.connection)
        InfoRow(stringResource(R.string.home_live), connectionHint(state)) {
            StatusChip(liveText, liveTone)
        }
        if (state.connection == ConnectionState.OFF && !state.alwaysConnected) {
            GhostButton(stringResource(R.string.home_open_settings), actions.onOpenSettings)
        }
    }
}

@Composable
fun pushLabel(push: PushStatus): Pair<String, Tone> = when (push) {
    PushStatus.Checking -> stringResource(R.string.push_checking) to Tone.Neutral
    PushStatus.Ok -> stringResource(R.string.push_ok) to Tone.Success
    PushStatus.NotRegistered -> stringResource(R.string.push_not_registered) to Tone.Warning
    PushStatus.NoPlayServices -> stringResource(R.string.push_unavailable) to Tone.Neutral
}

@Composable
private fun pushHint(push: PushStatus): String = when (push) {
    PushStatus.Checking -> stringResource(R.string.push_hint_checking)
    PushStatus.Ok -> stringResource(R.string.push_hint_ok)
    PushStatus.NotRegistered -> stringResource(R.string.push_hint_not_registered)
    PushStatus.NoPlayServices -> stringResource(R.string.push_hint_unavailable)
}

/** The socket state in plain words, with its chip tone (Home and Settings). */
@Composable
fun connectionLabel(state: ConnectionState): Pair<String, Tone> = when (state) {
    ConnectionState.OFF -> stringResource(R.string.connection_off) to Tone.Neutral
    ConnectionState.CONNECTING -> stringResource(R.string.connection_connecting) to Tone.Brand
    ConnectionState.CONNECTED -> stringResource(R.string.connection_connected) to Tone.Success
    ConnectionState.WAITING -> stringResource(R.string.connection_waiting) to Tone.Warning
}

@Composable
private fun connectionHint(state: HomeUiState): String = when (state.connection) {
    ConnectionState.CONNECTED -> stringResource(R.string.connection_hint_connected)
    ConnectionState.CONNECTING -> stringResource(R.string.connection_hint_connecting)
    ConnectionState.WAITING -> stringResource(R.string.connection_hint_waiting)
    ConnectionState.OFF -> when {
        state.alwaysConnected -> stringResource(R.string.connection_hint_off_always)
        state.playServices -> stringResource(R.string.connection_hint_off_push)
        else -> stringResource(R.string.connection_hint_off_sync)
    }
}

@Composable
private fun TodayCard(today: DayCounts) {
    SectionCard(stringResource(R.string.home_today_title)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3,
        ) {
            Stat(stringResource(R.string.home_today_sent), today.sent, Tone.Brand, Modifier.weight(1f))
            Stat(stringResource(R.string.home_today_delivered), today.delivered, Tone.Success, Modifier.weight(1f))
            Stat(stringResource(R.string.home_today_failed), today.failed, Tone.Danger, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, tone: Tone, modifier: Modifier) {
    val colors = SmsGateTheme.colors
    val t = toneColors(tone, colors, SmsGateTheme.isDark)
    val spoken = stringResource(R.string.home_stat_spoken, label, value)
    Column(
        modifier
            .widthIn(min = 88.dp)
            .clip(Radii.Control)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, Radii.Control)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).clip(Radii.Pill).background(t.dot))
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.fgMuted)
        }
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold), color = colors.fg)
    }
}

@Composable
private fun SimsCard(sims: SimsInfo, blocked: Boolean, onFix: () -> Unit) {
    val colors = SmsGateTheme.colors
    SectionCard(stringResource(R.string.home_sims_title)) {
        when (sims) {
            SimsInfo.Loading -> Text(stringResource(R.string.home_sims_loading), style = MaterialTheme.typography.bodyMedium, color = colors.fgMuted)
            SimsInfo.NoPermission -> {
                Text(stringResource(R.string.home_sims_no_permission), style = MaterialTheme.typography.bodyMedium, color = colors.fgMuted)
                val label = stringResource(if (blocked) R.string.action_open_app_settings else R.string.action_allow)
                SecondaryButton(label, onFix, Modifier.padding(top = 8.dp))
            }
            is SimsInfo.Known -> if (sims.sims.isEmpty()) {
                Text(stringResource(R.string.home_sims_none), style = MaterialTheme.typography.bodyMedium, color = colors.fgMuted)
            } else {
                sims.sims.forEachIndexed { i, sim ->
                    if (i > 0) CardDivider()
                    SimRow(sim)
                }
            }
        }
    }
}

@Composable
private fun SimRow(sim: SimCard) {
    val colors = SmsGateTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(R.drawable.ic_sim, if (sim.isDefault) Tone.Brand else Tone.Neutral, size = 36)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sim_slot, sim.slot + 1), style = MaterialTheme.typography.titleSmall, color = colors.fg)
                if (sim.isDefault) StatusChip(stringResource(R.string.sim_default), Tone.Brand)
            }
            Text(
                listOfNotNull(sim.carrier.ifBlank { null }, sim.number).joinToString(stringResource(R.string.list_separator))
                    .ifEmpty { stringResource(R.string.sim_unknown_carrier) },
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }
    }
}

@Composable
private fun Warnings(warnings: List<PermissionWarning>, oem: Oem?, actions: HomeActions) {
    val colors = SmsGateTheme.colors
    SgCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Text(
            stringResource(R.string.home_warnings_title),
            style = MaterialTheme.typography.titleSmall,
            color = colors.fg,
            modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
        )
        warnings.forEachIndexed { i, w ->
            if (i > 0) CardDivider()
            WarningRow(w, oem, actions)
        }
    }
}

private data class WarningCopy(val title: String, val body: String, @DrawableRes val icon: Int, val tone: Tone)

@Composable
private fun warningCopy(w: PermissionWarning, oem: Oem?): WarningCopy = when (w.kind) {
    WarningKind.Sms -> WarningCopy(stringResource(R.string.warning_sms_title), stringResource(R.string.warning_sms_body), R.drawable.ic_sms, Tone.Danger)
    WarningKind.PhoneState -> WarningCopy(stringResource(R.string.warning_phone_title), stringResource(R.string.warning_phone_body), R.drawable.ic_sim, Tone.Warning)
    WarningKind.Notifications -> WarningCopy(
        stringResource(R.string.warning_notifications_title),
        stringResource(R.string.warning_notifications_body),
        R.drawable.ic_notifications,
        Tone.Warning,
    )
    WarningKind.Battery -> WarningCopy(stringResource(R.string.warning_battery_title), stringResource(R.string.warning_battery_body), R.drawable.ic_battery, Tone.Warning)
    WarningKind.Oem -> {
        val label = oem?.let { stringResource(it.label) } ?: ""
        WarningCopy(
            stringResource(R.string.item_oem_title, label),
            oem?.let { stringResource(it.steps) } ?: "",
            R.drawable.ic_phone,
            Tone.Warning,
        )
    }
}

@Composable
private fun WarningRow(w: PermissionWarning, oem: Oem?, actions: HomeActions) {
    val colors = SmsGateTheme.colors
    val copy = warningCopy(w, oem)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(copy.icon, copy.tone, size = 36)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(copy.title, style = MaterialTheme.typography.titleSmall, color = colors.fg)
                Text(copy.body, style = MaterialTheme.typography.bodySmall, color = colors.fgMuted)
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 48.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val label = when {
                w.kind == WarningKind.Oem -> stringResource(R.string.action_open_settings)
                w.blocked -> stringResource(R.string.action_open_app_settings)
                else -> stringResource(R.string.action_fix)
            }
            val description = stringResource(R.string.warning_fix_description, label, copy.title)
            SecondaryButton(label, { actions.onFix(w) }, Modifier.semantics { contentDescription = description })
            if (w.kind == WarningKind.Oem) GhostButton(stringResource(R.string.action_mark_done), actions.onOemDone)
        }
    }
}
