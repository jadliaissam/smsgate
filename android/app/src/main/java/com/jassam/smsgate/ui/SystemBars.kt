package com.jassam.smsgate.ui

/** System bar decisions for MainActivity's edge-to-edge setup. */
object SystemBars {
    /**
     * Whether the navigation bar may be fully transparent with icons following the app
     * theme. Only from API 26: below it, androidx.activity (EdgeToEdgeApi23) paints the
     * navigation bar with the style's dark scrim and light icons whatever the theme, so
     * a transparent scrim would leave white icons invisible on a light screen. There the
     * default style (semi-opaque dark scrim) is kept.
     */
    fun transparentNavigationBar(sdkInt: Int): Boolean = sdkInt >= 26
}
