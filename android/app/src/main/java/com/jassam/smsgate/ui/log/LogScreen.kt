package com.jassam.smsgate.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jassam.smsgate.AppGraph
import com.jassam.smsgate.R
import com.jassam.smsgate.auth.PairingState
import com.jassam.smsgate.ui.components.CardDivider
import com.jassam.smsgate.ui.components.ContentMaxWidth
import com.jassam.smsgate.ui.components.EmptyState
import com.jassam.smsgate.ui.components.PageHeader
import com.jassam.smsgate.ui.components.SecondaryButton
import com.jassam.smsgate.ui.components.SgCard
import com.jassam.smsgate.ui.components.StatusChip
import com.jassam.smsgate.ui.format.TimeFormat
import com.jassam.smsgate.ui.format.absoluteText
import com.jassam.smsgate.ui.format.rememberNow
import com.jassam.smsgate.ui.format.sinceText
import com.jassam.smsgate.ui.theme.Palette
import com.jassam.smsgate.ui.theme.Radii
import com.jassam.smsgate.ui.theme.SmsGateTheme
import com.jassam.smsgate.ui.theme.Tone
import com.jassam.smsgate.ui.theme.toneColors

@Composable
fun LogScreen(graph: AppGraph, paired: PairingState.Paired) {
    val vm = viewModel(key = "log:${paired.deviceId}") { graph.logViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    LogContent(state, rememberNow(), onOpen = vm::open, onLoadMore = vm::loadMore)
    val shown = detail
    if (selected != null && shown != null) DetailSheet(shown, onClose = vm::close)
}

/** The list: header, rows with stable keys, empty state, "Load more". */
@Composable
fun LogContent(state: LogUiState, nowMs: Long, onOpen: (String) -> Unit, onLoadMore: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header", contentType = "header") {
                PageHeader(stringResource(R.string.log_title), Modifier.padding(bottom = 8.dp), subtitle = stringResource(R.string.log_subtitle))
            }
            when {
                state.loading -> item(key = "loading", contentType = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Palette.Brand600, strokeWidth = 2.dp)
                    }
                }
                state.items.isEmpty() -> item(key = "empty", contentType = "empty") {
                    EmptyState(R.drawable.ic_inbox, stringResource(R.string.log_empty_title), stringResource(R.string.log_empty_body))
                }
                else -> items(state.items, key = { it.id }, contentType = { "row" }) { item ->
                    LogRow(item, nowMs, onOpen)
                }
            }
            if (state.canLoadMore) {
                item(key = "more", contentType = "more") {
                    SecondaryButton(stringResource(R.string.log_load_more), onLoadMore, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }
}

/** The status in the dashboard's words. */
@Composable
fun statusLabel(status: LogStatus): String = stringResource(
    when (status) {
        LogStatus.Queued -> R.string.status_queued
        LogStatus.Dispatched -> R.string.status_dispatched
        LogStatus.Sent -> R.string.status_sent
        LogStatus.Delivered -> R.string.status_delivered
        LogStatus.Failed -> R.string.status_failed
        LogStatus.Expired -> R.string.status_expired
    },
)

/** How TalkBack reads a masked number ("number ending in 89"), instead of a row of stars. */
@Composable
private fun spokenNumber(masked: String): String {
    val tail = masked.filter { it.isDigit() }.takeLast(2)
    return if (tail.isEmpty()) stringResource(R.string.log_number_hidden) else stringResource(R.string.log_number_ending, tail)
}

@Composable
private fun LogRow(item: LogItem, nowMs: Long, onOpen: (String) -> Unit) {
    val colors = SmsGateTheme.colors
    val status = statusLabel(item.status)
    val time = sinceText(item.atMs, nowMs)
    val spoken = stringResource(R.string.log_row_spoken, spokenNumber(item.maskedTo), status, time)
    SgCard(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        onClick = { onOpen(item.id) },
        onClickLabel = stringResource(R.string.log_open_details),
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(item.maskedTo, style = MaterialTheme.typography.titleSmall, color = colors.fg)
            StatusChip(status, item.status.tone, decorative = true)
        }
        Text(
            item.simSlot?.let { stringResource(R.string.log_row_meta_sim, time, it + 1) } ?: time,
            style = MaterialTheme.typography.bodySmall,
            color = colors.fgMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(detail: LogDetail, onClose: () -> Unit) {
    val colors = SmsGateTheme.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        contentColor = colors.fg,
        scrimColor = colors.overlay,
    ) {
        DetailContent(detail)
    }
}

/** The detail sheet's body (also rendered by UI tests without the sheet). */
@Composable
fun DetailContent(detail: LogDetail) {
    val colors = SmsGateTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val spoken = spokenNumber(detail.maskedTo)
            Text(
                detail.maskedTo,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.fg,
                modifier = Modifier.semantics {
                    heading()
                    contentDescription = spoken
                },
            )
            StatusChip(statusLabel(detail.status), detail.status.tone)
        }
        detail.simSlot?.let {
            Text(stringResource(R.string.log_detail_sim, it + 1), style = MaterialTheme.typography.bodyMedium, color = colors.fgMuted)
        } ?: Text(stringResource(R.string.log_detail_sim_default), style = MaterialTheme.typography.bodyMedium, color = colors.fgMuted)

        SgCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            Text(
                stringResource(R.string.log_detail_timeline),
                style = MaterialTheme.typography.titleSmall,
                color = colors.fg,
                modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
            )
            detail.timeline.forEachIndexed { i, step -> TimelineRow(step, last = i == detail.timeline.lastIndex) }
            CardDivider()
            Text(reportText(detail.report), style = MaterialTheme.typography.bodySmall, color = colors.fgMuted, modifier = Modifier.padding(top = 4.dp))
            Text(
                stringResource(R.string.log_detail_expires, absoluteText(detail.expiresAtMs)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        SgCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            Text(
                stringResource(R.string.log_detail_message),
                style = MaterialTheme.typography.titleSmall,
                color = colors.fg,
                modifier = Modifier.padding(bottom = 6.dp).semantics { heading() },
            )
            if (detail.body != null) {
                SelectionContainer { Text(detail.body, style = MaterialTheme.typography.bodyMedium, color = colors.fg) }
            } else {
                Text(stringResource(R.string.log_detail_body_removed), style = MaterialTheme.typography.bodyMedium, color = colors.fgMuted)
            }
        }

        SelectionContainer {
            Text(stringResource(R.string.log_detail_id, detail.id), style = MaterialTheme.typography.bodySmall, color = colors.fgSubtle)
        }
    }
}

@Composable
private fun reportText(report: ReportState): String = stringResource(
    when (report) {
        ReportState.None -> R.string.log_report_none
        ReportState.Pending -> R.string.log_report_pending
        ReportState.Confirmed -> R.string.log_report_confirmed
    },
)

@Composable
private fun TimelineRow(step: TimelineStep, last: Boolean) {
    val colors = SmsGateTheme.colors
    val context = LocalContext.current
    val (title, tone) = stepCopy(step)
    val time = step.atMs?.let { TimeFormat.absolute(context, it) }
    Row(
        Modifier.fillMaxWidth().padding(bottom = if (last) 6.dp else 10.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val t = toneColors(tone, colors, SmsGateTheme.isDark)
        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(Radii.Pill).background(t.dot))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.fg)
            stepDetail(step)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.fgMuted) }
            if (time != null) Text(time, style = MaterialTheme.typography.bodySmall, color = colors.fgSubtle)
        }
    }
}

@Composable
private fun stepCopy(step: TimelineStep): Pair<String, Tone> = when (step) {
    is TimelineStep.Claimed -> stringResource(R.string.log_step_claimed) to Tone.Brand
    is TimelineStep.Handed -> stringResource(R.string.log_step_handed) to Tone.Brand
    is TimelineStep.Sent -> stringResource(R.string.log_step_sent) to Tone.Brand
    is TimelineStep.Delivered -> stringResource(R.string.log_step_delivered) to Tone.Success
    is TimelineStep.Failed -> stringResource(R.string.log_step_failed) to Tone.Danger
}

@Composable
private fun stepDetail(step: TimelineStep): String? = when (step) {
    is TimelineStep.Claimed -> null
    is TimelineStep.Handed -> if (step.parts > 0) pluralStringResource(R.plurals.log_parts, step.parts, step.parts) else null
    is TimelineStep.Sent -> if (step.parts > 0) stringResource(R.string.log_parts_sent, step.sent, step.parts) else null
    is TimelineStep.Delivered -> if (step.parts > 0) stringResource(R.string.log_parts_delivered, step.delivered, step.parts) else null
    is TimelineStep.Failed -> {
        val text = friendlyText(step.friendly)
        step.code?.let { stringResource(R.string.log_error_with_code, text, it) } ?: text
    }
}

@Composable
fun friendlyText(e: FriendlyError): String = stringResource(
    when (e) {
        FriendlyError.NoService -> R.string.error_no_service
        FriendlyError.RadioOff -> R.string.error_radio_off
        FriendlyError.Generic -> R.string.error_generic
        FriendlyError.LimitExceeded -> R.string.error_limit
        FriendlyError.ShortCode -> R.string.error_short_code
        FriendlyError.Permission -> R.string.error_permission
        FriendlyError.SimUnavailable -> R.string.error_sim_unavailable
        FriendlyError.NoDefaultSim -> R.string.error_no_default_sim
        FriendlyError.InvalidDestination -> R.string.error_invalid_destination
        FriendlyError.ExpiredOnDevice -> R.string.error_expired_on_device
        FriendlyError.OutcomeUnknown -> R.string.error_outcome_unknown
        FriendlyError.DeviceRevoked -> R.string.error_device_revoked
        FriendlyError.DeliveryFailed -> R.string.error_delivery_failed
        FriendlyError.BodyMissing -> R.string.error_body_missing
        FriendlyError.TooManyParts -> R.string.error_too_many_parts
        FriendlyError.Fdn -> R.string.error_fdn
        FriendlyError.NoSmsService -> R.string.error_no_sms_service
        FriendlyError.Other -> R.string.error_other
    },
)
