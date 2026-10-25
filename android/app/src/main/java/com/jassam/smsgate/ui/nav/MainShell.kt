package com.jassam.smsgate.ui.nav

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jassam.smsgate.AppGraph
import com.jassam.smsgate.R
import com.jassam.smsgate.auth.PairingState
import com.jassam.smsgate.ui.LogoMark
import com.jassam.smsgate.ui.home.HomeScreen
import com.jassam.smsgate.ui.log.LogScreen
import com.jassam.smsgate.ui.settings.SettingsScreen
import com.jassam.smsgate.ui.theme.Motion
import com.jassam.smsgate.ui.theme.Palette
import com.jassam.smsgate.ui.theme.SmsGateTheme
import com.jassam.smsgate.ui.theme.Tone
import com.jassam.smsgate.ui.theme.rememberReducedMotion
import com.jassam.smsgate.ui.theme.toneColors

/** The paired app's destinations (no navigation library: a saved-state switcher). */
enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    Home(R.string.nav_home, R.drawable.ic_home),
    Log(R.string.nav_log, R.drawable.ic_list),
    Settings(R.string.nav_settings, R.drawable.ic_settings),
}

/**
 * Paired shell: bottom navigation on phones (a navigation rail from 600 dp wide:
 * tablets, landscape), shared-axis X transitions between tabs (instant when animations
 * are off), each tab's saveable state (scroll positions, open sheets) kept while
 * switching. Back from Log or Settings returns to Home; back on Home leaves the app.
 */
@Composable
fun MainShell(graph: AppGraph, paired: PairingState.Paired) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }
    MainShellContent(tab, onTab = { tab = it }) { current ->
        when (current) {
            Tab.Home -> HomeScreen(graph, paired, onOpenSettings = { tab = Tab.Settings })
            Tab.Log -> LogScreen(graph, paired)
            Tab.Settings -> SettingsScreen(graph, paired)
        }
    }
}

/** The shell's layout around [content] (also used by UI tests with stand-in screens). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainShellContent(tab: Tab, onTab: (Tab) -> Unit, content: @Composable (Tab) -> Unit) {
    val colors = SmsGateTheme.colors
    val reduced = rememberReducedMotion()
    val holder = rememberSaveableStateHolder()
    val offset = with(LocalDensity.current) { 30.dp.roundToPx() }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.canvas)) {
        val rail = maxWidth >= 600.dp
        val body: @Composable (Modifier) -> Unit = { m ->
            AnimatedContent(
                targetState = tab,
                modifier = m,
                transitionSpec = { sharedAxisX(targetState.ordinal > initialState.ordinal, reduced, offset) },
                label = "tab",
            ) { t -> holder.SaveableStateProvider(t.name) { content(t) } }
        }
        if (rail) {
            Row(Modifier.fillMaxSize()) {
                Rail(tab, onTab)
                body(
                    Modifier.weight(1f).fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                        .imePadding(),
                )
            }
        } else {
            val ime = WindowInsets.isImeVisible
            Column(Modifier.fillMaxSize()) {
                body(
                    Modifier.weight(1f)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .imePadding(),
                )
                // Hidden while typing so the keyboard has the room (the field stays visible).
                if (!ime) BottomBar(tab, onTab)
            }
        }
    }
}

/** Material shared axis X: slide 30 dp with a fade, snappy easing; nothing when motion is off. */
private fun sharedAxisX(forward: Boolean, reduced: Boolean, offsetPx: Int): ContentTransform {
    if (reduced) return EnterTransition.None togetherWith ExitTransition.None
    val sign = if (forward) 1 else -1
    return (slideInHorizontally(Motion.spec(false, Motion.SLOW_MS)) { sign * offsetPx } + fadeIn(Motion.spec(false, Motion.BASE_MS))) togetherWith
        (slideOutHorizontally(Motion.spec(false, Motion.SLOW_MS)) { -sign * offsetPx } + fadeOut(Motion.spec(false, Motion.FAST_MS)))
}

@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit) {
    val colors = SmsGateTheme.colors
    val dark = SmsGateTheme.isDark
    val brand = toneColors(Tone.Brand, colors, dark)
    val selected = if (dark) Palette.Brand300 else Palette.Brand700
    Column {
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
            for (t in Tab.entries) {
                NavigationBarItem(
                    selected = t == tab,
                    onClick = { onTab(t) },
                    icon = { Icon(painterResource(t.icon), contentDescription = null) },
                    label = { Text(stringResource(t.label), maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = selected,
                        selectedTextColor = selected,
                        indicatorColor = brand.container,
                        unselectedIconColor = colors.fgMuted,
                        unselectedTextColor = colors.fgMuted,
                    ),
                )
            }
        }
    }
}

@Composable
private fun Rail(tab: Tab, onTab: (Tab) -> Unit) {
    val colors = SmsGateTheme.colors
    val dark = SmsGateTheme.isDark
    val brand = toneColors(Tone.Brand, colors, dark)
    val selected = if (dark) Palette.Brand300 else Palette.Brand700
    Row {
        NavigationRail(
            containerColor = colors.surface,
            header = { LogoMark(Modifier.padding(vertical = 12.dp)) },
        ) {
            for (t in Tab.entries) {
                NavigationRailItem(
                    selected = t == tab,
                    onClick = { onTab(t) },
                    icon = { Icon(painterResource(t.icon), contentDescription = null) },
                    label = { Text(stringResource(t.label), maxLines = 1) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = selected,
                        selectedTextColor = selected,
                        indicatorColor = brand.container,
                        unselectedIconColor = colors.fgMuted,
                        unselectedTextColor = colors.fgMuted,
                    ),
                )
            }
        }
        VerticalDivider(color = colors.border, thickness = 1.dp)
    }
}
