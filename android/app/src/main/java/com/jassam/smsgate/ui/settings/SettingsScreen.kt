package com.jassam.smsgate.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jassam.smsgate.AppGraph
import com.jassam.smsgate.R
import com.jassam.smsgate.auth.PairingState
import com.jassam.smsgate.ui.components.Banner
import com.jassam.smsgate.ui.components.CardDivider
import com.jassam.smsgate.ui.components.DangerButton
import com.jassam.smsgate.ui.components.DialogText
import com.jassam.smsgate.ui.components.GhostButton
import com.jassam.smsgate.ui.components.InfoRow
import com.jassam.smsgate.ui.components.PageHeader
import com.jassam.smsgate.ui.components.PrimaryButton
import com.jassam.smsgate.ui.components.ScreenColumn
import com.jassam.smsgate.ui.components.SecondaryButton
import com.jassam.smsgate.ui.components.SectionCard
import com.jassam.smsgate.ui.components.SgDialog
import com.jassam.smsgate.ui.components.SgTextField
import com.jassam.smsgate.ui.components.StatusChip
import com.jassam.smsgate.ui.format.rememberNow
import com.jassam.smsgate.ui.format.sinceText
import com.jassam.smsgate.ui.home.connectionLabel
import com.jassam.smsgate.ui.onboarding.Permissions
import com.jassam.smsgate.ui.theme.Palette
import com.jassam.smsgate.ui.theme.SmsGateTheme
import com.jassam.smsgate.ui.theme.ThemeMode
import com.jassam.smsgate.ui.theme.Tone

/** What the settings screen's controls do (wired to the view model and Android). */
class SettingsActions(
    val onAlwaysConnected: (Boolean) -> Unit = {},
    val onDismissToggleError: () -> Unit = {},
    val onRequestExemption: () -> Unit = {},
    val onDismissExemption: () -> Unit = {},
    val onSaveLabel: (String) -> Boolean = { true },
    val onDismissSaveError: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onOpenLicences: () -> Unit = {},
    val onUnpair: () -> Unit = {},
    val onConfirmUnpair: () -> Unit = {},
    val onLocalRemoval: () -> Unit = {},
    val onConfirmLocalRemoval: () -> Unit = {},
    val onCancelUnpair: () -> Unit = {},
)

@Composable
fun SettingsScreen(graph: AppGraph, paired: PairingState.Paired) {
    val vm = viewModel(key = "settings:${paired.deviceId}") { SettingsViewModel(graph.settingsDeps()) }
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = checkNotNull(LocalActivity.current) { "SettingsScreen needs an Activity" }
    var licences by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }
    if (licences) {
        BackHandler { licences = false }
        LicencesScreen(onBack = { licences = false })
        return
    }
    val actions = remember(vm, activity) {
        SettingsActions(
        onAlwaysConnected = vm::setAlwaysConnected,
        onDismissToggleError = vm::dismissToggleError,
        onRequestExemption = {
            vm.dismissExemptionPrompt()
            Permissions.requestBatteryExemption(activity)
        },
        onDismissExemption = vm::dismissExemptionPrompt,
        onSaveLabel = vm::saveDeviceLabel,
        onDismissSaveError = vm::dismissSaveError,
        onSyncNow = vm::syncNow,
        onTheme = vm::setTheme,
        onOpenLicences = { licences = true },
        onUnpair = vm::requestUnpair,
        onConfirmUnpair = vm::confirmUnpair,
        onLocalRemoval = vm::requestLocalRemoval,
        onConfirmLocalRemoval = vm::confirmLocalRemoval,
        onCancelUnpair = vm::cancelUnpair,
        )
    }
    SettingsContent(state, rememberNow(), actions)
}

@Composable
fun SettingsContent(state: SettingsUiState, nowMs: Long, actions: SettingsActions) {
    ScreenColumn {
        PageHeader(stringResource(R.string.settings_title))
        ConnectionSection(state, actions)
        PhoneSection(state, nowMs, actions)
        AppearanceSection(state.theme, actions.onTheme)
        AboutSection(state.appVersion, actions.onOpenLicences)
        UnpairSection(actions.onUnpair)
    }
    UnpairDialogs(state.unpair, actions)
    if (state.exemptionPrompt) ExemptionDialog(actions)
}

@Composable
private fun ConnectionSection(state: SettingsUiState, actions: SettingsActions) {
    val colors = SmsGateTheme.colors
    SectionCard(stringResource(R.string.settings_connection_title)) {
        val title = stringResource(R.string.settings_always_connected)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(
                    value = state.alwaysConnected,
                    enabled = !state.toggleBusy,
                    role = Role.Switch,
                    onValueChange = actions.onAlwaysConnected,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.fg)
                Text(stringResource(R.string.settings_always_connected_note), style = MaterialTheme.typography.bodySmall, color = colors.fgMuted)
            }
            Switch(
                checked = state.alwaysConnected,
                onCheckedChange = null, // the whole row toggles (one 48 dp+ target, one TalkBack item)
                enabled = !state.toggleBusy,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Palette.Brand600,
                    checkedThumbColor = colors.fgOnBrand,
                    checkedBorderColor = Palette.Brand600,
                    uncheckedTrackColor = colors.surfaceSunken,
                    uncheckedThumbColor = colors.fgSubtle,
                    uncheckedBorderColor = colors.borderStrong,
                ),
            )
        }
        CardDivider()
        val (text, tone) = connectionLabel(state.connection)
        InfoRow(stringResource(R.string.settings_live_state), stringResource(R.string.settings_live_state_hint)) {
            StatusChip(text, tone, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.toggleFailed) {
            Banner(stringResource(R.string.settings_toggle_failed), Tone.Danger, Modifier.fillMaxWidth().padding(top = 8.dp), onDismiss = actions.onDismissToggleError)
        }
        if (state.dozeWarning) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Banner(stringResource(R.string.settings_doze_warning), Tone.Warning, Modifier.fillMaxWidth())
                GhostButton(stringResource(R.string.settings_allow_background), actions.onRequestExemption)
            }
        }
    }
}

@Composable
private fun PhoneSection(state: SettingsUiState, nowMs: Long, actions: SettingsActions) {
    val colors = SmsGateTheme.colors
    SectionCard(stringResource(R.string.settings_phone_title)) {
        var draft by rememberSaveable { mutableStateOf(state.deviceLabel.orEmpty()) }
        var edited by rememberSaveable { mutableStateOf(false) }
        // Follow the stored label until the user starts typing.
        LaunchedEffect(state.deviceLabel) { if (!edited) draft = state.deviceLabel.orEmpty() }
        var invalid by rememberSaveable { mutableStateOf(false) }
        val save = {
            invalid = !actions.onSaveLabel(draft)
            if (!invalid) edited = false
        }
        SgTextField(
            value = draft,
            onValueChange = {
                draft = it
                edited = true
                invalid = false
            },
            label = stringResource(R.string.settings_label),
            hint = stringResource(R.string.settings_label_hint),
            error = if (invalid) stringResource(R.string.field_name_length) else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (edited && draft.trim() != state.deviceLabel.orEmpty()) {
            SecondaryButton(stringResource(R.string.settings_label_save), { save() }, Modifier.padding(bottom = 4.dp))
        }
        if (state.saveFailed) {
            Banner(stringResource(R.string.settings_save_failed), Tone.Danger, Modifier.fillMaxWidth(), onDismiss = actions.onDismissSaveError)
        }
        CardDivider()
        InfoRow(stringResource(R.string.settings_default_sim), defaultSimText(state.defaultSim))
        CardDivider()
        val syncText = when {
            state.sync == SyncFeedback.Done -> stringResource(R.string.settings_sync_done)
            state.sync == SyncFeedback.Requested -> stringResource(R.string.settings_sync_requested)
            state.lastSyncMs != null -> stringResource(R.string.settings_sync_last, sinceText(state.lastSyncMs, nowMs))
            else -> stringResource(R.string.settings_sync_never)
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(stringResource(R.string.settings_sync_title), style = MaterialTheme.typography.labelMedium, color = colors.fgMuted)
                Text(syncText, style = MaterialTheme.typography.bodyMedium, color = colors.fg)
            }
        }
        SecondaryButton(stringResource(R.string.settings_sync_now), actions.onSyncNow, icon = R.drawable.ic_sync)
    }
}

@Composable
private fun defaultSimText(sim: DefaultSim): String = when (sim) {
    DefaultSim.Loading -> stringResource(R.string.home_sims_loading)
    DefaultSim.NoPermission -> stringResource(R.string.settings_default_sim_no_permission)
    DefaultSim.None -> stringResource(R.string.settings_default_sim_none)
    is DefaultSim.Card -> listOfNotNull(
        stringResource(R.string.sim_slot, sim.sim.slot + 1),
        sim.sim.carrier.ifBlank { null },
        sim.sim.number,
    ).joinToString(stringResource(R.string.list_separator))
}

@Composable
private fun AppearanceSection(theme: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    val colors = SmsGateTheme.colors
    SectionCard(stringResource(R.string.settings_theme_title)) {
        Column(Modifier.selectableGroup()) {
            for (mode in ThemeMode.entries) {
                val label = stringResource(
                    when (mode) {
                        ThemeMode.System -> R.string.settings_theme_system
                        ThemeMode.Light -> R.string.settings_theme_light
                        ThemeMode.Dark -> R.string.settings_theme_dark
                    },
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = mode == theme, role = Role.RadioButton, onClick = { onTheme(mode) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(
                        selected = mode == theme,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = Palette.Brand600, unselectedColor = colors.borderStrong),
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.fg)
                }
            }
        }
    }
}

@Composable
private fun AboutSection(version: String, onLicences: () -> Unit) {
    SectionCard(stringResource(R.string.settings_about_title)) {
        InfoRow(stringResource(R.string.settings_version), version)
        CardDivider()
        GhostButton(stringResource(R.string.settings_licences), onLicences)
    }
}

@Composable
private fun UnpairSection(onUnpair: () -> Unit) {
    val colors = SmsGateTheme.colors
    SectionCard(stringResource(R.string.settings_unpair_title)) {
        Text(stringResource(R.string.settings_unpair_body), style = MaterialTheme.typography.bodySmall, color = colors.fgMuted)
        FlowRow(Modifier.padding(top = 10.dp)) {
            DangerButton(stringResource(R.string.settings_unpair), onUnpair)
        }
    }
}

@Composable
private fun ExemptionDialog(actions: SettingsActions) {
    SgDialog(
        title = stringResource(R.string.exemption_title),
        onDismiss = actions.onDismissExemption,
        icon = R.drawable.ic_battery,
        body = { DialogText(stringResource(R.string.exemption_body)) },
        actions = {
            PrimaryButton(stringResource(R.string.exemption_allow), actions.onRequestExemption, Modifier.fillMaxWidth())
            GhostButton(stringResource(R.string.exemption_not_now), actions.onDismissExemption, Modifier.fillMaxWidth())
        },
    )
}

@Composable
private fun UnpairDialogs(unpair: UnpairState, actions: SettingsActions) {
    when (unpair) {
        UnpairState.Idle -> Unit
        UnpairState.Confirm -> SgDialog(
            title = stringResource(R.string.unpair_confirm_title),
            onDismiss = actions.onCancelUnpair,
            icon = R.drawable.ic_link,
            tone = Tone.Danger,
            body = { DialogText(stringResource(R.string.unpair_confirm_body)) },
            actions = {
                DangerButton(stringResource(R.string.settings_unpair), actions.onConfirmUnpair, Modifier.fillMaxWidth())
                GhostButton(stringResource(R.string.action_cancel), actions.onCancelUnpair, Modifier.fillMaxWidth())
            },
        )
        UnpairState.Working -> SgDialog(
            title = stringResource(R.string.unpair_working_title),
            onDismiss = {},
            icon = R.drawable.ic_link,
            tone = Tone.Danger,
            dismissible = false,
            body = { DialogText(stringResource(R.string.unpair_working_body)) },
            actions = {
                DangerButton(
                    stringResource(R.string.settings_unpair),
                    {},
                    Modifier.fillMaxWidth(),
                    loading = true,
                    loadingLabel = stringResource(R.string.unpair_working_title),
                )
            },
        )
        is UnpairState.Failed -> SgDialog(
            title = stringResource(R.string.unpair_failed_title),
            onDismiss = actions.onCancelUnpair,
            icon = R.drawable.ic_alert,
            tone = Tone.Warning,
            body = {
                DialogText(
                    stringResource(if (unpair.reason == UnpairFailure.Network) R.string.unpair_failed_network else R.string.unpair_failed_server),
                )
            },
            actions = {
                PrimaryButton(stringResource(R.string.action_retry), actions.onConfirmUnpair, Modifier.fillMaxWidth())
                SecondaryButton(stringResource(R.string.unpair_local), actions.onLocalRemoval, Modifier.fillMaxWidth())
                GhostButton(stringResource(R.string.action_cancel), actions.onCancelUnpair, Modifier.fillMaxWidth())
            },
        )
        UnpairState.ConfirmLocal -> SgDialog(
            title = stringResource(R.string.unpair_local_title),
            onDismiss = actions.onCancelUnpair,
            icon = R.drawable.ic_alert,
            tone = Tone.Warning,
            body = { DialogText(stringResource(R.string.unpair_local_body)) },
            actions = {
                DangerButton(stringResource(R.string.unpair_local_confirm), actions.onConfirmLocalRemoval, Modifier.fillMaxWidth())
                GhostButton(stringResource(R.string.action_cancel), actions.onCancelUnpair, Modifier.fillMaxWidth())
            },
        )
    }
}
