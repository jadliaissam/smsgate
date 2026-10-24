package com.jassam.smsgate.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** One CSS box-shadow layer: `offsetY blur spread colour` (x offset is always 0 here). */
data class ShadowLayer(val y: Int, val blur: Int, val spread: Int, val color: (SemanticColors) -> Color)

/** Elevation levels matching the dashboard's --shadow-card and --shadow-raised. */
enum class Elevation(val inset: ShadowLayer?, val drops: List<ShadowLayer>) {
    Flat(null, emptyList()),

    /** `inset 0 1px 0 0 highlight, 0 1px 2px 0 tint-sm, 0 1px 3px 0 tint-xs` */
    Card(
        ShadowLayer(1, 0, 0) { it.shadowHighlight },
        listOf(ShadowLayer(1, 2, 0) { it.shadowTintSm }, ShadowLayer(1, 3, 0) { it.shadowTintXs }),
    ),

    /** `inset 0 1px 0 0 highlight, 0 2px 4px -1px tint-sm, 0 8px 16px -4px tint-md, 0 16px 32px -8px tint-xs` */
    Raised(
        ShadowLayer(1, 0, 0) { it.shadowHighlight },
        listOf(
            ShadowLayer(2, 4, -1) { it.shadowTintSm },
            ShadowLayer(8, 16, -4) { it.shadowTintMd },
            ShadowLayer(16, 32, -8) { it.shadowTintXs },
        ),
    ),

    /** `--shadow-control`: `0 1px 2px 0 tint-sm` (buttons, inputs). */
    Control(null, listOf(ShadowLayer(1, 2, 0) { it.shadowTintSm })),
}

private fun ShadowLayer.toShadow(colors: SemanticColors) =
    Shadow(radius = blur.dp, color = color(colors), spread = spread.dp, offset = DpOffset(0.dp, y.dp))

/**
 * Soft layered shadows like the dashboard (draw behind the content; apply before
 * `background`/`clip`). The inset highlight is drawn on top of the background.
 */
@Composable
@ReadOnlyComposable
fun Modifier.elevation(level: Elevation, shape: Shape): Modifier {
    if (level == Elevation.Flat) return this
    val colors = SmsGateTheme.colors
    var m = this
    for (layer in level.drops) m = m.dropShadow(shape, layer.toShadow(colors))
    return m
}

/** The top-edge inner highlight of [level]; apply after the background. */
@Composable
@ReadOnlyComposable
fun Modifier.elevationHighlight(level: Elevation, shape: Shape): Modifier {
    val inset = level.inset ?: return this
    return innerShadow(shape, inset.toShadow(SmsGateTheme.colors))
}
