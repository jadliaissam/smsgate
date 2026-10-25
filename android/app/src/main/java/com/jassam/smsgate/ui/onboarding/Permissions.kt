package com.jassam.smsgate.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Android side of the checklist: reads permission state into an
 * [OnboardingSnapshot] and opens the system screens. Every intent is guarded:
 * a missing settings screen (OEM ROMs, Android Go) falls back to the app's
 * details page and never crashes.
 */
object Permissions {
    const val SMS = Manifest.permission.SEND_SMS
    const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS" // API 33+

    /** READ_PHONE_STATE for the SIM list, plus READ_PHONE_NUMBERS (API 26+) for the optional number. */
    val phone: List<String> =
        if (Build.VERSION.SDK_INT >= 26) listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)
        else listOf(Manifest.permission.READ_PHONE_STATE)

    fun snapshot(activity: Activity, prefs: OnboardingPrefs, oem: Oem?): OnboardingSnapshot = OnboardingSnapshot(
        sdkInt = Build.VERSION.SDK_INT,
        sms = state(activity, prefs, SMS),
        // The item is about the SIM list; the number permission stays optional.
        phoneState = state(activity, prefs, Manifest.permission.READ_PHONE_STATE),
        notifications = if (Build.VERSION.SDK_INT >= 33) state(activity, prefs, NOTIFICATIONS) else PermissionState(true, true, false),
        batteryExempt = batteryExempt(activity),
        oem = oem,
        oemAcknowledged = prefs.oemAcknowledged,
    )

    private fun state(activity: Activity, prefs: OnboardingPrefs, permission: String) = PermissionState(
        granted = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
        asked = prefs.asked(permission),
        showRationale = activity.shouldShowRequestPermissionRationale(permission),
    )

    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    /** The system "allow SMSGate to run in the background?" dialog (D12), or the list screen as a fallback. */
    @SuppressLint("BatteryLife") // sideloaded gateway app: the direct request is intended (manifest note)
    fun requestBatteryExemption(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))
        if (!start(context, direct) && !start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            openAppSettings(context)
        }
    }

    fun openAppSettings(context: Context) {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
        if (!start(context, details)) start(context, Intent(Settings.ACTION_SETTINGS))
    }

    /** Tries the OEM's autostart/battery screens in order, then the app's details page. */
    fun openOemSettings(context: Context, oem: Oem) {
        for (screen in oem.screens) {
            if (start(context, Intent().setClassName(screen.packageName, screen.className))) return
        }
        openAppSettings(context)
    }

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false // OEM activity exists but isn't exported to us
    }
}
