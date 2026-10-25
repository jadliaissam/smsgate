package com.jassam.smsgate.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jassam.smsgate.AppGraph
import com.jassam.smsgate.auth.PairingState
import com.jassam.smsgate.ui.nav.MainShell
import com.jassam.smsgate.ui.onboarding.OnboardingFlow
import com.jassam.smsgate.ui.theme.Motion
import com.jassam.smsgate.ui.theme.rememberReducedMotion

/**
 * Top-level destination from the stored credentials: splash while they load,
 * onboarding/pairing when unpaired, the Home/Log/Settings shell when paired. A
 * revocation or unpair that clears the store brings the user back to pairing
 * automatically.
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
            is PairingState.Paired -> MainShell(graph, state)
        }
    }
}
