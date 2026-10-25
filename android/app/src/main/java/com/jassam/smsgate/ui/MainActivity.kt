package com.jassam.smsgate.ui

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.jassam.smsgate.appGraph
import com.jassam.smsgate.ui.theme.SmsGateTheme
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
        // Paired: periodic + immediate sync (unique KEEP, so recreation adds nothing).
        if (savedInstanceState == null) lifecycleScope.launch { appGraph.onAppStart() }
        setContent {
            SmsGateTheme {
                AppRoot(appGraph)
            }
        }
    }
}
