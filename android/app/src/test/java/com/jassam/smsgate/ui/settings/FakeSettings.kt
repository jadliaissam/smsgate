package com.jassam.smsgate.ui.settings

import com.jassam.smsgate.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [LocalPrefs]; [fail] makes writes throw like a broken DataStore. */
internal class FakePrefs : LocalPrefs {
    val theme = MutableStateFlow(ThemeMode.System)
    val label = MutableStateFlow<String?>(null)
    var fail = false
    override val themeMode = theme
    override val deviceLabel = label

    override suspend fun setThemeMode(mode: ThemeMode) {
        if (fail) throw java.io.IOException("disk")
        theme.value = mode
    }

    override suspend fun setDeviceLabel(label: String?) {
        if (fail) throw java.io.IOException("disk")
        this.label.value = label
    }
}
