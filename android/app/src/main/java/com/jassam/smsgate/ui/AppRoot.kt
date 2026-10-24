package com.jassam.smsgate.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jassam.smsgate.AppGraph
import com.jassam.smsgate.R
import com.jassam.smsgate.auth.PairingState
import com.jassam.smsgate.ui.components.IconTile
import com.jassam.smsgate.ui.components.SgCard
import com.jassam.smsgate.ui.onboarding.OnboardingFlow
import com.jassam.smsgate.ui.theme.Motion
import com.jassam.smsgate.ui.theme.SmsGateTheme
import com.jassam.smsgate.ui.theme.Tone
import com.jassam.smsgate.ui.theme.rememberReducedMotion

/**
 * Top-level destination from the stored credentials: splash while they load,
 * onboarding/pairing when unpaired, home when paired. A revocation that clears
 * the store (A6) brings the user back to pairing automatically.
 */
@Composable
fun AppRoot(graph: AppGraph) {
    val pairing by graph.credentialStore.state.collectAsStateWithLifecycle(initialValue = null)
    val reduced = rememberReducedMotion()
    AnimatedContent(
        targetState = pairing,
        transitionSpec = { fadeIn(Motion.spec(reduced)) togetherWith fadeOut(Motion.spec(reduced, Motion.FAST_MS)) },
        contentKey = { it?.let { s -> s::class } },
        label = "root",
    ) { state ->
        when (state) {
            null -> SplashScreen()
            PairingState.Unpaired -> OnboardingFlow(graph)
            is PairingState.Paired -> HomePlaceholder(state.serverUrl)
        }
    }
}

/** Stand-in home until Task A8 builds the real one (ui/home). */
@Composable
private fun HomePlaceholder(serverUrl: String) {
    val colors = SmsGateTheme.colors
    Box(
        Modifier.fillMaxSize().background(colors.canvas).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Wordmark(large = true)
            SgCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconTile(R.drawable.ic_check, Tone.Success)
                    Text(
                        stringResource(R.string.home_paired_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.fg,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(R.string.home_paired_body, serverUrl), style = MaterialTheme.typography.bodyMedium, color = colors.fgMuted)
                }
            }
        }
    }
}
