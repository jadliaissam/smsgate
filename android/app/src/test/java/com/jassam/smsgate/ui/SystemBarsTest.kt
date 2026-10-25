package com.jassam.smsgate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBarsTest {
    @Test
    fun theNavigationBarIsTransparentOnlyWhereItsIconsCanBeDark() {
        // API 23–25 (EdgeToEdgeApi23) always paints the navigation bar with the style's
        // dark scrim and light icons: a transparent scrim would hide the icons on a light app.
        assertFalse(SystemBars.transparentNavigationBar(24))
        assertFalse(SystemBars.transparentNavigationBar(25))
        assertTrue(SystemBars.transparentNavigationBar(26))
        assertTrue(SystemBars.transparentNavigationBar(37))
    }
}
