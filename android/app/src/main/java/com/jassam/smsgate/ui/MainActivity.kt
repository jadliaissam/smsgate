package com.jassam.smsgate.ui

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jassam.smsgate.appGraph
import com.jassam.smsgate.ui.theme.SmsGateTheme
import com.jassam.smsgate.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Display P3 tokens show the dashboard's saturation on wide-gamut screens.
        // Compose passes wide-gamut colours to the canvas only from API 29; on 26–28
        // the mode would cost a double-size buffer for no visible gain.
        if (Build.VERSION.SDK_INT >= 29 && resources.configuration.isScreenWideColorGamut) {
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
        val graph = appGraph
        // Paired: periodic + immediate sync (unique KEEP, so recreation adds nothing).
        if (savedInstanceState == null) {
            lifecycleScope.launch(Dispatchers.Default) { graph.onAppStart() }
        }
        setContent {
            // The theme override (Settings) is read from its DataStore off the main thread;
            // until it arrives (a frame or two) only the window background (the system's
            // light/dark canvas) shows, rather than a full frame in the wrong theme.
            val mode by graph.appSettings.themeMode.collectAsStateWithLifecycle(initialValue = null)
            val current = mode ?: return@setContent
            val systemDark = isSystemInDarkTheme()
            val dark = when (current) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // System bar icons follow the app's theme, not only the system's.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                if (SystemBars.transparentNavigationBar(Build.VERSION.SDK_INT)) {
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                } else {
                    // API 24–25: keep the default semi-opaque scrim behind the light nav icons.
                    enableEdgeToEdge(statusBarStyle = style)
                }
            }
            SmsGateTheme(current) {
                AppRoot(graph)
            }
        }
    }
}
