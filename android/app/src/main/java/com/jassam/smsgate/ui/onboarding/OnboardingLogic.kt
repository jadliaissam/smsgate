package com.jassam.smsgate.ui.onboarding

/** One row of the setup checklist, in display order. */
enum class ChecklistItem { Sms, PhoneState, Notifications, Battery, OemBattery }

/**
 * Where an item stands. [Denied]: refused but Android will ask again;
 * [Blocked]: refused for good ("don't ask again", or a second refusal on
 * Android 11+), so only the app's settings page can change it.
 */
enum class ItemStatus { Done, NotAsked, Denied, Blocked }

/** What the item's button does. */
enum class ItemAction { None, Request, OpenAppSettings, OpenOemSettings }

/**
 * A runtime permission as Android reports it. [asked] is remembered by the app
 * (Android can't tell "never asked" from "blocked": both have no rationale).
 */
data class PermissionState(val granted: Boolean, val asked: Boolean, val showRationale: Boolean)

/** Everything the checklist depends on, captured on each resume. */
data class OnboardingSnapshot(
    val sdkInt: Int,
    val sms: PermissionState,
    val phoneState: PermissionState,
    val notifications: PermissionState,
    val batteryExempt: Boolean,
    val oem: Oem?,
    val oemAcknowledged: Boolean,
)

/**
 * One rendered row. [settingsLink]: also offer "Open app settings" next to the
 * main button (the item was denied once).
 */
data class ChecklistEntry(
    val item: ChecklistItem,
    val status: ItemStatus,
    val required: Boolean,
    val action: ItemAction,
    val settingsLink: Boolean,
)

/** Pure checklist rules, unit-tested; the screen only renders them. */
object OnboardingLogic {
    fun status(p: PermissionState): ItemStatus = when {
        p.granted -> ItemStatus.Done
        !p.asked -> ItemStatus.NotAsked
        p.showRationale -> ItemStatus.Denied
        else -> ItemStatus.Blocked
    }

    fun checklist(s: OnboardingSnapshot): List<ChecklistEntry> = buildList {
        add(permission(ChecklistItem.Sms, s.sms, required = true))
        add(permission(ChecklistItem.PhoneState, s.phoneState, required = false))
        // POST_NOTIFICATIONS is a runtime permission only from Android 13.
        if (s.sdkInt >= 33) add(permission(ChecklistItem.Notifications, s.notifications, required = false))
        add(
            if (s.batteryExempt) {
                ChecklistEntry(ChecklistItem.Battery, ItemStatus.Done, required = false, ItemAction.None, settingsLink = false)
            } else {
                ChecklistEntry(ChecklistItem.Battery, ItemStatus.NotAsked, required = false, ItemAction.Request, settingsLink = false)
            },
        )
        if (s.oem != null) {
            add(
                if (s.oemAcknowledged) {
                    ChecklistEntry(ChecklistItem.OemBattery, ItemStatus.Done, required = false, ItemAction.None, settingsLink = false)
                } else {
                    ChecklistEntry(ChecklistItem.OemBattery, ItemStatus.NotAsked, required = false, ItemAction.OpenOemSettings, settingsLink = false)
                },
            )
        }
    }

    /** Sending SMS is the one thing the app cannot do without; the rest is recommended. */
    fun ready(list: List<ChecklistEntry>): Boolean = list.filter { it.required }.all { it.status == ItemStatus.Done }

    fun doneCount(list: List<ChecklistEntry>): Int = list.count { it.status == ItemStatus.Done }

    private fun permission(item: ChecklistItem, p: PermissionState, required: Boolean): ChecklistEntry {
        val status = status(p)
        val action = when (status) {
            ItemStatus.Done -> ItemAction.None
            ItemStatus.NotAsked, ItemStatus.Denied -> ItemAction.Request
            ItemStatus.Blocked -> ItemAction.OpenAppSettings
        }
        return ChecklistEntry(item, status, required, action, settingsLink = status == ItemStatus.Denied)
    }
}
