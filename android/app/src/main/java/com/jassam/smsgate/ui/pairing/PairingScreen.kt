package com.jassam.smsgate.ui.pairing

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jassam.smsgate.AppGraph
import com.jassam.smsgate.BuildConfig
import com.jassam.smsgate.R
import com.jassam.smsgate.ui.Wordmark
import com.jassam.smsgate.ui.components.Banner
import com.jassam.smsgate.ui.components.GhostButton
import com.jassam.smsgate.ui.components.IconTile
import com.jassam.smsgate.ui.components.PrimaryButton
import com.jassam.smsgate.ui.components.SgCard
import com.jassam.smsgate.ui.components.SgTextField
import com.jassam.smsgate.ui.theme.Elevation
import com.jassam.smsgate.ui.theme.Motion
import com.jassam.smsgate.ui.theme.Radii
import com.jassam.smsgate.ui.theme.SmsGateTheme
import com.jassam.smsgate.ui.theme.Tone
import com.jassam.smsgate.ui.theme.rememberReducedMotion

/**
 * Pairing: scan the dashboard QR, type the code, or log in. The screen leaves
 * by itself once credentials are stored (AppRoot observes the store).
 */
@Composable
fun PairingScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val fallbackName = stringResource(R.string.app_name)
    val vm = viewModel {
        PairingViewModel(
            pairer = graph.pairer(),
            defaultServerUrl = BuildConfig.DEFAULT_SERVER_URL,
            defaultName = DeviceName.default(Build.MODEL, fallbackName),
            playServices = PlayServices.available(context),
            normalizeServerUrl = graph::normalizeServerUrl,
            // A8: Unpair only needs to clear the credential store; this resets the form.
            pairingStates = graph.credentialStore.state,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    // The only back handler of the pairing step: code/login → chooser → checklist.
    // While a request is in flight back is swallowed (like the disabled back button),
    // rather than disabled, which would close the app mid-registration.
    BackHandler {
        when {
            state.busy -> Unit
            state.mode != PairingMode.Choose -> vm.setMode(PairingMode.Choose)
            else -> onBack()
        }
    }

    PairingContent(
        state = state,
        onBack = { if (state.mode == PairingMode.Choose) onBack() else vm.setMode(PairingMode.Choose) },
        onScan = { activity?.let { PlayServices.scan(it, vm::onQrScanned, vm::onScanUnavailable) } ?: vm.onScanUnavailable() },
        onMode = vm::setMode,
        onName = vm::onNameChange,
        onServer = vm::onServerChange,
        onCode = vm::onCodeChange,
        onEmail = vm::onEmailChange,
        onPassword = vm::onPasswordChange,
        onSubmit = vm::submit,
        onDismissError = vm::dismissError,
    )
    state.pendingQr?.let { QrConfirmDialog(it, onConfirm = { shown -> vm.confirmQr(shown) }, onCancel = vm::cancelQr) }
}

/**
 * "Pair this phone with <host>?" for a scanned QR naming a non-default server.
 * The host is shown in full (wrapping, never truncated) as OkHttp canonicalised
 * it, and the full address below it; nothing is sent until Pair is tapped.
 */
@Composable
private fun QrConfirmDialog(pending: PendingQr, onConfirm: (PendingQr) -> Unit, onCancel: () -> Unit) {
    val colors = SmsGateTheme.colors
    val title = stringResource(R.string.qr_confirm_title, pending.host)
    Dialog(onDismissRequest = onCancel) {
        SgCard(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().semantics { paneTitle = title },
            level = Elevation.Raised,
            contentPadding = PaddingValues(24.dp),
        ) {
            // Scrolls so a long host or a large font never pushes the buttons off a small screen.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                IconTile(R.drawable.ic_qr, Tone.Brand)
                Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.fg, modifier = Modifier.semantics { heading() })
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(Radii.Control)
                        .background(colors.surfaceSunken)
                        .border(1.dp, colors.border, Radii.Control)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(stringResource(R.string.qr_confirm_url_label), style = MaterialTheme.typography.labelMedium, color = colors.fgMuted)
                    Text(pending.serverUrl, style = MaterialTheme.typography.bodyMedium, color = colors.fg)
                }
                Text(stringResource(R.string.qr_confirm_note), style = MaterialTheme.typography.bodySmall, color = colors.fgMuted)
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PrimaryButton(stringResource(R.string.qr_confirm_pair), { onConfirm(pending) }, Modifier.fillMaxWidth())
                    GhostButton(stringResource(R.string.qr_confirm_cancel), onCancel, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PairingContent(
    state: PairingUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onMode: (PairingMode) -> Unit,
    onName: (String) -> Unit,
    onServer: (String) -> Unit,
    onCode: (String) -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = SmsGateTheme.colors
    val reduced = rememberReducedMotion()
    Box(
        Modifier.fillMaxSize().background(colors.canvas).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val back = stringResource(R.string.action_back)
                IconButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = back }) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, tint = colors.fg)
                }
                Spacer(Modifier.size(4.dp))
                Wordmark()
            }
            Text(
                stringResource(R.string.pairing_title),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.fg,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
            Text(stringResource(R.string.pairing_subtitle), style = MaterialTheme.typography.bodyMedium, color = colors.fgMuted)

            SgTextField(
                value = state.deviceName,
                onValueChange = onName,
                label = stringResource(R.string.pairing_name_label),
                hint = stringResource(R.string.pairing_name_hint),
                error = state.fieldErrors[Field.Name]?.let { fieldErrorText(Field.Name, it) },
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            // Keep the last error on screen while the banner animates out.
            var shownError by remember { mutableStateOf(state.error) }
            if (state.error != null) shownError = state.error
            AnimatedVisibility(
                visible = state.error != null,
                enter = fadeIn(Motion.spec(reduced)) + expandVertically(Motion.spec(reduced)),
                exit = fadeOut(Motion.spec(reduced, Motion.FAST_MS)) + shrinkVertically(Motion.spec(reduced, Motion.FAST_MS)),
            ) {
                shownError?.let { Banner(errorText(it), Tone.Danger, Modifier.fillMaxWidth(), onDismiss = onDismissError) }
            }

            AnimatedContent(
                targetState = state.mode,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal || initialState == PairingMode.Choose
                    val sign = if (forward) 1 else -1
                    (fadeIn(Motion.spec(reduced)) + slideInHorizontally(Motion.spec(reduced)) { if (reduced) 0 else sign * it / 12 }) togetherWith
                        (fadeOut(Motion.spec(reduced, Motion.FAST_MS)) + slideOutHorizontally(Motion.spec(reduced, Motion.FAST_MS)) { if (reduced) 0 else -sign * it / 12 })
                },
                label = "pairingMode",
            ) { mode ->
                when (mode) {
                    PairingMode.Choose -> Chooser(state, onScan, onMode)
                    PairingMode.Code -> CodeForm(state, onServer, onCode, onSubmit, onMode)
                    PairingMode.Login -> LoginForm(state, onServer, onEmail, onPassword, onSubmit, onMode)
                }
            }
        }
    }
}

@Composable
private fun Chooser(state: PairingUiState, onScan: () -> Unit, onMode: (PairingMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.canScan) {
            MethodCard(R.drawable.ic_qr, stringResource(R.string.pairing_scan_title), stringResource(R.string.pairing_scan_body), primary = true, onScan)
        } else {
            Banner(stringResource(R.string.pairing_no_play_services), Tone.Warning, Modifier.fillMaxWidth())
        }
        MethodCard(R.drawable.ic_keyboard, stringResource(R.string.pairing_code_title), stringResource(R.string.pairing_code_body), primary = !state.canScan) {
            onMode(PairingMode.Code)
        }
        MethodCard(R.drawable.ic_person, stringResource(R.string.pairing_login_title), stringResource(R.string.pairing_login_body), primary = false) {
            onMode(PairingMode.Login)
        }
    }
}

@Composable
private fun MethodCard(@DrawableRes icon: Int, title: String, body: String, primary: Boolean, onClick: () -> Unit) {
    val colors = SmsGateTheme.colors
    SgCard(
        Modifier.fillMaxWidth(),
        level = if (primary) Elevation.Raised else Elevation.Card,
        contentPadding = PaddingValues(16.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(icon, if (primary) Tone.Brand else Tone.Neutral)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.fg)
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.fgMuted)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.fgSubtle)
        }
    }
}

@Composable
private fun ServerField(state: PairingUiState, onServer: (String) -> Unit) = SgTextField(
    value = state.serverUrl,
    onValueChange = onServer,
    label = stringResource(R.string.pairing_server_label),
    hint = stringResource(R.string.pairing_server_hint),
    error = state.fieldErrors[Field.Server]?.let { fieldErrorText(Field.Server, it) },
    enabled = !state.busy,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun CodeForm(
    state: PairingUiState,
    onServer: (String) -> Unit,
    onCode: (String) -> Unit,
    onSubmit: () -> Unit,
    onMode: (PairingMode) -> Unit,
) {
    Column {
    SgCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServerField(state, onServer)
            SgTextField(
                value = state.code,
                onValueChange = onCode,
                label = stringResource(R.string.pairing_code_label),
                error = state.fieldErrors[Field.Code]?.let { fieldErrorText(Field.Code, it) },
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                visualTransformation = CodeTransformation,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                stringResource(R.string.pairing_submit_code),
                onSubmit,
                Modifier.fillMaxWidth(),
                loading = state.busy,
                loadingLabel = stringResource(R.string.pairing_busy),
            )
        }
    }
    GhostButton(stringResource(R.string.pairing_use_login), { onMode(PairingMode.Login) }, Modifier.padding(top = 8.dp), enabled = !state.busy)
    }
}

@Composable
private fun LoginForm(
    state: PairingUiState,
    onServer: (String) -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onMode: (PairingMode) -> Unit,
) {
    Column {
        SgCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerField(state, onServer)
                SgTextField(
                    value = state.email,
                    onValueChange = onEmail,
                    label = stringResource(R.string.pairing_email_label),
                    error = state.fieldErrors[Field.Email]?.let { fieldErrorText(Field.Email, it) },
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                SgTextField(
                    value = state.password,
                    onValueChange = onPassword,
                    label = stringResource(R.string.pairing_password_label),
                    error = state.fieldErrors[Field.Password]?.let { fieldErrorText(Field.Password, it) },
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                PrimaryButton(
                    stringResource(R.string.pairing_submit_login),
                    onSubmit,
                    Modifier.fillMaxWidth(),
                    loading = state.busy,
                    loadingLabel = stringResource(R.string.pairing_busy),
                )
            }
        }
        GhostButton(stringResource(R.string.pairing_use_code), { onMode(PairingMode.Code) }, Modifier.padding(top = 8.dp), enabled = !state.busy)
    }
}

/** Shows the stored 8 characters as `XXXX-XXXX` without putting the dash in the value. */
private object CodeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.length <= 4) return TransformedText(text, OffsetMapping.Identity)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = if (offset <= 4) offset else offset + 1
            override fun transformedToOriginal(offset: Int): Int = (if (offset <= 4) offset else offset - 1).coerceIn(0, raw.length)
        }
        return TransformedText(AnnotatedString(PairingCode.format(raw)), mapping)
    }
}

@Composable
private fun fieldErrorText(field: Field, error: FieldError): String = when (error) {
    FieldError.Required -> stringResource(R.string.field_required)
    FieldError.InvalidServer -> stringResource(R.string.field_invalid_server)
    FieldError.IncompleteCode -> stringResource(R.string.field_incomplete_code)
    FieldError.InvalidEmail -> stringResource(R.string.field_invalid_email)
    FieldError.TooLong -> if (field == Field.Name) stringResource(R.string.field_name_length) else stringResource(R.string.field_required)
}

/** User-facing copy for every [PairingError] (never includes server text, tokens or the password). */
@Composable
private fun errorText(error: PairingError): String = when (error) {
    PairingError.InvalidCode -> stringResource(R.string.pairing_error_invalid_code)
    PairingError.InvalidCredentials -> stringResource(R.string.pairing_error_invalid_credentials)
    PairingError.EmailNotVerified -> stringResource(R.string.pairing_error_email_not_verified)
    is PairingError.RateLimited -> error.retryAfterSeconds?.takeIf { it in 1..3600 }?.toInt()
        ?.let { pluralStringResource(R.plurals.pairing_error_rate_limited_wait, it, it) }
        ?: stringResource(R.string.pairing_error_rate_limited)
    PairingError.Network -> stringResource(R.string.pairing_error_network)
    PairingError.ServerUnavailable -> stringResource(R.string.pairing_error_server_unavailable)
    PairingError.NotSmsGateServer -> stringResource(R.string.pairing_error_not_smsgate)
    PairingError.StorageFailed -> stringResource(R.string.pairing_error_storage)
    PairingError.InvalidQr -> stringResource(R.string.pairing_error_invalid_qr)
    PairingError.ScannerUnavailable -> stringResource(R.string.pairing_error_scanner)
    PairingError.InvalidServer -> stringResource(R.string.field_invalid_server)
    PairingError.InvalidName -> stringResource(R.string.field_name_length)
    is PairingError.Unknown -> stringResource(R.string.pairing_error_unknown)
}
