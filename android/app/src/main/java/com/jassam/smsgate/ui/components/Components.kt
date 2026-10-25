package com.jassam.smsgate.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jassam.smsgate.R
import com.jassam.smsgate.ui.theme.Elevation
import com.jassam.smsgate.ui.theme.Motion
import com.jassam.smsgate.ui.theme.Palette
import com.jassam.smsgate.ui.theme.Radii
import com.jassam.smsgate.ui.theme.SmsGateTheme
import com.jassam.smsgate.ui.theme.Tone
import com.jassam.smsgate.ui.theme.elevation
import com.jassam.smsgate.ui.theme.elevationHighlight
import com.jassam.smsgate.ui.theme.rememberReducedMotion
import com.jassam.smsgate.ui.theme.toneColors

// Small Compose counterparts of web/src/ui/* (Card, Button, Badge, Input), built
// on the generated tokens only. 48 dp minimum touch targets throughout.

/** web Card: rounded-2xl, 1 px border, surface, --shadow-card (or raised). */
@Composable
fun SgCard(
    modifier: Modifier = Modifier,
    level: Elevation = Elevation.Card,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = SmsGateTheme.colors
    var m = modifier
        .elevation(level, Radii.Card)
        .clip(Radii.Card)
        .background(colors.surface)
        .elevationHighlight(level, Radii.Card)
        .border(1.dp, colors.border, Radii.Card)
    if (onClick != null) m = m.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    Column(m.padding(contentPadding), content = content)
}

private enum class ButtonKind { Primary, Secondary, Ghost, Danger }

/** web Button variant="primary": brand gradient, white label, control shadow. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLabel: String? = null,
    @DrawableRes icon: Int? = null,
) = SgButton(ButtonKind.Primary, text, onClick, modifier, enabled, loading, loadingLabel, icon)

/** web Button variant="secondary": surface, border, fg label. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) = SgButton(ButtonKind.Secondary, text, onClick, modifier, enabled, loading = false, loadingLabel = null, icon = icon)

/** web Button variant="danger": solid danger fill (destructive confirmations such as Unpair). */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLabel: String? = null,
) = SgButton(ButtonKind.Danger, text, onClick, modifier, enabled, loading, loadingLabel, icon = null)

/** web Button variant="ghost", in brand: secondary actions and links. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    SgButton(ButtonKind.Ghost, text, onClick, modifier, enabled, loading = false, loadingLabel = null, icon = null)

@Composable
private fun SgButton(
    kind: ButtonKind,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    loading: Boolean,
    loadingLabel: String?,
    @DrawableRes icon: Int?,
) {
    val colors = SmsGateTheme.colors
    val dark = SmsGateTheme.isDark
    val reduced = rememberReducedMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) 0.98f else 1f, Motion.spec(reduced, Motion.FAST_MS), label = "press")
    val active = enabled && !loading
    val content = when (kind) {
        ButtonKind.Primary -> colors.fgOnBrand
        ButtonKind.Secondary -> colors.fg
        ButtonKind.Ghost -> if (dark) Palette.Brand300 else Palette.Brand700
        ButtonKind.Danger -> colors.fgOnDanger
    }
    var m = modifier
        .heightIn(min = 48.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }
    m = when (kind) {
        ButtonKind.Primary -> m.elevation(Elevation.Control, Radii.Control).clip(Radii.Control)
            .background(Brush.verticalGradient(listOf(Palette.Brand600, Palette.Brand700)))
        ButtonKind.Secondary -> m.elevation(Elevation.Control, Radii.Control).clip(Radii.Control)
            .background(colors.surface).border(1.dp, colors.border, Radii.Control)
        ButtonKind.Ghost -> m.clip(Radii.Control)
        ButtonKind.Danger -> m.elevation(Elevation.Control, Radii.Control).clip(Radii.Control)
            .background(if (dark) Palette.Danger500 else Palette.Danger600)
    }
    m = m.clickable(interactionSource = interaction, indication = ripple(), enabled = active, role = Role.Button, onClick = onClick)
        .alpha(if (enabled) 1f else 0.55f)
    if (loading) {
        val busy = loadingLabel ?: text
        m = m.semantics { stateDescription = busy }
    }
    Row(
        m.padding(horizontal = if (kind == ButtonKind.Ghost) 12.dp else 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), color = content, strokeWidth = 2.dp)
        } else if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/**
 * web Badge with a dot: rounded-full, tinted by [tone]. [decorative] hides it
 * from TalkBack when the parent already announces the same text (stateDescription).
 */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier, decorative: Boolean = false) {
    val t = toneColors(tone, SmsGateTheme.colors, SmsGateTheme.isDark)
    Row(
        (if (decorative) modifier.clearAndSetSemantics { } else modifier)
            .clip(Radii.Pill)
            .background(t.container)
            .border(1.dp, t.ring, Radii.Pill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(Radii.Pill).background(t.dot))
        Text(text, style = MaterialTheme.typography.labelMedium, color = t.content)
    }
}

/** Rounded icon tile tinted by [tone] (the dashboard's empty-state / alert icons). */
@Composable
fun IconTile(@DrawableRes icon: Int, tone: Tone, modifier: Modifier = Modifier, size: Int = 40) {
    val t = toneColors(tone, SmsGateTheme.colors, SmsGateTheme.isDark)
    Box(
        modifier.size(size.dp).clip(Radii.Control).background(t.container).border(1.dp, t.ring, Radii.Control),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = t.content, modifier = Modifier.size((size * 0.5f).dp))
    }
}

/**
 * Inline alert (web FormAlert): tinted panel, announced politely by TalkBack.
 * [onDismiss] adds a 48 dp close button.
 */
@Composable
fun Banner(text: String, tone: Tone, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val t = toneColors(tone, SmsGateTheme.colors, SmsGateTheme.isDark)
    Row(
        modifier
            .clip(Radii.Control)
            .background(t.container)
            .border(1.dp, t.ring, Radii.Control)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 14.dp, end = if (onDismiss != null) 2.dp else 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = t.content, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = t.content, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        if (onDismiss != null) {
            val label = stringResource(R.string.action_dismiss)
            IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = label }) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = t.content, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * web Input: rounded-xl field with label, hint or error below. Error text is
 * exposed to TalkBack through Material's isError semantics.
 */
@Composable
fun SgTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = SmsGateTheme.colors
    val dangerText = if (SmsGateTheme.isDark) Palette.Danger400 else Palette.Danger600
    val note = error ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        supportingText = note?.let { { Text(it) } },
        isError = error != null,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        shape = Radii.Control,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            disabledContainerColor = colors.surfaceSunken,
            errorContainerColor = colors.surface,
            focusedBorderColor = Palette.Brand500,
            unfocusedBorderColor = colors.border,
            disabledBorderColor = colors.border,
            errorBorderColor = Palette.Danger500,
            focusedLabelColor = if (SmsGateTheme.isDark) Palette.Brand300 else Palette.Brand600,
            unfocusedLabelColor = colors.fgMuted,
            errorLabelColor = dangerText,
            focusedSupportingTextColor = colors.fgMuted,
            unfocusedSupportingTextColor = colors.fgMuted,
            errorSupportingTextColor = dangerText,
            focusedTextColor = colors.fg,
            unfocusedTextColor = colors.fg,
            disabledTextColor = colors.fgMuted,
            errorTextColor = colors.fg,
            cursorColor = Palette.Brand600,
        ),
    )
}
