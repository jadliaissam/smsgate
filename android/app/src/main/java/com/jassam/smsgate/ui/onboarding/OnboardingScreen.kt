package com.jassam.smsgate.ui.onboarding

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.jassam.smsgate.R
import com.jassam.smsgate.ui.Wordmark
import com.jassam.smsgate.ui.components.GhostButton
import com.jassam.smsgate.ui.components.IconTile
import com.jassam.smsgate.ui.components.PrimaryButton
import com.jassam.smsgate.ui.components.SecondaryButton
import com.jassam.smsgate.ui.components.SgCard
import com.jassam.smsgate.ui.components.StatusChip
import com.jassam.smsgate.ui.theme.Motion
import com.jassam.smsgate.ui.theme.Palette
import com.jassam.smsgate.ui.theme.SmsGateTheme
import com.jassam.smsgate.ui.theme.Tone
import com.jassam.smsgate.ui.theme.rememberReducedMotion

/**
 * The setup checklist: SMS, SIM list, notifications (API 33+), background
 * running, and the OEM battery step (D12) when the manufacturer needs one.
 * State is re-read on every resume, so changes made in system settings show
 * up when the user comes back. [onContinue] is always available: only SMS is
 * required, and skipping it is allowed (the app explains what won't work).
 */
@Composable
fun OnboardingScreen(prefs: OnboardingPrefs, oem: Oem?, onContinue: () -> Unit) {
    val activity = checkNotNull(LocalActivity.current) { "OnboardingScreen needs an Activity" }
    var snapshot by remember { mutableStateOf(Permissions.snapshot(activity, prefs, oem)) }
    val refresh = { snapshot = Permissions.snapshot(activity, prefs, oem) }
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    fun request(item: ChecklistItem) {
        when (item) {
            ChecklistItem.Sms -> {
                prefs.markAsked(listOf(Permissions.SMS))
                single.launch(Permissions.SMS)
            }
            ChecklistItem.PhoneState -> {
                prefs.markAsked(Permissions.phone)
                multiple.launch(Permissions.phone.toTypedArray())
            }
            ChecklistItem.Notifications -> {
                prefs.markAsked(listOf(Permissions.NOTIFICATIONS))
                single.launch(Permissions.NOTIFICATIONS)
            }
            ChecklistItem.Battery -> Permissions.requestBatteryExemption(activity)
            ChecklistItem.OemBattery -> oem?.let { Permissions.openOemSettings(activity, it) }
        }
    }

    val list = OnboardingLogic.checklist(snapshot)
    val ready = OnboardingLogic.ready(list)
    val done = OnboardingLogic.doneCount(list)
    val reduced = rememberReducedMotion()
    val progress by animateFloatAsState(done / list.size.toFloat(), Motion.spec(reduced, Motion.SLOW_MS), label = "progress")
    val colors = SmsGateTheme.colors

    Box(
        Modifier.fillMaxSize().background(colors.canvas).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Wordmark()
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.fg,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.onboarding_subtitle), style = MaterialTheme.typography.bodyMedium, color = colors.fgMuted)
            val progressText = stringResource(R.string.onboarding_progress, done, list.size)
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f).height(6.dp),
                    color = Palette.Brand600,
                    trackColor = colors.surfaceSunken,
                    drawStopIndicator = {},
                )
                Text(progressText, style = MaterialTheme.typography.labelMedium, color = colors.fgMuted)
            }

            for (entry in list) {
                ChecklistCard(
                    entry = entry,
                    oem = oem,
                    onAction = { if (entry.action != ItemAction.None) request(entry.item) },
                    onAppSettings = { Permissions.openAppSettings(activity) },
                    onMarkDone = {
                        prefs.oemAcknowledged = true
                        refresh()
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            if (ready) {
                PrimaryButton(stringResource(R.string.onboarding_continue), onContinue, Modifier.fillMaxWidth())
            } else {
                Text(stringResource(R.string.onboarding_skip_note), style = MaterialTheme.typography.bodySmall, color = colors.fgMuted)
                SecondaryButton(stringResource(R.string.onboarding_skip), onContinue, Modifier.fillMaxWidth())
            }
        }
    }
}

private data class ItemCopy(val title: String, val why: String, @DrawableRes val icon: Int)

@Composable
private fun copyOf(item: ChecklistItem, oem: Oem?): ItemCopy = when (item) {
    ChecklistItem.Sms -> ItemCopy(stringResource(R.string.item_sms_title), stringResource(R.string.item_sms_why), R.drawable.ic_sms)
    ChecklistItem.PhoneState -> ItemCopy(stringResource(R.string.item_phone_title), stringResource(R.string.item_phone_why), R.drawable.ic_sim)
    ChecklistItem.Notifications ->
        ItemCopy(stringResource(R.string.item_notifications_title), stringResource(R.string.item_notifications_why), R.drawable.ic_notifications)
    ChecklistItem.Battery -> ItemCopy(stringResource(R.string.item_battery_title), stringResource(R.string.item_battery_why), R.drawable.ic_battery)
    ChecklistItem.OemBattery -> {
        val o = checkNotNull(oem)
        ItemCopy(stringResource(R.string.item_oem_title, stringResource(o.label)), stringResource(o.steps), R.drawable.ic_phone)
    }
}

@Composable
private fun ChecklistCard(
    entry: ChecklistEntry,
    oem: Oem?,
    onAction: () -> Unit,
    onAppSettings: () -> Unit,
    onMarkDone: () -> Unit,
) {
    val copy = copyOf(entry.item, oem)
    val colors = SmsGateTheme.colors
    val oemItem = entry.item == ChecklistItem.OemBattery
    val (statusText, tone) = when (entry.status) {
        ItemStatus.Done -> stringResource(if (oemItem) R.string.status_done else R.string.status_allowed) to Tone.Success
        ItemStatus.NotAsked -> stringResource(if (oemItem) R.string.status_recommended else R.string.status_not_set) to Tone.Neutral
        // Blocked is a guess (see ItemStatus), so it reads as "Denied" with a settings hint.
        ItemStatus.Denied, ItemStatus.Blocked -> stringResource(R.string.status_denied) to Tone.Warning
    }
    val reduced = rememberReducedMotion()
    SgCard(
        Modifier.fillMaxWidth().animateContentSize(Motion.spec(reduced)),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            // Title, why line and status read as one TalkBack item; buttons stay separate.
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = statusText },
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconTile(copy.icon, if (entry.status == ItemStatus.Done) Tone.Success else Tone.Brand)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(copy.title, style = MaterialTheme.typography.titleSmall, color = colors.fg)
                    // Announced once, through the row's stateDescription.
                    StatusChip(statusText, tone, decorative = true)
                    if (entry.required && entry.status != ItemStatus.Done) {
                        StatusChip(stringResource(R.string.onboarding_required), Tone.Brand)
                    }
                }
                Text(copy.why, style = MaterialTheme.typography.bodySmall, color = colors.fgMuted)
                if (entry.status == ItemStatus.Blocked) {
                    Text(stringResource(R.string.item_blocked_hint), style = MaterialTheme.typography.bodySmall, color = colors.fgMuted)
                }
            }
        }
        if (entry.action != ItemAction.None) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 54.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val label = when (entry.action) {
                    ItemAction.Request -> stringResource(R.string.action_allow)
                    else -> stringResource(R.string.action_open_settings)
                }
                SecondaryButton(label, onAction)
                if (entry.settingsLink) GhostButton(stringResource(R.string.action_open_app_settings), onAppSettings)
                if (oemItem) GhostButton(stringResource(R.string.action_mark_done), onMarkDone)
            }
        }
    }
}
