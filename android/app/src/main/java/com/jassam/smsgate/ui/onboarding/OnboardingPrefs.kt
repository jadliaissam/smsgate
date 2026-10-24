package com.jassam.smsgate.ui.onboarding

import android.content.Context
import androidx.core.content.edit

/**
 * Small local flags for onboarding (one tiny SharedPreferences file, nothing
 * secret): whether the checklist was completed, which permissions were already
 * requested (Android cannot tell "never asked" from "blocked"), and whether the
 * OEM battery step was marked done.
 */
class OnboardingPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    var checklistDone: Boolean
        get() = prefs.getBoolean(KEY_CHECKLIST_DONE, false)
        set(v) = prefs.edit { putBoolean(KEY_CHECKLIST_DONE, v) }

    var oemAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_OEM_DONE, false)
        set(v) = prefs.edit { putBoolean(KEY_OEM_DONE, v) }

    fun asked(permission: String): Boolean = prefs.getBoolean(KEY_ASKED + permission, false)

    fun markAsked(permissions: Collection<String>) = prefs.edit {
        permissions.forEach { putBoolean(KEY_ASKED + it, true) }
    }

    private companion object {
        const val KEY_CHECKLIST_DONE = "checklist_done"
        const val KEY_OEM_DONE = "oem_done"
        const val KEY_ASKED = "asked:"
    }
}
