package com.jassam.smsgate.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jassam.smsgate.R
import com.jassam.smsgate.ui.theme.Gradients
import com.jassam.smsgate.ui.theme.Palette
import com.jassam.smsgate.ui.theme.Radii
import com.jassam.smsgate.ui.theme.SmsGateTheme

/** Brand mark: the message-bubble glyph on the brand gradient tile (web LogoMark). */
@Composable
fun LogoMark(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Box(
        modifier
            .size(size)
            .clip(Radii.LogoTile)
            .background(Gradients.diagonal(listOf(Palette.Brand400, Palette.Brand600, Palette.Brand800))),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_logo_bubble), contentDescription = null, modifier = Modifier.size(size * 0.62f))
    }
}

/** Mark + "SMSGate" wordmark ("Gate" in brand), as in web/src/layout/Logo.tsx. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, large: Boolean = false) {
    val gate = if (SmsGateTheme.isDark) Palette.Brand400 else Palette.Brand600
    Row(
        modifier.clearAndSetSemantics { contentDescription = "SMSGate" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoMark(size = if (large) 40.dp else 32.dp)
        Text(
            buildAnnotatedString {
                append("SMS")
                withStyle(SpanStyle(color = gate)) { append("Gate") }
            },
            style = MaterialTheme.typography.titleLarge.copy(fontSize = if (large) 20.sp else 17.sp),
            color = SmsGateTheme.colors.fg,
        )
    }
}

/** Placeholder screen until the onboarding/home screens land: the centred wordmark. */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(SmsGateTheme.colors.canvas), contentAlignment = Alignment.Center) {
        Wordmark(large = true)
    }
}
