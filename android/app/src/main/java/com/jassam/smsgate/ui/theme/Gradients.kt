package com.jassam.smsgate.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces

/**
 * Gradient brushes. The generated palette mixes sRGB colours with Display P3 ones (for the
 * tokens outside the sRGB gamut), and android.graphics.LinearGradient throws "All colors must
 * be in the same ColorSpace!" for such a mix. Every gradient in ui/ goes through here so its
 * stops are converted to one space (a test forbids calling Brush.*Gradient elsewhere).
 */
object Gradients {
    /** [colors] converted to sRGB so they can be mixed in one gradient. */
    fun stops(colors: List<Color>): List<Color> = colors.map { it.convert(ColorSpaces.Srgb) }

    fun diagonal(colors: List<Color>): Brush = Brush.linearGradient(stops(colors))

    fun vertical(colors: List<Color>): Brush = Brush.verticalGradient(stops(colors))
}
