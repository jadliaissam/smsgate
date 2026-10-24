package com.jassam.smsgate.ui.onboarding

import androidx.annotation.StringRes
import com.jassam.smsgate.R

/** An OEM settings activity to try, most specific first (kept free of Android types for tests). */
data class OemScreen(val packageName: String, val className: String)

/**
 * Manufacturers whose battery managers stop background apps beyond stock
 * Android (D12, see dontkillmyapp.com). Each has plain steps and the
 * autostart/battery screens known to exist on some firmware versions; none is
 * guaranteed, so the screen falls back to the app's settings page.
 */
enum class Oem(@StringRes val label: Int, @StringRes val steps: Int, val screens: List<OemScreen>) {
    Xiaomi(
        R.string.oem_xiaomi,
        R.string.oem_steps_xiaomi,
        listOf(OemScreen("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    ),
    Samsung(
        R.string.oem_samsung,
        R.string.oem_steps_samsung,
        listOf(
            OemScreen("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            OemScreen("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ),
    ),
    Huawei(
        R.string.oem_huawei,
        R.string.oem_steps_huawei,
        listOf(
            OemScreen("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            OemScreen("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ),
    ),
    Oppo(
        R.string.oem_oppo,
        R.string.oem_steps_oppo,
        listOf(
            OemScreen("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            OemScreen("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            OemScreen("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
    ),
    OnePlus(
        R.string.oem_oneplus,
        R.string.oem_steps_oneplus,
        listOf(OemScreen("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
    ),
    Vivo(
        R.string.oem_vivo,
        R.string.oem_steps_vivo,
        listOf(
            OemScreen("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            OemScreen("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ),
    ),
}

object OemGuidance {
    /** The OEM step for `Build.MANUFACTURER` / `Build.BRAND`, or null on stock-like phones. */
    fun detect(manufacturer: String?, brand: String?): Oem? =
        match(manufacturer) ?: match(brand)

    private fun match(raw: String?): Oem? = when (raw?.trim()?.lowercase()) {
        "xiaomi", "redmi", "poco" -> Oem.Xiaomi
        "samsung" -> Oem.Samsung
        "huawei", "honor" -> Oem.Huawei
        "oppo", "realme" -> Oem.Oppo
        "oneplus" -> Oem.OnePlus
        "vivo", "iqoo" -> Oem.Vivo
        else -> null
    }
}
