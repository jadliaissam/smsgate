package com.jassam.smsgate.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/** Dashboard motion: short and snappy (--ease-snappy, 150–250 ms). */
object Motion {
    val EaseSnappy = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    const val FAST_MS = 150
    const val BASE_MS = 200
    const val SLOW_MS = 250
}
