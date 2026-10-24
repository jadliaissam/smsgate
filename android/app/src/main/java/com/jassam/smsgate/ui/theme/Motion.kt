package com.jassam.smsgate.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Dashboard motion: short and snappy (--ease-snappy, 150–250 ms). */
object Motion {
    val EaseSnappy = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    const val FAST_MS = 150
    const val BASE_MS = 200
    const val SLOW_MS = 250

    /** A snappy tween, or an instant [snap] when motion is reduced. */
    fun <T> spec(reduced: Boolean, durationMs: Int = BASE_MS): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMs, easing = EaseSnappy)
}

/**
 * True when the system animator duration scale is 0 ("Remove animations" /
 * developer option), the Android counterpart of prefers-reduced-motion.
 * Read once per composition entry: the setting rarely changes while a screen is shown.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
