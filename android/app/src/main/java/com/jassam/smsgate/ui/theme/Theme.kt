package com.jassam.smsgate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Light, dark, or follow the system (manual override from settings). */
enum class ThemeMode { System, Light, Dark }

private val LocalSemanticColors = staticCompositionLocalOf { LightRoles }
private val LocalIsDark = staticCompositionLocalOf { false }

object SmsGateTheme {
    /** The dashboard's semantic roles (canvas, surface, fg, border, shadow tints…). */
    val colors: SemanticColors
        @Composable @ReadOnlyComposable
        get() = LocalSemanticColors.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalIsDark.current
}

// Material 3 roles from the dashboard tokens; dynamic colour stays off (D9).
private fun lightScheme(r: SemanticColors): ColorScheme = lightColorScheme(
    primary = Palette.Brand600,
    onPrimary = r.fgOnBrand,
    primaryContainer = Palette.Brand50,
    onPrimaryContainer = Palette.Brand700,
    inversePrimary = Palette.Brand300,
    secondary = Palette.Ink700,
    onSecondary = Palette.White,
    secondaryContainer = r.surfaceSunken,
    onSecondaryContainer = r.fg,
    tertiary = Palette.Success600,
    onTertiary = r.fgOnSuccess,
    tertiaryContainer = Palette.Success50,
    onTertiaryContainer = Palette.Success700,
    background = r.canvas,
    onBackground = r.fg,
    surface = r.surface,
    onSurface = r.fg,
    surfaceVariant = r.surfaceSunken,
    onSurfaceVariant = r.fgMuted,
    surfaceTint = Color.Transparent, // the dashboard never tints surfaces
    inverseSurface = Palette.Ink900,
    inverseOnSurface = Palette.Ink50,
    error = Palette.Danger600,
    onError = r.fgOnDanger,
    errorContainer = Palette.Danger50,
    onErrorContainer = Palette.Danger700,
    outline = r.borderStrong,
    outlineVariant = r.border,
    scrim = r.overlay,
    surfaceBright = r.surface,
    surfaceDim = r.surfaceSunken,
    surfaceContainerLowest = r.surface,
    surfaceContainerLow = r.surface,
    surfaceContainer = r.surface,
    surfaceContainerHigh = r.surfaceRaised,
    surfaceContainerHighest = r.surfaceSunken,
)

private fun darkScheme(r: SemanticColors): ColorScheme = darkColorScheme(
    primary = Palette.Brand600,
    onPrimary = r.fgOnBrand,
    primaryContainer = Palette.Brand900,
    onPrimaryContainer = Palette.Brand200,
    inversePrimary = Palette.Brand700,
    secondary = Palette.Ink300,
    onSecondary = Palette.Ink950,
    secondaryContainer = r.surfaceHover,
    onSecondaryContainer = r.fg,
    tertiary = Palette.Success400,
    onTertiary = Palette.Success950,
    tertiaryContainer = Palette.Success900,
    onTertiaryContainer = Palette.Success200,
    background = r.canvas,
    onBackground = r.fg,
    surface = r.surface,
    onSurface = r.fg,
    surfaceVariant = r.surfaceRaised,
    onSurfaceVariant = r.fgMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = Palette.Ink100,
    inverseOnSurface = Palette.Ink900,
    error = Palette.Danger500,
    onError = r.fgOnDanger,
    errorContainer = Palette.Danger900,
    onErrorContainer = Palette.Danger200,
    outline = r.borderStrong,
    outlineVariant = r.border,
    scrim = r.overlay,
    surfaceBright = r.surfaceHover,
    surfaceDim = r.surfaceSunken,
    surfaceContainerLowest = r.surfaceSunken,
    surfaceContainerLow = r.surface,
    surfaceContainer = r.surface,
    surfaceContainerHigh = r.surfaceRaised,
    surfaceContainerHighest = r.surfaceHover,
)

@Composable
fun SmsGateTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val roles = if (dark) DarkRoles else LightRoles
    CompositionLocalProvider(LocalSemanticColors provides roles, LocalIsDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(roles) else lightScheme(roles),
            typography = SmsGateTypography,
            shapes = SmsGateShapes,
            content = content,
        )
    }
}
