package com.jassam.smsgate.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.jassam.smsgate.AppGraph
import com.jassam.smsgate.ui.pairing.PairingScreen
import com.jassam.smsgate.ui.theme.Motion
import com.jassam.smsgate.ui.theme.rememberReducedMotion

private enum class Step { Checklist, Pairing }

/**
 * Unpaired flow: the checklist first (until completed once), then pairing.
 * After an unpair/revocation the app opens straight on pairing; its back
 * button returns to the checklist.
 */
@Composable
fun OnboardingFlow(graph: AppGraph) {
    val prefs = graph.onboardingPrefs
    var step by rememberSaveable { mutableStateOf(if (prefs.checklistDone) Step.Pairing else Step.Checklist) }
    val reduced = rememberReducedMotion()
    AnimatedContent(
        targetState = step,
        transitionSpec = { fadeIn(Motion.spec(reduced)) togetherWith fadeOut(Motion.spec(reduced, Motion.FAST_MS)) },
        label = "onboarding",
    ) { current ->
        when (current) {
            Step.Checklist -> OnboardingScreen(prefs, graph.oem) {
                prefs.checklistDone = true
                step = Step.Pairing
            }
            Step.Pairing -> {
                BackHandler { step = Step.Checklist }
                PairingScreen(graph, onBack = { step = Step.Checklist })
            }
        }
    }
}
