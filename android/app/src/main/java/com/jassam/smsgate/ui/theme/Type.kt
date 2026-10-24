package com.jassam.smsgate.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jassam.smsgate.R

/** Inter 4.1 (latin subset), the dashboard's typeface. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

// Same OpenType features as the dashboard's body (font-feature-settings: "cv11", "ss01").
private const val FEATURES = "'cv11', 'ss01'"
private val Tight = (-0.025).em // Tailwind tracking-tight, used on headings

private fun style(size: Int, line: Int, weight: FontWeight, tracking: androidx.compose.ui.unit.TextUnit = 0.em) =
    TextStyle(
        fontFamily = Inter,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking,
        fontFeatureSettings = FEATURES,
    )

/** Type scale mapped from the dashboard's Tailwind sizes (xs 12 … 3xl 30). */
val SmsGateTypography = Typography(
    displayLarge = style(36, 40, FontWeight.SemiBold, Tight),
    displayMedium = style(30, 36, FontWeight.SemiBold, Tight),
    displaySmall = style(24, 32, FontWeight.SemiBold, Tight),
    headlineLarge = style(24, 32, FontWeight.SemiBold, Tight),
    headlineMedium = style(20, 28, FontWeight.SemiBold, Tight),
    headlineSmall = style(18, 26, FontWeight.SemiBold, Tight),
    titleLarge = style(18, 28, FontWeight.SemiBold, Tight),
    titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.Medium),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium),
    labelSmall = style(11, 16, FontWeight.Medium),
)
