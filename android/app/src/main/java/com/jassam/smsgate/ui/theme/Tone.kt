package com.jassam.smsgate.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Badge tones (web/src/ui/Badge.tsx). Statuses map onto them as on the dashboard (D9). */
enum class Tone { Neutral, Brand, Success, Warning, Danger }

/** Chip container, content, ring and dot colours for a [Tone]. */
@Immutable
data class ToneColors(val container: Color, val content: Color, val ring: Color, val dot: Color)

fun toneColors(tone: Tone, colors: SemanticColors, dark: Boolean): ToneColors = when (tone) {
    Tone.Neutral -> ToneColors(colors.surfaceSunken, colors.fgMuted, colors.border, colors.fgSubtle)
    Tone.Brand -> tinted(dark, Palette.Brand50, Palette.Brand700, Palette.Brand600, Palette.Brand500, Palette.Brand300, Palette.Brand400)
    Tone.Success -> tinted(dark, Palette.Success50, Palette.Success700, Palette.Success600, Palette.Success500, Palette.Success300, Palette.Success400)
    Tone.Warning -> tinted(dark, Palette.Warning50, Palette.Warning800, Palette.Warning600, Palette.Warning500, Palette.Warning300, Palette.Warning400, ringAlpha = 0.20f)
    Tone.Danger -> tinted(dark, Palette.Danger50, Palette.Danger700, Palette.Danger600, Palette.Danger500, Palette.Danger300, Palette.Danger400)
}

// Light: bg-x-50 text-x-700 ring-x-600/15; dark: bg-x-500/12 text-x-300 ring-x-400/20; dot x-500.
private fun tinted(
    dark: Boolean,
    c50: Color,
    text: Color,
    c600: Color,
    c500: Color,
    c300: Color,
    c400: Color,
    ringAlpha: Float = 0.15f,
) = if (dark) {
    ToneColors(c500.copy(alpha = 0.12f), c300, c400.copy(alpha = 0.20f), c500)
} else {
    ToneColors(c50, text, c600.copy(alpha = ringAlpha), c500)
}
